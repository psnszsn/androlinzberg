/* artzig stub: records dynamic shortcuts without a desktop launcher backend. */
package android.content.pm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShortcutManager {
    private final ArrayList<ShortcutInfo> mDynamicShortcuts = new ArrayList<>();

    public boolean setDynamicShortcuts(List<ShortcutInfo> shortcuts) {
        mDynamicShortcuts.clear();
        mDynamicShortcuts.addAll(shortcuts);
        return true;
    }
    public List<ShortcutInfo> getDynamicShortcuts() {
        return new ArrayList<>(mDynamicShortcuts);
    }
    public List<ShortcutInfo> getManifestShortcuts() { return new ArrayList<>(); }
    public List<ShortcutInfo> getPinnedShortcuts() { return new ArrayList<>(); }
    public boolean addDynamicShortcuts(List<ShortcutInfo> shortcuts) {
        mDynamicShortcuts.addAll(shortcuts);
        return true;
    }
    public boolean updateShortcuts(List<ShortcutInfo> shortcuts) {
        removeDynamicShortcuts(ids(shortcuts));
        mDynamicShortcuts.addAll(shortcuts);
        return true;
    }
    public void pushDynamicShortcut(ShortcutInfo shortcut) {
        removeDynamicShortcuts(java.util.Collections.singletonList(shortcut.getId()));
        mDynamicShortcuts.add(shortcut);
    }
    public void removeDynamicShortcuts(List<String> shortcutIds) {
        Set<String> ids = new HashSet<>(shortcutIds);
        mDynamicShortcuts.removeIf(shortcut -> ids.contains(shortcut.getId()));
    }
    public void removeLongLivedShortcuts(List<String> shortcutIds) {
        removeDynamicShortcuts(shortcutIds);
    }
    public void removeAllDynamicShortcuts() { mDynamicShortcuts.clear(); }
    public void disableShortcuts(List<String> shortcutIds) {}
    public void disableShortcuts(List<String> shortcutIds, CharSequence message) {}
    public void enableShortcuts(List<String> shortcutIds) {}
    public int getMaxShortcutCountPerActivity() { return 10; }
    public boolean isRateLimitingActive() { return false; }
    public int getIconMaxWidth() { return 96; }
    public int getIconMaxHeight() { return 96; }
    public void reportShortcutUsed(String shortcutId) {}
    public boolean isRequestPinShortcutSupported() { return false; }
    public boolean requestPinShortcut(ShortcutInfo shortcut, android.content.IntentSender resultIntent) {
        return false;
    }
    public android.content.Intent createShortcutResultIntent(ShortcutInfo shortcut) {
        return new android.content.Intent();
    }

    private static List<String> ids(List<ShortcutInfo> shortcuts) {
        ArrayList<String> result = new ArrayList<>();
        for (ShortcutInfo shortcut : shortcuts) result.add(shortcut.getId());
        return result;
    }
}
