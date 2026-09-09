@file:Suppress("DEPRECATION")

package dev.artzig

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewRootImpl
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.WeakHashMap

/** Main-thread accessibility inspection for the private headless test protocol. */
object ArtzigAccessibilityBridge {
    private const val MAX_NODES = 10_000
    private const val MAX_DEPTH = 128
    private const val MAX_EVENTS = 128
    private var currentRevision = 1L
    private var actionRevision = 1L
    private var eventsDroppedThrough = 0L
    private val events = ArrayDeque<EventRecord>()
    private val windowIds = WeakHashMap<ViewRootImpl, Long>()
    private val observedTrees = WeakHashMap<ViewTreeObserver, Boolean>()
    private val drawObserver = ViewTreeObserver.OnDrawListener { markChanged("draw") }
    private var nextWindowId = 1L
    private var previousTree: String? = null

    // Host instrumentation reads the stock traversal field; the framework has no test hook.
    private val traversalScheduled = ViewRootImpl::class.java.getDeclaredField("mTraversalScheduled")
        .apply { isAccessible = true }

    private data class EventRecord(val revision: Long, val reason: String)
    private data class WindowRecord(
        val id: Long, val root: ViewRootImpl, val frame: Rect, val z: Int,
        val active: Boolean, val overlay: Boolean,
    )

    private val eventObserver = object : AccessibilityManager.AccessibilityPolicy {
        override fun isEnabled(accessibilityEnabled: Boolean) = accessibilityEnabled
        override fun getRelevantEventTypes(relevantEventTypes: Int) = relevantEventTypes
        override fun getInstalledAccessibilityServiceList(services: MutableList<AccessibilityServiceInfo>?) =
            services ?: mutableListOf()
        override fun getEnabledAccessibilityServiceList(feedbackType: Int, services: MutableList<AccessibilityServiceInfo>?) = services
        override fun onAccessibilityEvent(event: AccessibilityEvent, enabled: Boolean, relevantTypes: Int): AccessibilityEvent {
            markAccessibilityEvent(event)
            return event
        }
    }

    @JvmStatic @Synchronized fun markChanged(reason: String?) {
        currentRevision++
        // Repaints reset visual-idle waits but do not invalidate an unchanged
        // accessibility target. observeTree still detects mutation at dispatch.
        if (reason != "draw") actionRevision = currentRevision
        if (events.size == MAX_EVENTS) eventsDroppedThrough = events.removeFirst().revision
        events.addLast(EventRecord(currentRevision, reason ?: "changed"))
    }

    @JvmStatic @Synchronized fun markAccessibilityEvent(event: AccessibilityEvent?) {
        markChanged(if (event == null) "event" else "event:${event.eventType}")
    }

    @JvmStatic @Synchronized fun revision(): Long {
        observeTree()
        return currentRevision
    }

    @JvmStatic @Synchronized fun isIdle(): Boolean {
        val windows = collectWindows()
        return windows.isNotEmpty() && windows.none { traversalScheduled.getBoolean(it.root) }
    }

    @JvmStatic @Synchronized fun snapshot(): String = observeTree().put("revision", currentRevision).toString()

    /** Binder tokens accompany the process-local tree only to the trusted system
     * registry, which asks WMS for task membership before returning observations. */
    @JvmStatic @Synchronized fun processObservation(): Bundle {
        val tree = snapshot()
        val tokens = Bundle()
        for (window in collectWindows()) {
            tokens.putBinder(window.id.toString(), android.view.HostedRootFrames.windowToken(window.root))
        }
        return Bundle().apply {
            putString("snapshot", tree)
            putBundle("window_tokens", tokens)
            putBoolean("idle", isIdle())
        }
    }

    @JvmStatic @Synchronized fun diagnostics(afterRevision: Long): String {
        observeTree()
        val journal = JSONArray()
        for (event in events) if (event.revision > afterRevision) {
            journal.put(JSONObject().put("revision", event.revision).put("reason", event.reason))
        }
        return JSONObject().put("revision", currentRevision).put("idle", isIdle())
            .put("events_dropped_through", eventsDroppedThrough).put("events", journal).toString()
    }

    /** Refresh before checking the revision so unobserved view mutations reject stale actions. */
    @JvmStatic @Synchronized fun perform(
        expectedRevision: Long, windowId: Long, viewId: Int, virtualId: Int, action: Int, text: String?,
    ): Boolean {
        observeTree()
        if (expectedRevision < actionRevision || expectedRevision > currentRevision) return false
        val root = collectWindows().firstOrNull { it.id == windowId }?.root ?: return false
        val view = root.view?.findViewByAccessibilityIdTraversal<View>(viewId) ?: return false
        val arguments = if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text ?: "")
        } else null
        val provider = view.accessibilityNodeProvider
        val applied = when {
            provider != null -> provider.performAction(virtualId, action, arguments)
            virtualId == AccessibilityNodeProvider.HOST_VIEW_ID -> view.performAccessibilityAction(action, arguments)
            else -> false
        }
        if (applied) markChanged("action:$action")
        return applied
    }

    /** Validate a physical-input selection on the same main thread as the
     * tree. The system registry resolves this real IWindow token through WMS. */
    @JvmStatic @Synchronized fun inputWindow(expectedRevision: Long, windowId: Long,
        expectedTarget: String? = null): android.os.IBinder? {
        val snapshot = observeTree()
        if (expectedRevision > currentRevision || snapshot.getBoolean("truncated")) return null
        if (expectedTarget == null) {
            if (expectedRevision < actionRevision) return null
        } else {
            val expected = JSONObject(expectedTarget)
            val node = expected.getJSONObject("node")
            if (node.getLong("window_id") != windowId ||
                !sameJson(expected.getJSONArray("windows"), snapshot.getJSONArray("windows"))) return null
            val nodes = snapshot.getJSONArray("nodes")
            val current = (0 until nodes.length()).asSequence().map { nodes.getJSONObject(it) }
                .singleOrNull { it.getString("id") == node.getString("id") } ?: return null
            if (!current.getBoolean("visible") || !current.getBoolean("enabled") ||
                !sameJson(node, current)) return null
        }
        val root = collectWindows().firstOrNull { it.id == windowId }?.root ?: return null
        return android.view.HostedRootFrames.windowToken(root)
    }

    /** JSONObject key order is not part of the observation contract. */
    private fun sameJson(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject -> left.length() == right.length() &&
            left.keys().asSequence().all { right.has(it) && sameJson(left.get(it), right.get(it)) }
        left is JSONArray && right is JSONArray -> left.length() == right.length() &&
            (0 until left.length()).all { sameJson(left.get(it), right.get(it)) }
        left is Number && right is Number -> java.math.BigDecimal(left.toString())
            .compareTo(java.math.BigDecimal(right.toString())) == 0
        else -> left == right
    }

    private fun observeDraws(root: ViewRootImpl) {
        val tree = root.view?.viewTreeObserver ?: return
        if (tree.isAlive && observedTrees.put(tree, true) == null) {
            AccessibilityManager.getInstance(root.mContext).setAccessibilityPolicy(eventObserver)
            tree.addOnDrawListener(drawObserver)
        }
    }

    private fun windowId(root: ViewRootImpl): Long {
        observeDraws(root)
        return windowIds.getOrPut(root) { nextWindowId++ }
    }

    private fun collectWindows(): List<WindowRecord> {
        val result = ArrayList<WindowRecord>()
        // Pinned WindowManagerGlobal.getWindowViews snapshots the attached roots under
        // its lock. Stock View.getBoundsOnScreen already includes mWindowLeft/Top;
        // no additional frame translation is needed.
        val roots = android.view.HostedRootFrames.managedRoots()
        for (index in roots.indices.reversed()) {
            val root = roots[index]
            val view = root.view ?: continue
            if (android.view.HostedRootFrames.stopped(root) || !view.isShown) continue
            val params = view.layoutParams as android.view.WindowManager.LayoutParams
            result += WindowRecord(windowId(root), root, android.view.HostedRootFrames.frame(root),
                index, view.hasWindowFocus(),
                params.type != android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
        }
        return result
    }

    /** Only test-control queries collect the tree; ordinary draw events just advance the cursor. */
    private fun observeTree(): JSONObject {
        val windows = JSONArray()
        val nodes = JSONArray()
        val truncated = booleanArrayOf(false)
        for (window in collectWindows()) {
            windows.put(JSONObject().put("id", window.id).put("z", window.z)
                .put("active", window.active).put("overlay", window.overlay).put("bounds", rect(window.frame)))
            if (truncated[0]) continue
            android.view.HostedRootFrames.inspectAccessibility(window.root) {
                val root = window.root.view ?: return@inspectAccessibility
                val parent = "w:${window.id}"
                val seen = HashSet<String>()
                if (root.includeForAccessibility() || root.accessibilityNodeProvider != null) {
                    collectView(window, root, parent, nodes, seen, 0, truncated)
                } else {
                    val children = ArrayList<View>()
                    root.addChildrenForAccessibility(children)
                    for (child in children) {
                        collectView(window, child, parent, nodes, seen, 0, truncated)
                        if (truncated[0]) break
                    }
                }
            }
        }
        val tree = JSONObject().put("truncated", truncated[0]).put("windows", windows).put("nodes", nodes)
        val serialized = tree.toString()
        if (previousTree != serialized) {
            previousTree = serialized
            markChanged("tree-changed")
        }
        return tree
    }

    private fun collectView(
        window: WindowRecord, view: View, parent: String, out: JSONArray, seen: MutableSet<String>,
        depth: Int, truncated: BooleanArray,
    ): String? {
        if (depth > MAX_DEPTH || out.length() >= MAX_NODES) {
            truncated[0] = true
            return null
        }
        val info = view.createAccessibilityNodeInfo() ?: return null
        try {
            val id = nodeId(window.id, view.accessibilityViewId, AccessibilityNodeProvider.HOST_VIEW_ID)
            if (!seen.add(id)) return id
            val node = fromInfo(window, view.accessibilityViewId, AccessibilityNodeProvider.HOST_VIEW_ID, parent, info)
            out.put(node)
            val provider = view.accessibilityNodeProvider
            if (provider != null) {
                collectVirtualChildren(window, view, provider, info, node, out, seen, depth + 1, truncated)
            } else {
                val children = ArrayList<View>()
                view.addChildrenForAccessibility(children)
                for (child in children) {
                    collectView(window, child, id, out, seen, depth + 1, truncated)?.let {
                        node.getJSONArray("children").put(it)
                    }
                    if (truncated[0]) break
                }
            }
            return id
        } finally {
            info.recycle()
        }
    }

    private fun collectVirtualChildren(
        window: WindowRecord, host: View, provider: AccessibilityNodeProvider,
        parentInfo: AccessibilityNodeInfo, parent: JSONObject, out: JSONArray,
        seen: MutableSet<String>, depth: Int, truncated: BooleanArray,
    ) {
        if (depth > MAX_DEPTH) { truncated[0] = true; return }
        for (index in 0 until parentInfo.childCount) {
            if (out.length() >= MAX_NODES) { truncated[0] = true; return }
            val childNodeId = parentInfo.getChildId(index)
            if (AccessibilityNodeInfo.getAccessibilityViewId(childNodeId) != host.accessibilityViewId) continue
            val virtualId = AccessibilityNodeInfo.getVirtualDescendantId(childNodeId)
            if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) continue
            val id = nodeId(window.id, host.accessibilityViewId, virtualId)
            if (!seen.add(id)) continue
            val info = provider.createAccessibilityNodeInfo(virtualId) ?: continue
            try {
                val child = fromInfo(window, host.accessibilityViewId, virtualId, parent.getString("id"), info)
                out.put(child)
                parent.getJSONArray("children").put(id)
                collectVirtualChildren(window, host, provider, info, child, out, seen, depth + 1, truncated)
            } finally {
                info.recycle()
            }
            if (truncated[0]) return
        }
    }

    private fun fromInfo(window: WindowRecord, viewId: Int, virtualId: Int, parent: String, info: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect()
        info.getBoundsInScreen(bounds)
        fun value(text: CharSequence?): Any = text?.toString() ?: JSONObject.NULL
        val actions = JSONArray()
        for (action in info.actionList) actions.put(JSONObject().put("id", action.id).put("label", value(action.label)))
        return JSONObject().put("id", nodeId(window.id, viewId, virtualId)).put("parent", parent)
            .put("window_id", window.id).put("view_id", viewId).put("virtual_id", virtualId)
            .put("virtual", virtualId != AccessibilityNodeProvider.HOST_VIEW_ID)
            .put("role", roleFor(info.className)).put("package", value(info.packageName))
            .put("class_name", value(info.className)).put("text", value(if (info.isPassword) null else info.text))
            .put("content_description", value(if (info.isPassword) null else info.contentDescription))
            .put("state_description", value(info.stateDescription)).put("hint", value(if (info.isPassword) null else info.hintText))
            .put("resource_id", value(info.viewIdResourceName)).put("bounds", rect(bounds))
            .put("visible", info.isVisibleToUser).put("enabled", info.isEnabled).put("focused", info.isFocused)
            .put("selected", info.isSelected).put("clickable", info.isClickable).put("long_clickable", info.isLongClickable)
            .put("checkable", info.isCheckable).put("checked", info.isChecked).put("editable", info.isEditable)
            .put("password", info.isPassword).put("scrollable", info.isScrollable)
            .put("children", JSONArray()).put("actions", actions)
    }

    private fun nodeId(windowId: Long, viewId: Int, virtualId: Int) = "n:$windowId:$viewId:$virtualId"
    private fun rect(rect: Rect) = JSONObject().put("left", rect.left).put("top", rect.top)
        .put("right", rect.right).put("bottom", rect.bottom)

    private fun roleFor(className: CharSequence?): String {
        val name = className?.toString() ?: ""
        return when {
            name.endsWith("Button") -> "button"
            name.endsWith("EditText") || name.endsWith("AutoCompleteTextView") -> "field"
            name.endsWith("CheckBox") -> "checkbox"
            name.endsWith("RadioButton") -> "radio"
            name.endsWith("Switch") || name.endsWith("ToggleButton") -> "switch"
            name.endsWith("ImageView") || name.endsWith("ImageButton") -> "image"
            name.endsWith("SeekBar") -> "slider"
            name.endsWith("ListView") || name.endsWith("RecyclerView") -> "list"
            name.endsWith("ScrollView") -> "scroll"
            name.endsWith("TextView") -> "text"
            else -> "view"
        }
    }
}
