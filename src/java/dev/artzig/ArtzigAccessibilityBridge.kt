@file:Suppress("DEPRECATION")

package dev.artzig

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import dev.artzig.HostedWindowStack
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
    private var activityRoot: ViewRootImpl? = null
    private var currentRevision = 1L
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

    @JvmStatic @Synchronized fun setActivityRoot(root: ViewRootImpl?) {
        activityRoot = root
        if (root != null) AccessibilityManager.getInstance(root.mContext).setAccessibilityPolicy(eventObserver)
        if (root != null) observeDraws(root)
        markChanged("activity-root")
    }

    @JvmStatic @Synchronized fun markChanged(reason: String?) {
        currentRevision++
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

    @JvmStatic @Synchronized fun isIdle(): Boolean =
        activityRoot != null && collectWindows().none { traversalScheduled.getBoolean(it.root) }

    @JvmStatic @Synchronized fun snapshot(): String = observeTree().put("revision", currentRevision).toString()

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
        if (expectedRevision != currentRevision) return false
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

    private fun observeDraws(root: ViewRootImpl) {
        val tree = root.view?.viewTreeObserver ?: return
        if (tree.isAlive && observedTrees.put(tree, true) == null) tree.addOnDrawListener(drawObserver)
    }

    private fun windowId(root: ViewRootImpl): Long {
        observeDraws(root)
        return windowIds.getOrPut(root) { nextWindowId++ }
    }

    private fun collectWindows(): List<WindowRecord> {
        val result = ArrayList<WindowRecord>()
        val overlays = HostedWindowStack.entries()
        val active = HostedWindowStack.topFocusable()
        for (index in overlays.indices.reversed()) {
            val entry = overlays[index]
            if (entry.root.`isWindowStopped$artzig`()) continue
            result += WindowRecord(windowId(entry.root), entry.root, Rect(entry.frame), index + 1,
                entry === active, true)
        }
        activityRoot?.let { root ->
            if (root.`isWindowStopped$artzig`()) return@let
            result += WindowRecord(windowId(root), root, Rect(0, 0, root.width.coerceAtLeast(0),
                root.height.coerceAtLeast(0)), 0, active == null, false)
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
            val root = window.root.view ?: continue
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
        if (window.overlay) bounds.offset(window.frame.left, window.frame.top)
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
