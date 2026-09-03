/* artzig stub of AOSP android.content.pm.ShortcutInfo: the real class serializes itself
 * into AppSearch documents (android.app.appsearch, an unvendored module tree). Value
 * shape of the AOSP class: identity, labels, intents, rank, extras, icon, the flag
 * word the shortcut service maintains (dynamic/pinned/manifest/disabled/...), the
 * disabled reason, and the field-merge the service uses for updateShortcuts. */
package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ShortcutInfo implements Parcelable {
    public static final String SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation";
    public static final int SURFACE_LAUNCHER = 1;

    public static final int FLAG_DYNAMIC = 1 << 0;
    public static final int FLAG_PINNED = 1 << 1;
    public static final int FLAG_HAS_ICON_RES = 1 << 2;
    public static final int FLAG_HAS_ICON_FILE = 1 << 3;
    public static final int FLAG_KEY_FIELDS_ONLY = 1 << 4;
    public static final int FLAG_MANIFEST = 1 << 5;
    public static final int FLAG_DISABLED = 1 << 6;
    public static final int FLAG_STRINGS_RESOLVED = 1 << 7;
    public static final int FLAG_IMMUTABLE = 1 << 8;
    public static final int FLAG_ADAPTIVE_BITMAP = 1 << 9;
    public static final int FLAG_RETURNED_BY_SERVICE = 1 << 10;
    public static final int FLAG_ICON_FILE_PENDING_SAVE = 1 << 11;
    public static final int FLAG_SHADOW = 1 << 12;
    public static final int FLAG_LONG_LIVED = 1 << 13;
    public static final int FLAG_CACHED_NOTIFICATIONS = 1 << 14;
    public static final int FLAG_HAS_ICON_URI = 1 << 15;
    public static final int FLAG_CACHED_PEOPLE_TILE = 1 << 29;
    public static final int FLAG_CACHED_BUBBLES = 1 << 30;
    public static final int FLAG_CACHED_ALL = FLAG_CACHED_NOTIFICATIONS | FLAG_CACHED_PEOPLE_TILE | FLAG_CACHED_BUBBLES;

    public static final int DISABLED_REASON_NOT_DISABLED = 0;
    public static final int DISABLED_REASON_BY_APP = 1;
    public static final int DISABLED_REASON_APP_CHANGED = 2;
    public static final int DISABLED_REASON_UNKNOWN = 3;
    public static final int DISABLED_REASON_RESTORE_ISSUE_START = 100;
    public static final int DISABLED_REASON_VERSION_LOWER = 100;
    public static final int DISABLED_REASON_BACKUP_NOT_SUPPORTED = 101;
    public static final int DISABLED_REASON_SIGNATURE_MISMATCH = 102;
    public static final int DISABLED_REASON_OTHER_RESTORE_ISSUE = 103;

    public static final int CLONE_REMOVE_ICON = 1 << 0;
    public static final int CLONE_REMOVE_INTENT = 1 << 1;
    public static final int CLONE_REMOVE_NON_KEY_INFO = 1 << 2;
    public static final int CLONE_REMOVE_RES_NAMES = 1 << 3;
    public static final int CLONE_REMOVE_PERSON = 1 << 4;
    public static final int CLONE_REMOVE_FOR_CREATOR = CLONE_REMOVE_ICON | CLONE_REMOVE_RES_NAMES;
    public static final int CLONE_REMOVE_FOR_LAUNCHER = CLONE_REMOVE_ICON | CLONE_REMOVE_INTENT;
    public static final int CLONE_REMOVE_FOR_LAUNCHER_APPROVAL = CLONE_REMOVE_INTENT;
    public static final int CLONE_REMOVE_FOR_APP_PREDICTION = CLONE_REMOVE_ICON;

    private String mId;
    private String mPackage;
    private int mUserId;
    private LocusId mLocusId;
    private ComponentName mActivity;
    private Icon mIcon;
    private int mIconResId;
    private CharSequence mShortLabel;
    private CharSequence mLongLabel;
    private CharSequence mDisabledMessage;
    private int mDisabledReason;
    private Set<String> mCategories = Collections.emptySet();
    private Intent[] mIntents = new Intent[0];
    private int mRank;
    private PersistableBundle mExtras;
    private int mFlags;
    private int mExcludedSurfaces;

    private ShortcutInfo() {}

    public String getId() { return mId; }
    public LocusId getLocusId() { return mLocusId; }
    public String getPackage() { return mPackage; }
    public int getUserId() { return mUserId; }
    public UserHandle getUserHandle() { return UserHandle.of(mUserId); }
    public ComponentName getActivity() { return mActivity; }
    public Icon getIcon() { return mIcon; }
    public int getIconResourceId() { return mIconResId; }
    public CharSequence getShortLabel() { return mShortLabel; }
    public CharSequence getLongLabel() { return mLongLabel; }
    public CharSequence getDisabledMessage() { return mDisabledMessage; }
    public int getDisabledReason() { return mDisabledReason; }
    public Set<String> getCategories() { return mCategories; }
    public Intent getIntent() { return mIntents.length == 0 ? null : mIntents[mIntents.length - 1]; }
    public Intent[] getIntents() { return mIntents.clone(); }
    public int getRank() { return mRank; }
    public PersistableBundle getExtras() { return mExtras; }
    public int getFlags() { return mFlags; }
    public boolean hasFlags(int flags) { return (mFlags & flags) == flags; }
    public boolean isDynamic() { return hasFlags(FLAG_DYNAMIC); }
    public boolean isPinned() { return hasFlags(FLAG_PINNED); }
    public boolean isDeclaredInManifest() { return hasFlags(FLAG_MANIFEST); }
    public boolean isCached() { return (mFlags & FLAG_CACHED_ALL) != 0; }
    public boolean isLongLived() { return hasFlags(FLAG_LONG_LIVED); }
    public boolean isImmutable() { return hasFlags(FLAG_IMMUTABLE); }
    public boolean isEnabled() { return !hasFlags(FLAG_DISABLED); }
    public boolean isAlive() { return isPinned() || isDynamic() || isDeclaredInManifest() || isCached(); }
    public boolean hasKeyFieldsOnly() { return hasFlags(FLAG_KEY_FIELDS_ONLY); }
    public boolean hasIconResource() { return hasFlags(FLAG_HAS_ICON_RES); }
    public boolean hasIconFile() { return hasFlags(FLAG_HAS_ICON_FILE); }
    public boolean hasIconUri() { return hasFlags(FLAG_HAS_ICON_URI); }
    public boolean hasAdaptiveBitmap() { return hasFlags(FLAG_ADAPTIVE_BITMAP); }
    public boolean isExcludedFromSurfaces(int surface) { return (mExcludedSurfaces & surface) != 0; }
    public int getExcludedFromSurfaces() { return mExcludedSurfaces; }
    public boolean isVisibleToPublisher() { return !isDisabledForRestoreIssue(mDisabledReason); }

    public static boolean isDisabledForRestoreIssue(int disabledReason) { return disabledReason >= DISABLED_REASON_RESTORE_ISSUE_START; }
    public static String getDisabledReasonForRestoreIssue(Context context, int disabledReason) {
        switch (disabledReason) {
            case DISABLED_REASON_VERSION_LOWER: return "Shortcut disabled: app version lower than backup";
            case DISABLED_REASON_BACKUP_NOT_SUPPORTED: return "Shortcut disabled: app does not support backup";
            case DISABLED_REASON_SIGNATURE_MISMATCH: return "Shortcut disabled: app signature mismatch";
            case DISABLED_REASON_OTHER_RESTORE_ISSUE: return "Shortcut disabled: restore issue";
            default: return null;
        }
    }

    // Service-side mutators (AOSP @hide).
    public void addFlags(int flags) { mFlags |= flags; }
    public void clearFlags(int flags) { mFlags &= ~flags; }
    public void replaceFlags(int flags) { mFlags = flags; }
    public void setRank(int rank) { mRank = rank; }
    public void setDisabledReason(int reason) { mDisabledReason = reason; }
    public void setDisabledMessage(CharSequence message) { mDisabledMessage = message; }
    public void setIntents(Intent[] intents) { mIntents = intents == null ? new Intent[0] : intents.clone(); }
    public void setActivity(ComponentName activity) { mActivity = activity; }
    public void setUserIdAndPackage(int userId, String packageName) { mUserId = userId; mPackage = packageName; }

    /** AOSP copyNonNullFieldsFrom: the fields updateShortcuts may replace. */
    public void copyNonNullFieldsFrom(ShortcutInfo source) {
        if (source.mActivity != null) mActivity = source.mActivity;
        if (source.mIcon != null) { mIcon = source.mIcon; mIconResId = source.mIconResId; }
        if (source.mShortLabel != null) mShortLabel = source.mShortLabel;
        if (source.mLongLabel != null) mLongLabel = source.mLongLabel;
        if (source.mDisabledMessage != null) mDisabledMessage = source.mDisabledMessage;
        if (!source.mCategories.isEmpty()) mCategories = source.mCategories;
        if (source.mIntents.length > 0) mIntents = source.mIntents.clone();
        if (source.mRank != 0) mRank = source.mRank;
        if (source.mExtras != null) mExtras = source.mExtras;
        if (source.mLocusId != null) mLocusId = source.mLocusId;
        mExcludedSurfaces = source.mExcludedSurfaces;
    }

    public ShortcutInfo clone(int cloneFlags) {
        Parcel p = Parcel.obtain();
        writeToParcel(p, 0);
        p.setDataPosition(0);
        ShortcutInfo copy = CREATOR.createFromParcel(p);
        p.recycle();
        if ((cloneFlags & CLONE_REMOVE_ICON) != 0) copy.mIcon = null;
        if ((cloneFlags & CLONE_REMOVE_INTENT) != 0) copy.mIntents = new Intent[0];
        if ((cloneFlags & CLONE_REMOVE_NON_KEY_INFO) != 0) {
            copy.mShortLabel = null; copy.mLongLabel = null; copy.mDisabledMessage = null;
            copy.mCategories = Collections.emptySet(); copy.mExtras = null; copy.mIcon = null;
            copy.mIntents = new Intent[0]; copy.addFlags(FLAG_KEY_FIELDS_ONLY);
        }
        return copy;
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mPackage);
        dest.writeInt(mUserId);
        dest.writeParcelable(mLocusId, flags);
        dest.writeParcelable(mActivity, flags);
        dest.writeParcelable(mIcon, flags);
        dest.writeInt(mIconResId);
        dest.writeCharSequence(mShortLabel);
        dest.writeCharSequence(mLongLabel);
        dest.writeCharSequence(mDisabledMessage);
        dest.writeInt(mDisabledReason);
        dest.writeStringArray(mCategories.toArray(new String[0]));
        dest.writeTypedArray(mIntents, flags);
        dest.writeInt(mRank);
        dest.writePersistableBundle(mExtras);
        dest.writeInt(mFlags);
        dest.writeInt(mExcludedSurfaces);
    }

    public static final Parcelable.Creator<ShortcutInfo> CREATOR = new Parcelable.Creator<ShortcutInfo>() {
        public ShortcutInfo createFromParcel(Parcel in) {
            ShortcutInfo info = new ShortcutInfo();
            ClassLoader cl = ShortcutInfo.class.getClassLoader();
            info.mId = in.readString();
            info.mPackage = in.readString();
            info.mUserId = in.readInt();
            info.mLocusId = in.readParcelable(cl, LocusId.class);
            info.mActivity = in.readParcelable(cl, ComponentName.class);
            info.mIcon = in.readParcelable(cl, Icon.class);
            info.mIconResId = in.readInt();
            info.mShortLabel = in.readCharSequence();
            info.mLongLabel = in.readCharSequence();
            info.mDisabledMessage = in.readCharSequence();
            info.mDisabledReason = in.readInt();
            String[] categories = in.createStringArray();
            info.mCategories = categories == null || categories.length == 0
                    ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(categories)));
            Intent[] intents = in.createTypedArray(Intent.CREATOR);
            info.mIntents = intents == null ? new Intent[0] : intents;
            info.mRank = in.readInt();
            info.mExtras = in.readPersistableBundle(cl);
            info.mFlags = in.readInt();
            info.mExcludedSurfaces = in.readInt();
            return info;
        }
        public ShortcutInfo[] newArray(int size) { return new ShortcutInfo[size]; }
    };

    @Override public String toString() {
        return "ShortcutInfo{id=" + mId + ", package=" + mPackage + ", flags=0x" + Integer.toHexString(mFlags) + ", rank=" + mRank + "}";
    }

    public static class Builder {
        private final ShortcutInfo mInfo = new ShortcutInfo();
        public Builder(Context context) {
            mInfo.mPackage = context.getPackageName();
            mInfo.mUserId = context.getUserId();
        }
        public Builder(Context context, String id) {
            this(context);
            mInfo.mId = id;
        }
        public Builder setId(String id) { mInfo.mId = id; return this; }
        public Builder setLocusId(LocusId locusId) { mInfo.mLocusId = locusId; return this; }
        public Builder setActivity(ComponentName activity) { mInfo.mActivity = activity; return this; }
        public Builder setIcon(Icon icon) {
            mInfo.mIcon = icon;
            mInfo.clearFlags(FLAG_HAS_ICON_RES | FLAG_HAS_ICON_FILE | FLAG_HAS_ICON_URI | FLAG_ADAPTIVE_BITMAP);
            if (icon != null) {
                switch (icon.getType()) {
                    case Icon.TYPE_RESOURCE: mInfo.addFlags(FLAG_HAS_ICON_RES); mInfo.mIconResId = icon.getResId(); break;
                    case Icon.TYPE_URI: case Icon.TYPE_URI_ADAPTIVE_BITMAP: mInfo.addFlags(FLAG_HAS_ICON_URI); break;
                    case Icon.TYPE_ADAPTIVE_BITMAP: mInfo.addFlags(FLAG_ADAPTIVE_BITMAP); break;
                    default: break;
                }
            }
            return this;
        }
        public Builder setStartingTheme(int themeResId) { return this; }
        public Builder setShortLabel(CharSequence label) { mInfo.mShortLabel = label; return this; }
        public Builder setLongLabel(CharSequence label) { mInfo.mLongLabel = label; return this; }
        public Builder setDisabledMessage(CharSequence message) { mInfo.mDisabledMessage = message; return this; }
        public Builder setCategories(Set<String> categories) {
            mInfo.mCategories = Collections.unmodifiableSet(new HashSet<>(categories));
            return this;
        }
        public Builder setIntent(Intent intent) { mInfo.mIntents = new Intent[] { intent }; return this; }
        public Builder setIntents(Intent[] intents) { mInfo.mIntents = intents.clone(); return this; }
        public Builder setPerson(android.app.Person person) { return this; }
        public Builder setPersons(android.app.Person[] persons) { return this; }
        public Builder setLongLived(boolean longLived) {
            if (longLived) mInfo.addFlags(FLAG_LONG_LIVED); else mInfo.clearFlags(FLAG_LONG_LIVED);
            return this;
        }
        public Builder setRank(int rank) { mInfo.mRank = rank; return this; }
        public Builder setExtras(PersistableBundle extras) { mInfo.mExtras = extras; return this; }
        public Builder setExcludedFromSurfaces(int surfaces) { mInfo.mExcludedSurfaces = surfaces; return this; }
        public ShortcutInfo build() { return mInfo; }
    }
}
