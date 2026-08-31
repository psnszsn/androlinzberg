/* artzig shim: android.content.Intent — hosted in-process intent routing.
 * Typed-extra methods and createChooser follow AOSP Intent. The AOSP class cannot be compiled
 * directly because its system-service and platform-internal dependency closure is unavailable in
 * the hosted runtime. */
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
    public static final String ACTION_CHOOSER = "android.intent.action.CHOOSER";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM";
    public static final String EXTRA_SUBJECT = "android.intent.extra.SUBJECT";
    public static final String EXTRA_TITLE = "android.intent.extra.TITLE";
    public static final String EXTRA_INTENT = "android.intent.extra.INTENT";
    public static final String EXTRA_CHOOSER_RESULT_INTENT_SENDER =
            "android.intent.extra.CHOOSER_RESULT_INTENT_SENDER";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_SENDTO = "android.intent.action.SENDTO";
    public static final String ACTION_DEFAULT = "android.intent.action.VIEW";
    public static final String CATEGORY_DEFAULT = "android.intent.category.DEFAULT";
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
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
    public android.content.ClipData getClipData() { return mClipData; }
    public void setClipData(android.content.ClipData clip) { mClipData = clip; }
    public String toUri(int flags) { return String.valueOf(mData); }
    public static Intent parseUri(String uri, int flags) throws java.net.URISyntaxException { return new Intent(); }
    /**
     * Follows AOSP Intent.createChooser: the chooser is a distinct intent which owns the target
     * intent and optional title. ResolverActivity itself is supplied by the desktop host adapter.
     */
    public static Intent createChooser(Intent target, CharSequence title) {
        return createChooser(target, title, null);
    }
    public static Intent createChooser(Intent target, CharSequence title,
            android.content.IntentSender sender) {
        Intent chooser = new Intent(ACTION_CHOOSER);
        chooser.putExtra(EXTRA_INTENT, target);
        if (title != null) chooser.putExtra(EXTRA_TITLE, title);
        if (sender != null) chooser.putExtra(EXTRA_CHOOSER_RESULT_INTENT_SENDER, sender);

        int permissionFlags = target.getFlags() & (FLAG_GRANT_READ_URI_PERMISSION
                | FLAG_GRANT_WRITE_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (permissionFlags != 0) {
            ClipData targetClipData = target.getClipData();
            if (targetClipData == null && target.getData() != null) {
                String[] mimeTypes = target.getType() != null
                        ? new String[] { target.getType() } : new String[0];
                targetClipData = new ClipData(null, mimeTypes,
                        new ClipData.Item(target.getData()));
            }
            if (targetClipData != null) {
                chooser.setClipData(targetClipData);
                chooser.addFlags(permissionFlags);
            }
        }
        return chooser;
    }
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
    private ClipData mClipData;
    private Bundle mExtras;
    private int mFlags;
    public Intent() {}
    public Intent(String action) { mAction = action; }
    public Intent(Intent o) {
        mComponent = o.mComponent;
        mAction = o.mAction; mData = o.mData; mType = o.mType;
        mIdentifier = o.mIdentifier; mPackage = o.mPackage; mFlags = o.mFlags;
        mCategories = o.mCategories != null ? new HashSet<>(o.mCategories) : null;
        mClipData = o.mClipData;
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
    public Intent putExtra(String name, byte value) { extras().putByte(name, value); return this; }
    public Intent putExtra(String name, short value) { extras().putShort(name, value); return this; }
    public Intent putExtra(String name, char value) { extras().putChar(name, value); return this; }
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
    public byte getByteExtra(String name, byte def) { return mExtras != null ? mExtras.getByte(name, def) : def; }
    public short getShortExtra(String name, short def) { return mExtras != null ? mExtras.getShort(name, def) : def; }
    public char getCharExtra(String name, char def) { return mExtras != null ? mExtras.getChar(name, def) : def; }
    public int getIntExtra(String name, int def) { return mExtras != null ? mExtras.getInt(name, def) : def; }
    public long getLongExtra(String name, long def) { return mExtras != null ? mExtras.getLong(name, def) : def; }
    public float getFloatExtra(String name, float def) { return mExtras != null ? mExtras.getFloat(name, def) : def; }
    public double getDoubleExtra(String name, double def) { return mExtras != null ? mExtras.getDouble(name, def) : def; }
    public <T extends android.os.Parcelable> T getParcelableExtra(String name) {
        return mExtras != null ? mExtras.<T>getParcelable(name) : null;
    }
    public <T> T getParcelableExtra(String name, Class<T> clazz) {
        return mExtras != null ? mExtras.getParcelable(name, clazz) : null;
    }
    public <T extends android.os.Parcelable> java.util.ArrayList<T> getParcelableArrayListExtra(
            String name) {
        return mExtras != null ? mExtras.<T>getParcelableArrayList(name) : null;
    }
    public <T> java.util.ArrayList<T> getParcelableArrayListExtra(
            String name, Class<? extends T> clazz) {
        return mExtras != null ? mExtras.getParcelableArrayList(name, clazz) : null;
    }
    public android.os.Parcelable[] getParcelableArrayExtra(String name) {
        return mExtras != null ? mExtras.getParcelableArray(name) : null;
    }
    public <T> T[] getParcelableArrayExtra(String name, Class<T> clazz) {
        return mExtras != null ? mExtras.getParcelableArray(name, clazz) : null;
    }
    public java.util.ArrayList<Integer> getIntegerArrayListExtra(String name) {
        return mExtras != null ? mExtras.getIntegerArrayList(name) : null;
    }
    public java.util.ArrayList<String> getStringArrayListExtra(String name) {
        return mExtras != null ? mExtras.getStringArrayList(name) : null;
    }
    public java.util.ArrayList<CharSequence> getCharSequenceArrayListExtra(String name) {
        return mExtras != null ? mExtras.getCharSequenceArrayList(name) : null;
    }
    public boolean[] getBooleanArrayExtra(String name) { return mExtras != null ? mExtras.getBooleanArray(name) : null; }
    public byte[] getByteArrayExtra(String name) { return mExtras != null ? mExtras.getByteArray(name) : null; }
    public short[] getShortArrayExtra(String name) { return mExtras != null ? mExtras.getShortArray(name) : null; }
    public char[] getCharArrayExtra(String name) { return mExtras != null ? mExtras.getCharArray(name) : null; }
    public int[] getIntArrayExtra(String name) { return mExtras != null ? mExtras.getIntArray(name) : null; }
    public long[] getLongArrayExtra(String name) { return mExtras != null ? mExtras.getLongArray(name) : null; }
    public float[] getFloatArrayExtra(String name) { return mExtras != null ? mExtras.getFloatArray(name) : null; }
    public double[] getDoubleArrayExtra(String name) { return mExtras != null ? mExtras.getDoubleArray(name) : null; }
    public String[] getStringArrayExtra(String name) { return mExtras != null ? mExtras.getStringArray(name) : null; }
    public CharSequence[] getCharSequenceArrayExtra(String name) {
        return mExtras != null ? mExtras.getCharSequenceArray(name) : null;
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
    public ComponentName resolveActivity(android.content.pm.PackageManager packageManager) {
        if (mComponent != null) return mComponent;
        android.content.pm.ResolveInfo resolved = packageManager.resolveActivity(
                this, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                ? new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                : null;
    }
    public Intent putExtra(String name, android.os.Parcelable value) { extras().putParcelable(name, value); return this; }
    public Intent putExtra(String name, java.io.Serializable value) { extras().putSerializable(name, value); return this; }
    public Intent putExtra(String name, long value) { extras().putLong(name, value); return this; }
    public Intent putExtra(String name, float value) { extras().putFloat(name, value); return this; }
    public Intent putExtra(String name, double value) { extras().putDouble(name, value); return this; }
    public Intent putExtra(String name, CharSequence value) { extras().putCharSequence(name, value); return this; }
    public Intent putExtra(String name, String[] value) { extras().putStringArray(name, value); return this; }
    public Intent putExtra(String name, short[] value) { extras().putShortArray(name, value); return this; }
    public Intent putExtra(String name, char[] value) { extras().putCharArray(name, value); return this; }
    public Intent putExtra(String name, int[] value) { extras().putIntArray(name, value); return this; }
    public Intent putExtra(String name, long[] value) { extras().putLongArray(name, value); return this; }
    public Intent putExtra(String name, float[] value) { extras().putFloatArray(name, value); return this; }
    public Intent putExtra(String name, double[] value) { extras().putDoubleArray(name, value); return this; }
    public Intent putExtra(String name, byte[] value) { extras().putByteArray(name, value); return this; }
    public Intent putExtra(String name, boolean[] value) { extras().putBooleanArray(name, value); return this; }
    public Intent putExtra(String name, CharSequence[] value) { extras().putCharSequenceArray(name, value); return this; }
    public Intent putExtra(String name, android.os.Parcelable[] value) { extras().putParcelableArray(name, value); return this; }
    public Intent putExtra(String name, android.os.Bundle value) { extras().putBundle(name, value); return this; }
    public Intent putParcelableArrayListExtra(String name,
            java.util.ArrayList<? extends android.os.Parcelable> value) {
        extras().putParcelableArrayList(name, value);
        return this;
    }
    public Intent putIntegerArrayListExtra(String name, java.util.ArrayList<Integer> value) {
        extras().putIntegerArrayList(name, value);
        return this;
    }
    public Intent putStringArrayListExtra(String name, java.util.ArrayList<String> value) {
        extras().putStringArrayList(name, value);
        return this;
    }
    public Intent putCharSequenceArrayListExtra(String name,
            java.util.ArrayList<CharSequence> value) {
        extras().putCharSequenceArrayList(name, value);
        return this;
    }
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
    @Override public String toString() {
        StringBuilder out = new StringBuilder("Intent{");
        if (mAction != null) out.append(" act=").append(mAction);
        if (mData != null) out.append(" dat=").append(mData);
        if (mType != null) out.append(" typ=").append(mType);
        if (mPackage != null) out.append(" pkg=").append(mPackage);
        if (mComponent != null) out.append(" cmp=").append(mComponent.flattenToShortString());
        if (mFlags != 0) out.append(" flg=0x").append(Integer.toHexString(mFlags));
        if (mExtras != null) out.append(" extras");
        return out.append(" }").toString();
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
    /** AOSP Intent.writeToParcel field order; absent shim fields write their null markers. */
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString8(mAction);
        Uri.writeToParcel(out, mData);
        out.writeString8(mType);
        out.writeString8(mIdentifier);
        out.writeInt(mFlags);
        out.writeInt(0); // extendedFlags
        out.writeString8(mPackage);
        ComponentName.writeToParcel(mComponent, out);
        out.writeInt(0); // sourceBounds
        if (mCategories != null) {
            out.writeInt(mCategories.size());
            for (String category : mCategories) out.writeString8(category);
        } else {
            out.writeInt(0);
        }
        out.writeInt(0); // selector
        if (mClipData != null) {
            out.writeInt(1);
            mClipData.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeInt(-2); // contentUserHint = UserHandle.USER_CURRENT
        out.writeBundle(mExtras);
        out.writeInt(0); // originalIntent
    }
    public void readFromParcel(Parcel in) {
        mAction = in.readString8();
        String data = in.readString8();
        mData = data != null ? Uri.parse(data) : null;
        mType = in.readString8();
        mIdentifier = in.readString8();
        mFlags = in.readInt();
        in.readInt(); // extendedFlags
        mPackage = in.readString8();
        mComponent = ComponentName.readFromParcel(in);
        if (in.readInt() != 0) android.graphics.Rect.CREATOR.createFromParcel(in); // sourceBounds
        int categories = in.readInt();
        if (categories > 0) {
            mCategories = new HashSet<>();
            for (int i = 0; i < categories; i++) mCategories.add(in.readString8());
        } else {
            mCategories = null;
        }
        if (in.readInt() != 0) new Intent(in); // selector (dropped: shim has no field)
        if (in.readInt() != 0) mClipData = ClipData.CREATOR.createFromParcel(in);
        in.readInt(); // contentUserHint
        mExtras = in.readBundle();
        if (in.readInt() != 0) new Intent(in); // originalIntent (dropped)
    }
    protected Intent(Parcel in) { readFromParcel(in); }
    public void setExtrasClassLoader(ClassLoader loader) {
        if (mExtras != null) mExtras.setClassLoader(loader);
    }
    public static final Creator<Intent> CREATOR = new Creator<Intent>() {
        public Intent createFromParcel(Parcel in) { return new Intent(in); }
        public Intent[] newArray(int size) { return new Intent[size]; }
    };
    public String getScheme() {
        android.net.Uri data = getData();
        return data != null ? data.getScheme() : null;
    }
    /** AOSP: content: data resolves through the provider; else the set type. */
    public String resolveType(android.content.ContentResolver resolver) {
        if (getType() != null) return getType();
        android.net.Uri data = getData();
        if (data != null && "content".equals(data.getScheme()) && resolver != null) {
            return resolver.getType(data);
        }
        return null;
    }
    public String resolveType(android.content.Context context) {
        return resolveType(context.getContentResolver());
    }
    public String resolveTypeIfNeeded(android.content.ContentResolver resolver) {
        if (getComponent() != null) return getType();
        return resolveType(resolver);
    }
}
