/* artzig: in-process Android accessibility tree for the headless test interface.
 * Android normally exposes this through system_server and Binder. The hosted
 * runtime keeps the Android semantics local and hands immutable JSON to Zig.
 */
package android.view;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class ArtzigAccessibilityBridge {
    private static final int MAX_NODES = 10_000;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_EVENTS = 128;

    private static ViewRootImpl sActivityRoot;
    private static long sRevision = 1;
    private static long sEventsDroppedThrough;
    private static final ArrayDeque<EventRecord> sEvents = new ArrayDeque<>();

    private ArtzigAccessibilityBridge() {}

    private static final class EventRecord {
        final long revision;
        final String reason;
        EventRecord(long revision, String reason) {
            this.revision = revision;
            this.reason = reason;
        }
    }

    private static final class WindowRecord {
        final long id;
        final ViewRootImpl root;
        final Rect frame;
        final int z;
        final boolean active;
        final boolean overlay;
        WindowRecord(long id, ViewRootImpl root, Rect frame, int z,
                boolean active, boolean overlay) {
            this.id = id;
            this.root = root;
            this.frame = frame;
            this.z = z;
            this.active = active;
            this.overlay = overlay;
        }
    }

    private static final class ActionRecord {
        final int id;
        final CharSequence label;
        ActionRecord(int id, CharSequence label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final class NodeRecord {
        String id;
        String parent;
        long windowId;
        int viewId;
        int virtualId;
        boolean virtual;
        String role;
        CharSequence packageName;
        CharSequence className;
        CharSequence text;
        CharSequence contentDescription;
        CharSequence stateDescription;
        CharSequence hintText;
        String resourceId;
        final Rect bounds = new Rect();
        boolean visible;
        boolean enabled;
        boolean focused;
        boolean selected;
        boolean clickable;
        boolean longClickable;
        boolean checkable;
        boolean checked;
        boolean editable;
        boolean password;
        boolean scrollable;
        final ArrayList<String> children = new ArrayList<>();
        final ArrayList<ActionRecord> actions = new ArrayList<>();
    }

    public static synchronized void setActivityRoot(ViewRootImpl root) {
        sActivityRoot = root;
        forceAccessibilityEnabled(root);
        markChangedLocked("activity-root");
    }

    public static synchronized void markChanged(String reason) {
        markChangedLocked(reason == null ? "changed" : reason);
    }

    public static synchronized void markAccessibilityEvent(AccessibilityEvent event) {
        String reason = "event";
        if (event != null) reason += ":" + event.getEventType();
        markChangedLocked(reason);
    }

    private static void markChangedLocked(String reason) {
        ++sRevision;
        if (sEvents.size() == MAX_EVENTS) {
            sEventsDroppedThrough = sEvents.removeFirst().revision;
        }
        sEvents.addLast(new EventRecord(sRevision, reason));
    }

    public static synchronized long revision() { return sRevision; }

    public static synchronized boolean isIdle() {
        if (sActivityRoot == null || sActivityRoot.mTraversalScheduled) return false;
        for (ArtzigWindowStack.Entry entry : ArtzigWindowStack.entries()) {
            if (entry.root.mTraversalScheduled) return false;
        }
        return true;
    }

    /** Return one immutable snapshot. Must be called on Android's main thread. */
    public static synchronized String snapshot() {
        ArrayList<WindowRecord> windows = collectWindows();
        ArrayList<NodeRecord> nodes = new ArrayList<>();
        boolean[] truncated = new boolean[] { false };
        for (WindowRecord window : windows) {
            collectWindow(window, nodes, truncated);
            if (truncated[0]) break;
        }

        StringBuilder out = new StringBuilder(Math.max(1024, nodes.size() * 320));
        out.append('{');
        field(out, "revision", sRevision).append(',');
        field(out, "truncated", truncated[0]).append(',');
        out.append("\"windows\":[");
        for (int i = 0; i < windows.size(); ++i) {
            if (i != 0) out.append(',');
            appendWindow(out, windows.get(i));
        }
        out.append("],\"nodes\":[");
        for (int i = 0; i < nodes.size(); ++i) {
            if (i != 0) out.append(',');
            appendNode(out, nodes.get(i));
        }
        out.append("]}");
        return out.toString();
    }

    public static synchronized String diagnostics(long afterRevision) {
        StringBuilder out = new StringBuilder(1024);
        out.append('{');
        field(out, "revision", sRevision).append(',');
        field(out, "idle", isIdle()).append(',');
        field(out, "events_dropped_through", sEventsDroppedThrough).append(',');
        out.append("\"events\":[");
        boolean first = true;
        for (EventRecord event : sEvents) {
            if (event.revision <= afterRevision) continue;
            if (!first) out.append(',');
            first = false;
            out.append('{');
            field(out, "revision", event.revision).append(',');
            field(out, "reason", event.reason);
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    /** Perform an action against a node from an exact snapshot revision. */
    public static synchronized boolean perform(long expectedRevision, long windowId,
            int viewId, int virtualId, int action, String text) {
        if (expectedRevision != sRevision) return false;
        ViewRootImpl root = rootForWindow(windowId);
        if (root == null || root.mView == null) return false;
        View view = root.mView.findViewByAccessibilityIdTraversal(viewId);
        if (view == null) return false;

        Bundle arguments = null;
        if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
            arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text == null ? "" : text);
        }

        boolean applied;
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            applied = provider.performAction(virtualId, action, arguments);
        } else if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
            applied = view.performAccessibilityAction(action, arguments);
        } else {
            applied = false;
        }
        if (applied) markChangedLocked("action:" + action);
        return applied;
    }

    private static ArrayList<WindowRecord> collectWindows() {
        ArrayList<WindowRecord> result = new ArrayList<>();
        List<ArtzigWindowStack.Entry> overlays = ArtzigWindowStack.entries();
        ArtzigWindowStack.Entry activeOverlay = ArtzigWindowStack.topFocusable();
        // Frontmost first: selector defaults and diagnostics use visual order.
        for (int i = overlays.size() - 1; i >= 0; --i) {
            ArtzigWindowStack.Entry entry = overlays.get(i);
            result.add(new WindowRecord(entry.root.mArtzigAccessibilityWindowId,
                    entry.root, new Rect(entry.frame), i + 1,
                    entry == activeOverlay, true));
        }
        if (sActivityRoot != null) {
            result.add(new WindowRecord(sActivityRoot.mArtzigAccessibilityWindowId,
                    sActivityRoot, new Rect(0, 0,
                            Math.max(0, sActivityRoot.getWidth()),
                            Math.max(0, sActivityRoot.getHeight())),
                    0, activeOverlay == null, false));
        }
        return result;
    }

    private static ViewRootImpl rootForWindow(long windowId) {
        if (sActivityRoot != null
                && sActivityRoot.mArtzigAccessibilityWindowId == windowId) {
            return sActivityRoot;
        }
        for (ArtzigWindowStack.Entry entry : ArtzigWindowStack.entries()) {
            if (entry.root.mArtzigAccessibilityWindowId == windowId) return entry.root;
        }
        return null;
    }

    private static void collectWindow(WindowRecord window, ArrayList<NodeRecord> out,
            boolean[] truncated) {
        View root = window.root.mView;
        if (root == null) return;
        String parent = "w:" + window.id;
        HashSet<String> seen = new HashSet<>();
        if (root.includeForAccessibility() || root.getAccessibilityNodeProvider() != null) {
            collectView(window, root, parent, out, seen, 0, truncated);
        } else {
            ArrayList<View> children = new ArrayList<>();
            root.addChildrenForAccessibility(children);
            for (View child : children) {
                collectView(window, child, parent, out, seen, 0, truncated);
                if (truncated[0]) return;
            }
        }
    }

    private static String collectView(WindowRecord window, View view, String parent,
            ArrayList<NodeRecord> out, HashSet<String> seen, int depth,
            boolean[] truncated) {
        if (depth > MAX_DEPTH || out.size() >= MAX_NODES) {
            truncated[0] = true;
            return null;
        }
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        if (info == null) return null;
        int virtualId = AccessibilityNodeProvider.HOST_VIEW_ID;
        String id = nodeId(window.id, view.getAccessibilityViewId(), virtualId);
        if (!seen.add(id)) {
            info.recycle();
            return id;
        }
        NodeRecord node = fromInfo(window, view.getAccessibilityViewId(), virtualId,
                parent, info);
        out.add(node);
        try {
            if (provider != null) {
                collectVirtualChildren(window, view, provider, info, node, out,
                        seen, depth + 1, truncated);
            } else {
                ArrayList<View> children = new ArrayList<>();
                view.addChildrenForAccessibility(children);
                for (View child : children) {
                    String childId = collectView(window, child, node.id, out, seen,
                            depth + 1, truncated);
                    if (childId != null) node.children.add(childId);
                    if (truncated[0]) break;
                }
            }
        } finally {
            info.recycle();
        }
        return node.id;
    }

    private static void collectVirtualChildren(WindowRecord window, View host,
            AccessibilityNodeProvider provider, AccessibilityNodeInfo parentInfo,
            NodeRecord parent, ArrayList<NodeRecord> out, HashSet<String> seen,
            int depth, boolean[] truncated) {
        if (depth > MAX_DEPTH) {
            truncated[0] = true;
            return;
        }
        for (int i = 0; i < parentInfo.getChildCount(); ++i) {
            if (out.size() >= MAX_NODES) {
                truncated[0] = true;
                return;
            }
            long childNodeId = parentInfo.getChildId(i);
            if (AccessibilityNodeInfo.getAccessibilityViewId(childNodeId)
                    != host.getAccessibilityViewId()) continue;
            int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(childNodeId);
            if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) continue;
            String id = nodeId(window.id, host.getAccessibilityViewId(), virtualId);
            if (!seen.add(id)) continue;
            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(virtualId);
            if (info == null) continue;
            NodeRecord child = fromInfo(window, host.getAccessibilityViewId(),
                    virtualId, parent.id, info);
            out.add(child);
            parent.children.add(child.id);
            try {
                collectVirtualChildren(window, host, provider, info, child, out,
                        seen, depth + 1, truncated);
            } finally {
                info.recycle();
            }
            if (truncated[0]) return;
        }
    }

    private static NodeRecord fromInfo(WindowRecord window, int viewId, int virtualId,
            String parent, AccessibilityNodeInfo info) {
        NodeRecord node = new NodeRecord();
        node.id = nodeId(window.id, viewId, virtualId);
        node.parent = parent;
        node.windowId = window.id;
        node.viewId = viewId;
        node.virtualId = virtualId;
        node.virtual = virtualId != AccessibilityNodeProvider.HOST_VIEW_ID;
        node.packageName = info.getPackageName();
        node.className = info.getClassName();
        node.role = roleFor(node.className);
        node.password = info.isPassword();
        node.text = node.password ? null : info.getText();
        node.contentDescription = node.password ? null : info.getContentDescription();
        node.stateDescription = info.getStateDescription();
        node.hintText = node.password ? null : info.getHintText();
        node.resourceId = info.getViewIdResourceName();
        info.getBoundsInScreen(node.bounds);
        if (window.overlay) node.bounds.offset(window.frame.left, window.frame.top);
        node.visible = info.isVisibleToUser();
        node.enabled = info.isEnabled();
        node.focused = info.isFocused();
        node.selected = info.isSelected();
        node.clickable = info.isClickable();
        node.longClickable = info.isLongClickable();
        node.checkable = info.isCheckable();
        node.checked = info.isChecked();
        node.editable = info.isEditable();
        node.scrollable = info.isScrollable();
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            node.actions.add(new ActionRecord(action.getId(), action.getLabel()));
        }
        return node;
    }

    private static String nodeId(long windowId, int viewId, int virtualId) {
        return "n:" + windowId + ":" + viewId + ":" + virtualId;
    }

    private static String roleFor(CharSequence classNameValue) {
        String name = classNameValue == null ? "" : classNameValue.toString();
        if (name.endsWith("Button")) return "button";
        if (name.endsWith("EditText") || name.endsWith("AutoCompleteTextView")) return "field";
        if (name.endsWith("CheckBox")) return "checkbox";
        if (name.endsWith("RadioButton")) return "radio";
        if (name.endsWith("Switch") || name.endsWith("ToggleButton")) return "switch";
        if (name.endsWith("ImageView") || name.endsWith("ImageButton")) return "image";
        if (name.endsWith("SeekBar")) return "slider";
        if (name.endsWith("ListView") || name.endsWith("RecyclerView")) return "list";
        if (name.endsWith("ScrollView")) return "scroll";
        if (name.endsWith("TextView")) return "text";
        return "view";
    }

    private static void appendWindow(StringBuilder out, WindowRecord window) {
        out.append('{');
        field(out, "id", window.id).append(',');
        field(out, "z", window.z).append(',');
        field(out, "active", window.active).append(',');
        field(out, "overlay", window.overlay).append(',');
        out.append("\"bounds\":");
        appendRect(out, window.frame);
        out.append('}');
    }

    private static void appendNode(StringBuilder out, NodeRecord node) {
        out.append('{');
        field(out, "id", node.id).append(',');
        field(out, "parent", node.parent).append(',');
        field(out, "window_id", node.windowId).append(',');
        field(out, "view_id", node.viewId).append(',');
        field(out, "virtual_id", node.virtualId).append(',');
        field(out, "virtual", node.virtual).append(',');
        field(out, "role", node.role).append(',');
        field(out, "package", node.packageName).append(',');
        field(out, "class_name", node.className).append(',');
        field(out, "text", node.text).append(',');
        field(out, "content_description", node.contentDescription).append(',');
        field(out, "state_description", node.stateDescription).append(',');
        field(out, "hint", node.hintText).append(',');
        field(out, "resource_id", node.resourceId).append(',');
        out.append("\"bounds\":");
        appendRect(out, node.bounds);
        out.append(',');
        field(out, "visible", node.visible).append(',');
        field(out, "enabled", node.enabled).append(',');
        field(out, "focused", node.focused).append(',');
        field(out, "selected", node.selected).append(',');
        field(out, "clickable", node.clickable).append(',');
        field(out, "long_clickable", node.longClickable).append(',');
        field(out, "checkable", node.checkable).append(',');
        field(out, "checked", node.checked).append(',');
        field(out, "editable", node.editable).append(',');
        field(out, "password", node.password).append(',');
        field(out, "scrollable", node.scrollable).append(',');
        out.append("\"children\":[");
        for (int i = 0; i < node.children.size(); ++i) {
            if (i != 0) out.append(',');
            quote(out, node.children.get(i));
        }
        out.append("],\"actions\":[");
        for (int i = 0; i < node.actions.size(); ++i) {
            if (i != 0) out.append(',');
            ActionRecord action = node.actions.get(i);
            out.append('{');
            field(out, "id", action.id).append(',');
            field(out, "label", action.label);
            out.append('}');
        }
        out.append("]}");
    }

    private static void appendRect(StringBuilder out, Rect rect) {
        out.append('{');
        field(out, "left", rect.left).append(',');
        field(out, "top", rect.top).append(',');
        field(out, "right", rect.right).append(',');
        field(out, "bottom", rect.bottom);
        out.append('}');
    }

    private static StringBuilder field(StringBuilder out, String name, long value) {
        quote(out, name).append(':').append(value);
        return out;
    }

    private static StringBuilder field(StringBuilder out, String name, boolean value) {
        quote(out, name).append(':').append(value);
        return out;
    }

    private static StringBuilder field(StringBuilder out, String name, CharSequence value) {
        quote(out, name).append(':');
        if (value == null) out.append("null"); else quote(out, value);
        return out;
    }

    private static StringBuilder quote(StringBuilder out, CharSequence value) {
        out.append('"');
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int pad = hex.length(); pad < 4; ++pad) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"');
    }

    private static void forceAccessibilityEnabled(ViewRootImpl root) {
        if (root == null) return;
        try {
            AccessibilityManager manager = AccessibilityManager.getInstance(root.mContext);
            Field enabled = AccessibilityManager.class.getDeclaredField("mIsEnabled");
            enabled.setAccessible(true);
            enabled.setBoolean(manager, true);
            Field relevant = AccessibilityManager.class.getDeclaredField("mRelevantEventTypes");
            relevant.setAccessible(true);
            relevant.setInt(manager, -1);
        } catch (Throwable ignored) {
            // Snapshots do not require an enabled manager; this only makes app
            // code which checks AccessibilityManager behave like a real device.
        }
    }
}
