/* artzig stub: records tag->fragment into the owning FragmentManager on commit. */
package android.app;

import java.util.ArrayList;
import java.util.List;

public class FragmentTransaction {
    private final FragmentManager mMgr;
    private final List<Object[]> mAdds = new ArrayList<>();
    FragmentTransaction(FragmentManager mgr) { mMgr = mgr; }
    public FragmentTransaction() { mMgr = null; }
    public FragmentTransaction add(Fragment fragment, String tag) {
        mAdds.add(new Object[] { fragment, tag });
        return this;
    }
    public FragmentTransaction add(int containerViewId, Fragment fragment) { return this; }
    public FragmentTransaction add(int containerViewId, Fragment fragment, String tag) {
        mAdds.add(new Object[] { fragment, tag });
        return this;
    }
    public FragmentTransaction remove(Fragment fragment) { return this; }
    public FragmentTransaction replace(int containerViewId, Fragment fragment) { return this; }
    public FragmentTransaction replace(int containerViewId, Fragment fragment, String tag) { return this; }
    public FragmentTransaction addToBackStack(String name) { return this; }
    public int commit() { return apply(); }
    public int commitAllowingStateLoss() { return apply(); }
    public void commitNow() { apply(); }
    public void commitNowAllowingStateLoss() { apply(); }
    private int apply() {
        if (mMgr != null) for (Object[] a : mAdds) mMgr.put((String) a[1], (Fragment) a[0]);
        return 0;
    }
}
