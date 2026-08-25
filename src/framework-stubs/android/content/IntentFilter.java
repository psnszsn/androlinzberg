/* artzig shim: the action-matching part of IntentFilter used by the
 * in-process broadcast dispatcher. */
package android.content;

import java.util.ArrayList;

public class IntentFilter {
    private final ArrayList<String> mActions = new ArrayList<>();

    public IntentFilter() {}

    public IntentFilter(String action) {
        addAction(action);
    }

    public void addAction(String action) {
        if (action == null) throw new IllegalArgumentException("action must not be null");
        if (!mActions.contains(action)) mActions.add(action);
    }

    public int countActions() { return mActions.size(); }

    public String getAction(int index) { return mActions.get(index); }

    public boolean hasAction(String action) { return mActions.contains(action); }

    public boolean matchAction(String action) { return hasAction(action); }

    public void addCategory(String category) {}
    public void addDataScheme(String scheme) {}
}
