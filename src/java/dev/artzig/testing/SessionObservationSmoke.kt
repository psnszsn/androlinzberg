package dev.artzig.testing

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

object SessionObservationSmoke {
    private data class Peer(val pid: Int, var generation: Long, val task: Int?,
        var revision: Long = 1, var selected: Boolean = false, var failed: Boolean = false, var starting: Boolean = false, var malformed: Int = 0)

    fun run() {
        val peers = linkedMapOf(10 to Peer(10, 1, 100), 20 to Peer(20, 2, 200), 30 to Peer(30, 3, null))
        val calls = ArrayList<List<Long>>()
        val registry = object : IInspectionRegistry.Default() {
            override fun listProcesses() = Bundle().apply {
                putParcelableArrayList("processes", ArrayList(peers.values.map { peer -> Bundle().apply {
                    putInt("pid", peer.pid); putInt("uid", 10000 + peer.pid)
                    putLong("generation", peer.generation); putString("name", "peer-${peer.pid}")
                } }))
            }
            override fun observe(pid: Int, generation: Long, afterRevision: Long, afterLog: Long, afterIssue: Long): Bundle {
                val peer = checkNotNull(peers[pid])
                check(peer.generation == generation)
                if (peer.failed) error("Application observation timed out")
                if (peer.starting) return Bundle().apply {
                    putBoolean("starting", true)
                    putString("runtime", JSONObject().put("log_cursor", 0).put("issue_cursor", 0).toString())
                }
                val windows = JSONArray()
                val nodes = JSONArray()
                val tasks = Bundle()
                if (peer.task != null) {
                    tasks.putInt("1", peer.task)
                    windows.put(JSONObject().put("id", 1).put("z", 0).put("active", pid == 20))
                    nodes.put(JSONObject().put("id", "n:1:7:-1").put("parent", "w:1").put("window_id", 1)
                        .put("view_id", 7).put("virtual_id", -1).put("text", "same label")
                        .put("selected", peer.selected).put("children", JSONArray().put("n:1:8:-1")))
                    nodes.put(JSONObject().put("id", "n:1:8:-1").put("parent", "n:1:7:-1").put("window_id", 1)
                        .put("view_id", 8).put("virtual_id", -1).put("children", JSONArray()))
                }
                when (peer.malformed) {
                    1 -> windows.put(JSONObject(windows.getJSONObject(0).toString()))
                    2 -> nodes.getJSONObject(0).put("parent", "w:999")
                    3 -> nodes.getJSONObject(0).getJSONArray("children").put("n:999:8:-1")
                    4 -> nodes.getJSONObject(0).put("window_id", 999)
                    5 -> nodes.put(JSONObject(nodes.getJSONObject(0).toString()))
                    6 -> nodes.getJSONObject(0).put("id", "n:1:bad:-1")
                    7 -> nodes.getJSONObject(0).put("children", "bad")
                    8 -> windows.getJSONObject(0).put("id", 0)
                }
                return Bundle().apply {
                    putString("snapshot", JSONObject().put("revision", peer.revision).put("windows", windows)
                        .put("nodes", nodes).put("truncated", false).toString())
                    putBundle("window_tasks", tasks)
                    putBoolean("idle", peer.task != null)
                    putString("accessibility", JSONObject().put("revision", peer.revision)
                        .put("events_dropped_through", 0).put("events", JSONArray().apply {
                            if (peer.revision > afterRevision) put(JSONObject().put("revision", peer.revision).put("reason", "tree-changed"))
                        }).toString())
                    putString("runtime", JSONObject().put("log_cursor", 0).put("issue_cursor", 0).toString())
                }
            }
            override fun validateInputTarget(pid: Int, generation: Long, revision: Long, windowId: Long, expectedTarget: String?): Int {
                val peer = peers[pid] ?: return -1
                if (peer.generation != generation || revision > peer.revision || windowId != 1L || peer.failed || peer.starting) return -1
                if (expectedTarget == null) {
                    if (peer.revision != revision) return -1
                } else {
                    val expected = JSONObject(expectedTarget)
                    val node = expected.getJSONObject("node")
                    check(node.getLong("window_id") == 1L && node.getString("id") == "n:1:7:-1")
                    check(expected.getJSONArray("windows").getJSONObject(0).getLong("id") == 1L)
                    if (node.getBoolean("selected") != peer.selected) return -1
                }
                return peer.task ?: -1
            }
            override fun perform(pid: Int, generation: Long, revision: Long, windowId: Long,
                viewId: Int, virtualId: Int, action: Int, text: String?): Boolean {
                val peer = checkNotNull(peers[pid])
                check(peer.generation == generation)
                if (peer.revision != revision) return false
                calls += listOf(pid.toLong(), generation, windowId, viewId.toLong())
                peer.selected = true
                peer.revision++
                return true
            }
        }
        val observation = SessionObservation(registry)
        peers.getValue(20).starting = true
        observation.refresh()
        check(!observation.idle())
        val startup = JSONObject(observation.snapshot())
        check(startup.getJSONArray("starting_processes").length() == 1)
        check(startup.getJSONArray("unavailable_processes").length() == 0)
        val startupRevision = observation.revision()
        observation.refresh()
        check(observation.revision() == startupRevision)
        peers.getValue(20).starting = false
        observation.refresh()
        check(observation.idle()) { "A service process without windows prevented UI idle" }
        fun tree() = JSONObject(observation.snapshot())
        fun window(pid: Int): Long {
            val windows = tree().getJSONArray("windows")
            return (0 until windows.length()).map { windows.getJSONObject(it) }
                .single { it.getInt("pid") == pid }.getLong("id")
        }
        val first = window(10)
        val second = window(20)
        check(first != second)
        val nodes = tree().getJSONArray("nodes")
        check(nodes.getJSONObject(0).getString("id") == "n:$first:7:-1")
        check(nodes.getJSONObject(0).getJSONArray("children").getString(0) == "n:$first:8:-1")
        check(nodes.getJSONObject(1).getString("parent") == "n:$first:7:-1")
        check(nodes.getJSONObject(2).getInt("task_id") == 200)
        val stable = observation.revision()
        val input = checkNotNull(observation.inputTarget(stable, second))
        val selectedInput = checkNotNull(observation.inputTarget(stable, second, 7, -1))
        check(input.taskId == 200 && observation.validateInputTarget(input))
        check(observation.inputTarget(stable, Long.MAX_VALUE) == null)
        observation.refresh()
        check(observation.revision() == stable)
        peers.getValue(20).revision++ // An app mutation must reject the saved global revision.
        check(!observation.validateInputTarget(input))
        check(observation.validateInputTarget(selectedInput))
        check(!observation.perform(stable, second, 7, -1, 4, null) && calls.isEmpty())
        check(observation.perform(observation.revision(), second, 7, -1, 4, null))
        check(calls.single() == listOf(20L, 2L, 1L, 7L))
        check(tree().getJSONArray("nodes").getJSONObject(2).getBoolean("selected"))
        check(!observation.validateInputTarget(selectedInput))
        peers.getValue(20).failed = true
        observation.refresh()
        check(!observation.idle() && tree().getJSONArray("unavailable_processes").length() == 1)
        check(!observation.perform(observation.revision(), second, 7, -1, 4, null))
        peers.getValue(20).failed = false
        observation.refresh()
        check(window(20) == second) { "A temporary observation failure changed a live window's identity" }
        for (malformed in 1..8) {
            peers.getValue(20).malformed = malformed
            observation.refresh()
            check(!observation.idle())
            check(tree().getJSONArray("unavailable_processes").length() == 1)
            check(tree().getJSONArray("windows").length() == 1 && window(10) == first)
            check(tree().getJSONArray("nodes").length() == 2)
            check(!observation.perform(observation.revision(), second, 7, -1, 4, null))
            check(observation.inputTarget(observation.revision(), second) == null)
            check(observation.perform(observation.revision(), first, 7, -1, 4, null))
            val failedRevision = observation.revision()
            observation.refresh()
            check(observation.revision() == failedRevision) { "Unchanged malformed peer churned the session revision" }
            peers.getValue(20).malformed = 0
            observation.refresh()
            check(observation.idle() && window(20) == second && window(10) == first)
        }
        peers[20] = Peer(20, 4, 300)
        observation.refresh()
        check(window(20) != second && window(10) == first)
        check(!observation.perform(observation.revision(), second, 7, -1, 4, null))
        peers.remove(10)
        observation.refresh()
        check(tree().getJSONArray("windows").length() == 1)
        check(!observation.perform(observation.revision(), first, 7, -1, 4, null))
    }
}
