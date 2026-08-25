/* artzig stub: minimal legacy FragmentManager (androidx lifecycle ReportFragment injection). */
package android.app;

import java.util.HashMap;
import java.util.Map;

public class FragmentManager {
    private final Map<String, Fragment> mByTag = new HashMap<>();
    public Fragment findFragmentByTag(String tag) { return mByTag.get(tag); }
    public Fragment findFragmentById(int id) { return null; }
    public FragmentTransaction beginTransaction() { return new FragmentTransaction(this); }
    public boolean executePendingTransactions() { return true; }
    public boolean isDestroyed() { return false; }
    public boolean isStateSaved() { return false; }
    public void popBackStack() {}
    public int getBackStackEntryCount() { return 0; }
    void put(String tag, Fragment f) { if (tag != null) mByTag.put(tag, f); }
}
