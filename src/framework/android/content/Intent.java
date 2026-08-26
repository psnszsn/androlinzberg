/* artzig shim: android.content.Intent — minimal (real intent routing is single-activity in-process). */
package android.content;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
public class Intent implements Parcelable, Cloneable {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    public static final String CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    public static final String ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
    public static final String EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT";
    public static final String EXTRA_PROCESS_TEXT_READONLY = "android.intent.extra.PROCESS_TEXT_READONLY";
    public static final String ACTION_TRANSLATE = "android.intent.action.TRANSLATE";
    public static final String ACTION_DEFINE = "android.intent.action.DEFINE";
    public static final String ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";
    public static final String ACTION_TIME_CHANGED = "android.intent.action.TIME_SET";
    public static final String ACTION_DATE_CHANGED = "android.intent.action.DATE_CHANGED";
    public static final String ACTION_CONFIGURATION_CHANGED = "android.intent.action.CONFIGURATION_CHANGED";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String CATEGORY_APP_BROWSER = "android.intent.category.APP_BROWSER";
    public static final String CATEGORY_APP_EMAIL = "android.intent.category.APP_EMAIL";
    public static final String CATEGORY_APP_CONTACTS = "android.intent.category.APP_CONTACTS";
    public static final String CATEGORY_APP_CALENDAR = "android.intent.category.APP_CALENDAR";
    public static final String CATEGORY_APP_MAPS = "android.intent.category.APP_MAPS";
    public static final String CATEGORY_APP_MUSIC = "android.intent.category.APP_MUSIC";
    public static final String CATEGORY_APP_CALCULATOR = "android.intent.category.APP_CALCULATOR";
    public static final String CATEGORY_APP_MESSAGING = "android.intent.category.APP_MESSAGING";
    public static final String CATEGORY_APP_GALLERY = "android.intent.category.APP_GALLERY";
    public static final String CATEGORY_APP_FILES = "android.intent.category.APP_FILES";
    public static final String CATEGORY_APP_FITNESS = "android.intent.category.APP_FITNESS";
    public static final String CATEGORY_APP_WEATHER = "android.intent.category.APP_WEATHER";
    public static final String CATEGORY_APP_MARKET = "android.intent.category.APP_MARKET";
    public static final String CATEGORY_ACCESSIBILITY_SHORTCUT_TARGET = "android.intent.category.ACCESSIBILITY_SHORTCUT_TARGET";
    public static final String EXTRA_TIMEZONE = "time-zone";
    public static final String ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND";
    public static final String ACTION_ALL_APPS = "android.intent.action.ALL_APPS";
    public static final String ACTION_SET_WALLPAPER = "android.intent.action.SET_WALLPAPER";
    public static final String ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH";
    public static final String ACTION_SEARCH_LONG_PRESS = "android.intent.action.SEARCH_LONG_PRESS";
    public static final String ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON";
    public static final String ACTION_ASSIST = "android.intent.action.ASSIST";
    public static final String EXTRA_KEY_EVENT = "android.intent.extra.KEY_EVENT";
    public static final String EXTRA_TIME = "android.intent.extra.TIME";
    public static final String EXTRA_ASSIST_INPUT_HINT_KEYBOARD = "android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD";
    public static final String EXTRA_ASSIST_INPUT_DEVICE_ID = "android.intent.extra.ASSIST_INPUT_DEVICE_ID";
    public static final String EXTRA_ASSIST_PACKAGE = "android.intent.extra.ASSIST_PACKAGE";
    public static final int FLAG_RECEIVER_FOREGROUND = 0x10000000;
    public Intent addCategory(String category) {
        if (mCategories == null) mCategories = new HashSet<>();
        mCategories.add(category);
        return this;
    }
    public void removeCategory(String category) {
        if (mCategories != null) {
            mCategories.remove(category);
            if (mCategories.isEmpty()) mCategories = null;
        }
    }
    public Set<String> getCategories() { return mCategories; }
    public void removeExtra(String name) { if (mExtras != null) mExtras.remove(name); }
    public static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00200000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_LAUNCH_ADJACENT = 0x00001000;
    public Intent setType(String type) { mType = type; return this; }
    public String getType() { return mType; }
    public static final int URI_INTENT_SCHEME = 1, URI_ANDROID_APP_SCHEME = 2, URI_ALLOW_UNSAFE = 4;
    public android.content.ClipData getClipData() { return null; }
    public void setClipData(android.content.ClipData clip) {}
    public String toUri(int flags) { return String.valueOf(mData); }
    public static Intent parseUri(String uri, int flags) throws java.net.URISyntaxException { return new Intent(); }
    public static Intent createChooser(Intent target, CharSequence title) { return target; }
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    public static final int FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002;
    public static final int FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040;
    public static final int FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080;
    private String mAction;
    private Uri mData;
    private String mType;
    private String mIdentifier;
    private String mPackage;
    private HashSet<String> mCategories;
    private Bundle mExtras;
    private int mFlags;
    public Intent() {}
    public Intent(String action) { mAction = action; }
    public Intent(Intent o) {
        mComponent = o.mComponent;
        mAction = o.mAction; mData = o.mData; mType = o.mType;
        mIdentifier = o.mIdentifier; mPackage = o.mPackage; mFlags = o.mFlags;
        mCategories = o.mCategories != null ? new HashSet<>(o.mCategories) : null;
        mExtras = o.mExtras != null ? new android.os.Bundle(o.mExtras) : null;
    }
    public Intent(String action, Uri uri) { mAction = action; mData = uri; }
    public Intent(Context packageContext, Class<?> cls) {
        mComponent = new ComponentName(packageContext, cls);
    }
    public String getAction() { return mAction; }
    public Intent setAction(String action) { mAction = action; return this; }
    public Uri getData() { return mData; }
    public Intent setData(Uri data) { mData = data; return this; }
    public Intent setDataAndType(Uri data, String type) { mData = data; mType = type; return this; }
    public Intent setFlags(int flags) { mFlags = flags; return this; }
    public int getFlags() { return mFlags; }
    public Intent addFlags(int flags) { mFlags |= flags; return this; }
    public Intent setPackage(String packageName) { mPackage = packageName; return this; }
    public String getPackage() { return mPackage; }
    public Intent setIdentifier(String identifier) { mIdentifier = identifier; return this; }
    public String getIdentifier() { return mIdentifier; }
    public Intent putExtra(String name, String value) { extras().putString(name, value); return this; }
    public Intent putExtra(String name, int value) { extras().putInt(name, value); return this; }
    public Intent putExtra(String name, boolean value) { extras().putBoolean(name, value); return this; }
    public Intent putExtras(Bundle src) { extras().putAll(src); return this; }
    public Bundle getExtras() { return mExtras; }
    public boolean hasExtra(String name) { return mExtras != null && mExtras.containsKey(name); }
    public String getStringExtra(String name) { return mExtras != null ? mExtras.getString(name) : null; }
    public CharSequence getCharSequenceExtra(String name) { return mExtras != null ? mExtras.getCharSequence(name) : null; }
    public <T extends java.io.Serializable> T getSerializableExtra(String name, Class<T> clazz) {
        return mExtras != null ? mExtras.getSerializable(name, clazz) : null;
    }
    public java.io.Serializable getSerializableExtra(String name) {
        return mExtras != null ? mExtras.getSerializable(name) : null;
    }
    public android.os.Bundle getBundleExtra(String name) { return mExtras != null ? mExtras.getBundle(name) : null; }
    public void fixUris(int contentUserHint) {}
    public void prepareToEnterProcess(boolean fromProtectedComponent, android.content.AttributionSource source) {}
    public static void maybeMarkAsMissingCreatorToken(Object intent) {}
    public Intent cloneForCreatorToken() { return this; }
    public void prepareToLeaveProcess(boolean leavingPackage, boolean toPackage) {}
    public void prepareToLeaveProcess(Context context) {}
    public void toShortString(StringBuilder b, boolean secure, boolean comp, boolean extras, boolean clip) {
        b.append("Intent{").append(mAction).append('}');
    }
    public boolean isImmutable() { return false; }
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId,
            boolean secure, boolean comp, boolean extras, boolean clip) {}
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    public boolean getBooleanExtra(String name, boolean def) { return mExtras != null ? mExtras.getBoolean(name, def) : def; }
    public int getIntExtra(String name, int def) { return mExtras != null ? mExtras.getInt(name, def) : def; }
    public <T extends android.os.Parcelable> T getParcelableExtra(String name) {
        return mExtras != null ? mExtras.<T>getParcelable(name) : null;
    }
    public <T> T getParcelableExtra(String name, Class<T> clazz) {
        return mExtras != null ? mExtras.getParcelable(name, clazz) : null;
    }
    public Intent setComponent(ComponentName component) { mComponent = component; return this; }
    public ComponentName getComponent() { return mComponent; }
    private ComponentName mComponent;
    public Intent setClass(Context packageContext, Class<?> cls) {
        mComponent = new ComponentName(packageContext, cls);
        return this;
    }
    public Intent setClassName(Context packageContext, String className) {
        mComponent = new ComponentName(packageContext.getPackageName(), className);
        return this;
    }
    public Intent setClassName(String packageName, String className) {
        mComponent = new ComponentName(packageName, className);
        return this;
    }
    public Intent putExtra(String name, android.os.Parcelable value) { extras().putParcelable(name, value); return this; }
    public Intent putExtra(String name, java.io.Serializable value) { extras().putSerializable(name, value); return this; }
    public Intent putExtra(String name, long value) { extras().putLong(name, value); return this; }
    public Intent putExtra(String name, float value) { extras().putFloat(name, value); return this; }
    public Intent putExtra(String name, double value) { extras().putDouble(name, value); return this; }
    public Intent putExtra(String name, CharSequence value) { extras().putCharSequence(name, value); return this; }
    public Intent putExtra(String name, String[] value) { extras().putStringArray(name, value); return this; }
    public Intent putExtra(String name, int[] value) { extras().putIntArray(name, value); return this; }
    public Intent putExtra(String name, long[] value) { extras().putLongArray(name, value); return this; }
    public Intent putExtra(String name, byte[] value) { extras().putByteArray(name, value); return this; }
    public Intent putExtra(String name, boolean[] value) { extras().putBooleanArray(name, value); return this; }
    public Intent putExtra(String name, android.os.Parcelable[] value) { extras().putParcelableArray(name, value); return this; }
    public Intent putExtra(String name, android.os.Bundle value) { extras().putBundle(name, value); return this; }
    private Bundle extras() { if (mExtras == null) mExtras = new Bundle(); return mExtras; }
    public boolean filterEquals(Intent other) {
        return other != null
                && Objects.equals(mAction, other.mAction)
                && Objects.equals(mData, other.mData)
                && Objects.equals(mType, other.mType)
                && Objects.equals(mIdentifier, other.mIdentifier)
                && Objects.equals(mPackage, other.mPackage)
                && Objects.equals(mComponent, other.mComponent)
                && Objects.equals(mCategories, other.mCategories);
    }
    public int filterHashCode() {
        int code = 0;
        if (mAction != null) code += mAction.hashCode();
        if (mData != null) code += mData.hashCode();
        if (mType != null) code += mType.hashCode();
        if (mIdentifier != null) code += mIdentifier.hashCode();
        if (mPackage != null) code += mPackage.hashCode();
        if (mComponent != null) code += mComponent.hashCode();
        if (mCategories != null) code += mCategories.hashCode();
        return code;
    }
    public static final class FilterComparison {
        private final Intent mIntent;
        public FilterComparison(Intent intent) { mIntent = intent; }
        public Intent getIntent() { return mIntent; }
        @Override public boolean equals(Object o) {
            return o instanceof FilterComparison
                    && mIntent.filterEquals(((FilterComparison) o).mIntent);
        }
        @Override public int hashCode() { return mIntent.filterHashCode(); }
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(mAction); }
    public static final Creator<Intent> CREATOR = new Creator<Intent>() {
        public Intent createFromParcel(Parcel in) { return new Intent(in.readString()); }
        public Intent[] newArray(int size) { return new Intent[size]; }
    };
}
