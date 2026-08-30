/* artzig shim: the matching part of IntentFilter used by the hosted broadcast
 * dispatcher. Parcel layout follows AOSP IntentFilter.writeToParcel for the
 * fields this shim has; absent lists write their null markers. */
package android.content;

import java.util.ArrayList;

public class IntentFilter implements android.os.Parcelable {
    private final ArrayList<String> mActions = new ArrayList<>();
    private ArrayList<String> mCategories;
    private ArrayList<String> mDataSchemes;
    private int mPriority;

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

    public void addCategory(String category) {
        if (mCategories == null) mCategories = new ArrayList<>();
        if (!mCategories.contains(category)) mCategories.add(category);
    }
    public boolean hasCategory(String category) { return mCategories != null && mCategories.contains(category); }
    public int countCategories() { return mCategories != null ? mCategories.size() : 0; }
    public String getCategory(int index) { return mCategories.get(index); }
    public void addDataScheme(String scheme) {
        if (mDataSchemes == null) mDataSchemes = new ArrayList<>();
        if (!mDataSchemes.contains(scheme)) mDataSchemes.add(scheme);
    }
    public boolean hasDataScheme(String scheme) { return mDataSchemes != null && mDataSchemes.contains(scheme); }
    public int countDataSchemes() { return mDataSchemes != null ? mDataSchemes.size() : 0; }
    public String getDataScheme(int index) { return mDataSchemes.get(index); }
    public void setPriority(int priority) { mPriority = priority; }
    public int getPriority() { return mPriority; }
    /** AOSP IntentFilter.matchCategories: null when every category is declared by the filter. */
    public String matchCategories(java.util.Set<String> categories) {
        if (categories == null) return null;
        for (String category : categories) {
            if (mCategories == null || !mCategories.contains(category)) return category;
        }
        return null;
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeStringArray(mActions.toArray(new String[0]));
        if (mCategories != null) {
            dest.writeInt(1);
            dest.writeStringList(mCategories);
        } else {
            dest.writeInt(0);
        }
        if (mDataSchemes != null) {
            dest.writeInt(1);
            dest.writeStringList(mDataSchemes);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(0); // staticDataTypes
        dest.writeInt(0); // dataTypes
        dest.writeInt(0); // mimeGroups
        dest.writeInt(0); // schemeSpecificParts
        dest.writeInt(0); // authorities
        dest.writeInt(0); // paths
        dest.writeInt(mPriority);
    }
    public IntentFilter(android.os.Parcel source) {
        String[] actions = source.createStringArray();
        if (actions != null) for (String action : actions) addAction(action);
        if (source.readInt() != 0) {
            mCategories = new ArrayList<>();
            source.readStringList(mCategories);
        }
        if (source.readInt() != 0) {
            mDataSchemes = new ArrayList<>();
            source.readStringList(mDataSchemes);
        }
        for (int i = 0; i < 6; i++) source.readInt(); // absent lists
        mPriority = source.readInt();
    }
    public static final android.os.Parcelable.Creator<IntentFilter> CREATOR =
            new android.os.Parcelable.Creator<IntentFilter>() {
        public IntentFilter createFromParcel(android.os.Parcel source) { return new IntentFilter(source); }
        public IntentFilter[] newArray(int size) { return new IntentFilter[size]; }
    };
}
