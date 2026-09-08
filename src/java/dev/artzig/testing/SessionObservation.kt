package dev.artzig.testing

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Session-level window identities and observations over the real process
 * registry. The controller calls this on its own main thread. */
class SessionObservation(private val registry: IInspectionRegistry) {
    private data class ProcessKey(val pid: Int, val generation: Long)
    private class Peer(val metadata: Bundle) {
        var revision = 0L
        var observation: Bundle? = null
        var tree: JSONObject? = null
        var failure: String? = null
        var starting = false
        var logCursor = 0L
        var issueCursor = 0L
    }
    private data class WindowKey(val process: ProcessKey, val localId: Long)
    private val peers = LinkedHashMap<ProcessKey, Peer>()
    private val windowIds = LinkedHashMap<WindowKey, Long>()
    private var nextWindowId = 1L
    private val events = ArrayDeque<JSONObject>()
    private var eventsDropped = 0L
    private var revision = 1L
    private var signature = ""
    private var tree = JSONObject().put("windows", JSONArray()).put("nodes", JSONArray()).put("truncated", false)

    fun refresh() {
        val processes = registry.listProcesses().getParcelableArrayList("processes", Bundle::class.java).orEmpty()
        check(processes.size <= 64) { "Too many inspected processes" }
        val current = LinkedHashSet<ProcessKey>()
        for (metadata in processes) {
            val key = ProcessKey(metadata.getInt("pid"), metadata.getLong("generation"))
            check(key.pid > 0 && key.generation > 0 && current.add(key)) { "Invalid or repeated inspection process identity" }
            val peer = peers.getOrPut(key) { event("process-added", key); Peer(metadata) }
            try {
                val observation = registry.observe(key.pid, key.generation, peer.revision, peer.logCursor, peer.issueCursor)
                val runtime = JSONObject(checkNotNull(observation.getString("runtime")))
                peer.logCursor = runtime.getLong("log_cursor")
                peer.issueCursor = runtime.getLong("issue_cursor")
                if (observation.getBoolean("starting")) {
                    if (!peer.starting) event("process-starting", key)
                    peer.starting = true
                    peer.failure = null
                    peer.observation = observation
                    peer.tree = null
                    continue
                }
                val observedTree = JSONObject(checkNotNull(observation.getString("snapshot")))
                validateTree(observedTree)
                val observedRevision = observedTree.getLong("revision")
                check(observedRevision >= peer.revision) { "Process tree revision rewound" }
                val accessibility = JSONObject(checkNotNull(observation.getString("accessibility")))
                if (accessibility.getLong("events_dropped_through") > peer.revision) event("source-events-lost", key)
                val changes = accessibility.getJSONArray("events")
                for (index in 0 until changes.length()) {
                    val change = changes.getJSONObject(index)
                    if (change.getLong("revision") > peer.revision) event(change.getString("reason"), key, change.getLong("revision"))
                }
                if (peer.failure != null) event("process-observation-restored", key)
                if (peer.starting) event("process-ready", key)
                peer.starting = false
                peer.revision = observedRevision
                peer.observation = observation
                peer.tree = observedTree
                peer.failure = null
            } catch (failure: Exception) {
                if (peer.failure != failure.toString()) event("process-unavailable", key)
                peer.starting = false
                peer.failure = failure.toString()
                peer.observation = null
                peer.tree = null
            }
        }
        for (key in peers.keys.toList()) if (key !in current) {
            peers.remove(key)
            event("process-removed", key)
        }
        rebuildTree()
    }

    /** Validate a complete peer contribution before publishing any of it. A
     * malformed app tree must become an unavailable peer, not abort aggregation
     * after some of its windows have already acquired session identities.
     * Children may refer to omitted nodes in a deliberately truncated tree.
     */
    private fun validateTree(local: JSONObject) {
        local.getBoolean("truncated")
        val windows = local.getJSONArray("windows")
        val nodes = local.getJSONArray("nodes")
        require(windows.length() <= 1024 && nodes.length() <= 10_000) { "Process tree exceeds observation limits" }
        val ids = HashSet<Long>()
        for (index in 0 until windows.length()) {
            val id = windows.getJSONObject(index).getLong("id")
            require(id > 0 && ids.add(id)) { "Invalid or repeated process window identity" }
        }
        fun nodeWindow(value: String, rootAllowed: Boolean): Long {
            val parts = value.split(':')
            require(parts.size == 4 && parts[0] == "n" ||
                rootAllowed && parts.size == 2 && parts[0] == "w") { "Invalid process node identity" }
            val window = parts[1].toLong()
            require(window in ids && parts[1] == window.toString()) { "Node belongs to an unobserved window" }
            if (parts.size == 4) {
                require(parts[2] == parts[2].toInt().toString() && parts[3] == parts[3].toInt().toString()) {
                    "Invalid process node components"
                }
            }
            return window
        }
        val nodeIds = HashSet<String>()
        var childCount = 0
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            val id = node.getString("id")
            val window = node.getLong("window_id")
            require(nodeIds.add(id) && nodeWindow(id, false) == window) { "Invalid or repeated process node identity" }
            require(nodeWindow(node.getString("parent"), true) == window) { "Node parent crosses windows" }
            val children = node.getJSONArray("children")
            childCount += children.length()
            require(childCount <= 20_000) { "Process tree exceeds child limit" }
            for (child in 0 until children.length()) require(nodeWindow(children.getString(child), false) == window) {
                "Node child crosses windows"
            }
        }
    }

    private fun rebuildTree() {
        val windows = JSONArray()
        val nodes = JSONArray()
        val unavailable = JSONArray()
        val starting = JSONArray()
        val seenWindows = HashSet<WindowKey>()
        var truncated = false
        for ((key, peer) in peers) {
            val local = peer.tree
            if (local == null) {
                val state = JSONObject().put("pid", key.pid).put("generation", key.generation)
                if (peer.starting) starting.put(state)
                else unavailable.put(state.put("error", peer.failure))
                continue
            }
            truncated = truncated || local.getBoolean("truncated")
            val localWindows = local.getJSONArray("windows")
            val ids = HashMap<Long, Long>()
            val tasks = checkNotNull(peer.observation).getBundle("window_tasks") ?: Bundle()
            for (index in 0 until localWindows.length()) {
                val window = JSONObject(localWindows.getJSONObject(index).toString())
                val localId = window.getLong("id")
                val windowKey = WindowKey(key, localId)
                check(seenWindows.add(windowKey)) { "Repeated process window identity" }
                val id = windowIds.getOrPut(windowKey) { nextWindowId++ }
                ids[localId] = id
                window.put("id", id).put("pid", key.pid).put("process_generation", key.generation)
                    .put("task_id", if (tasks.containsKey(localId.toString())) tasks.getInt(localId.toString()) else JSONObject.NULL)
                windows.put(window)
            }
            fun globalNodeId(value: String): String {
                val parts = value.split(':')
                require(parts.size == 2 && parts[0] == "w" || parts.size == 4 && parts[0] == "n") { "Invalid process node identity" }
                val window = checkNotNull(ids[parts[1].toLong()]) { "Node belongs to an unobserved window" }
                return if (parts[0] == "w") "w:$window" else "n:$window:${parts[2]}:${parts[3]}"
            }
            val localNodes = local.getJSONArray("nodes")
            for (index in 0 until localNodes.length()) {
                if (nodes.length() == 10_000) { truncated = true; break }
                val node = JSONObject(localNodes.getJSONObject(index).toString())
                val localWindow = node.getLong("window_id")
                node.put("id", globalNodeId(node.getString("id")))
                    .put("parent", globalNodeId(node.getString("parent")))
                    .put("window_id", checkNotNull(ids[localWindow]))
                    .put("pid", key.pid).put("process_generation", key.generation)
                    .put("task_id", if (tasks.containsKey(localWindow.toString())) tasks.getInt(localWindow.toString()) else JSONObject.NULL)
                val children = node.getJSONArray("children")
                for (child in 0 until children.length()) children.put(child, globalNodeId(children.getString(child)))
                nodes.put(node)
            }
        }
        windowIds.keys.removeAll { it !in seenWindows && peers[it.process]?.failure == null }
        val next = JSONObject().put("windows", windows).put("nodes", nodes).put("truncated", truncated)
            .put("unavailable_processes", unavailable).put("starting_processes", starting)
        val nextSignature = next.toString()
        if (signature != nextSignature) { signature = nextSignature; event("tree-changed") }
        tree = next
    }

    fun snapshot(): String = JSONObject(tree.toString()).put("revision", revision).toString()
    fun revision(): Long = revision
    fun idle(): Boolean = peers.isNotEmpty() && tree.getJSONArray("windows").length() > 0 &&
        peers.values.all { !it.starting && it.failure == null &&
            (it.tree?.getJSONArray("windows")?.length() == 0 || it.observation?.getBoolean("idle") == true) }

    fun diagnostics(afterRevision: Long): String = JSONObject().put("revision", revision).put("idle", idle())
        .put("events_dropped_through", eventsDropped).put("events", JSONArray().apply {
            for (event in events) if (event.getLong("revision") > afterRevision) put(JSONObject(event.toString()))
        }).toString()

    fun runtimeDiagnostics(afterLog: Long, afterIssue: Long): String = registry.diagnostics(afterLog, afterIssue)

    fun perform(expectedRevision: Long, windowId: Long, viewId: Int, virtualId: Int, action: Int, text: String?): Boolean {
        // Resolve the published global identity, then let the app atomically
        // refresh and validate its target on main. A separate preflight observe
        // adds an IPC race and rejects harmless draws before every action.
        if (expectedRevision != revision) return false
        val window = windowIds.entries.singleOrNull { it.value == windowId }?.key ?: return false
        val peer = peers[window.process] ?: return false
        if (peer.observation == null || peer.starting) return false
        val applied = registry.perform(window.process.pid, window.process.generation, peer.revision,
            window.localId, viewId, virtualId, action, text)
        refresh()
        return applied
    }

    data class InputTarget internal constructor(val taskId: Int, internal val pid: Int,
        internal val generation: Long, internal val revision: Long, internal val windowId: Long,
        internal val expectedTarget: String?)

    fun inputTarget(expectedRevision: Long, windowId: Long, viewId: Int? = null, virtualId: Int? = null): InputTarget? {
        if (expectedRevision != revision) return null
        val window = windowIds.entries.singleOrNull { it.value == windowId }?.key ?: return null
        val peer = peers[window.process] ?: return null
        if (peer.starting || peer.failure != null) return null
        val tasks = peer.observation?.getBundle("window_tasks") ?: return null
        if (!tasks.containsKey(window.localId.toString())) return null
        val expectedTarget = if (viewId != null && virtualId != null) {
            val local = peer.tree ?: return null
            if (local.getBoolean("truncated")) return null
            val nodes = local.getJSONArray("nodes")
            val node = (0 until nodes.length()).asSequence().map { nodes.getJSONObject(it) }
                .singleOrNull { it.getLong("window_id") == window.localId &&
                    it.getInt("view_id") == viewId && it.getInt("virtual_id") == virtualId } ?: return null
            // Keep process-local identities. Window order/focus/geometry must
            // also remain unchanged when the app validates the selected widget.
            JSONObject().put("node", node).put("windows", local.getJSONArray("windows")).toString()
        } else null
        return InputTarget(tasks.getInt(window.localId.toString()), window.process.pid,
            window.process.generation, peer.revision, window.localId, expectedTarget).takeIf(::validateInputTarget)
    }

    fun validateInputTarget(target: InputTarget): Boolean = registry.validateInputTarget(target.pid,
        target.generation, target.revision, target.windowId, target.expectedTarget) == target.taskId

    private fun event(reason: String, process: ProcessKey? = null, sourceRevision: Long? = null) {
        val event = JSONObject().put("revision", ++revision).put("reason", reason)
        if (process != null) event.put("pid", process.pid).put("process_generation", process.generation)
        if (sourceRevision != null) event.put("source_revision", sourceRevision)
        if (events.size == 128) eventsDropped = events.removeFirst().getLong("revision")
        events.addLast(event)
    }
}
