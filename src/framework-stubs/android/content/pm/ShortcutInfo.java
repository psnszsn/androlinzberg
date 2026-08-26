/* artzig stub: value object for app-maintained launcher shortcuts. */
package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ShortcutInfo implements Parcelable {
    public static final String SHORTCUT_CATEGORY_CONVERSATION =
            "android.shortcut.conversation";
    public static final int SURFACE_LAUNCHER = 1;

    private String mId;
    private String mPackage;
    private LocusId mLocusId;
    private ComponentName mActivity;
    private CharSequence mShortLabel;
    private CharSequence mLongLabel;
    private CharSequence mDisabledMessage;
    private Set<String> mCategories = Collections.emptySet();
    private Intent[] mIntents = new Intent[0];
    private int mRank;
    private PersistableBundle mExtras;
    private int mExcludedSurfaces;

    private ShortcutInfo() {}

    public String getId() { return mId; }
    public LocusId getLocusId() { return mLocusId; }
    public String getPackage() { return mPackage; }
    public ComponentName getActivity() { return mActivity; }
    public CharSequence getShortLabel() { return mShortLabel; }
    public CharSequence getLongLabel() { return mLongLabel; }
    public CharSequence getDisabledMessage() { return mDisabledMessage; }
    public Set<String> getCategories() { return mCategories; }
    public Intent getIntent() {
        return mIntents.length == 0 ? null : mIntents[mIntents.length - 1];
    }
    public Intent[] getIntents() { return mIntents.clone(); }
    public int getRank() { return mRank; }
    public PersistableBundle getExtras() { return mExtras; }
    public boolean isDynamic() { return true; }
    public boolean isPinned() { return false; }
    public boolean isEnabled() { return true; }
    public boolean isExcludedFromSurfaces(int surface) {
        return (mExcludedSurfaces & surface) != 0;
    }
    public int getExcludedFromSurfaces() { return mExcludedSurfaces; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) { dest.writeString(mId); }

    public static final Parcelable.Creator<ShortcutInfo> CREATOR =
            new Parcelable.Creator<ShortcutInfo>() {
        public ShortcutInfo createFromParcel(Parcel in) {
            ShortcutInfo info = new ShortcutInfo();
            info.mId = in.readString();
            return info;
        }
        public ShortcutInfo[] newArray(int size) { return new ShortcutInfo[size]; }
    };

    public static class Builder {
        private final ShortcutInfo mInfo = new ShortcutInfo();

        public Builder(Context context, String id) {
            mInfo.mPackage = context.getPackageName();
            mInfo.mId = id;
        }

        public Builder setLocusId(LocusId locusId) { mInfo.mLocusId = locusId; return this; }
        public Builder setActivity(ComponentName activity) { mInfo.mActivity = activity; return this; }
        public Builder setIcon(android.graphics.drawable.Icon icon) { return this; }
        public Builder setStartingTheme(int themeResId) { return this; }
        public Builder setShortLabel(CharSequence label) { mInfo.mShortLabel = label; return this; }
        public Builder setLongLabel(CharSequence label) { mInfo.mLongLabel = label; return this; }
        public Builder setDisabledMessage(CharSequence message) {
            mInfo.mDisabledMessage = message;
            return this;
        }
        public Builder setCategories(Set<String> categories) {
            mInfo.mCategories = Collections.unmodifiableSet(new HashSet<>(categories));
            return this;
        }
        public Builder setIntent(Intent intent) {
            mInfo.mIntents = new Intent[] { intent };
            return this;
        }
        public Builder setIntents(Intent[] intents) { mInfo.mIntents = intents.clone(); return this; }
        public Builder setPerson(android.app.Person person) { return this; }
        public Builder setPersons(android.app.Person[] persons) { return this; }
        public Builder setLongLived(boolean longLived) { return this; }
        public Builder setRank(int rank) { mInfo.mRank = rank; return this; }
        public Builder setExtras(PersistableBundle extras) { mInfo.mExtras = extras; return this; }
        public Builder setExcludedFromSurfaces(int surfaces) {
            mInfo.mExcludedSurfaces = surfaces;
            return this;
        }
        public ShortcutInfo build() { return mInfo; }
    }
}
