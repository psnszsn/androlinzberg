/* artzig stub. */
package android.media;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public final class MediaMetadata implements Parcelable {
    public static final String METADATA_KEY_TITLE = "android.media.metadata.TITLE";
    public static final String METADATA_KEY_ARTIST = "android.media.metadata.ARTIST";
    public static final String METADATA_KEY_ALBUM = "android.media.metadata.ALBUM";
    public static final String METADATA_KEY_DURATION = "android.media.metadata.DURATION";
    public static final String METADATA_KEY_ART = "android.media.metadata.ART";
    public static final String METADATA_KEY_ALBUM_ART = "android.media.metadata.ALBUM_ART";
    public static final String METADATA_KEY_DISPLAY_TITLE = "android.media.metadata.DISPLAY_TITLE";
    public static final String METADATA_KEY_DISPLAY_SUBTITLE = "android.media.metadata.DISPLAY_SUBTITLE";
    public static final String METADATA_KEY_DISPLAY_DESCRIPTION = "android.media.metadata.DISPLAY_DESCRIPTION";
    public static final String METADATA_KEY_DISPLAY_ICON = "android.media.metadata.DISPLAY_ICON";
    public static final String METADATA_KEY_DISPLAY_ICON_URI = "android.media.metadata.DISPLAY_ICON_URI";
    public static final String METADATA_KEY_MEDIA_ID = "android.media.metadata.MEDIA_ID";
    public static final String METADATA_KEY_MEDIA_URI = "android.media.metadata.MEDIA_URI";

    private final Bundle mBundle = new Bundle();

    private MediaMetadata() {}
    public boolean containsKey(String key) { return mBundle.containsKey(key); }
    public CharSequence getText(String key) { return mBundle.getCharSequence(key); }
    public String getString(String key) {
        CharSequence cs = mBundle.getCharSequence(key);
        return cs != null ? cs.toString() : mBundle.getString(key);
    }
    public long getLong(String key) { return mBundle.getLong(key); }
    public Bitmap getBitmap(String key) { return (Bitmap) mBundle.getParcelable(key); }
    public android.media.Rating getRating(String key) { return null; }
    public MediaDescription getDescription() { return new MediaDescription.Builder().build(); }
    public int size() { return mBundle.size(); }
    public java.util.Set<String> keySet() { return mBundle.keySet(); }

    public static final class Builder {
        private final MediaMetadata m = new MediaMetadata();
        public Builder() {}
        public Builder(MediaMetadata source) { m.mBundle.putAll(source.mBundle); }
        public Builder putText(String key, CharSequence value) { m.mBundle.putCharSequence(key, value); return this; }
        public Builder putString(String key, String value) { m.mBundle.putString(key, value); return this; }
        public Builder putLong(String key, long value) { m.mBundle.putLong(key, value); return this; }
        public Builder putBitmap(String key, Bitmap value) { m.mBundle.putParcelable(key, value); return this; }
        public Builder putRating(String key, android.media.Rating value) { return this; }
        public MediaMetadata build() { return m; }
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<MediaMetadata> CREATOR = new Parcelable.Creator<MediaMetadata>() {
        @Override public MediaMetadata createFromParcel(Parcel in) { return new MediaMetadata(); }
        @Override public MediaMetadata[] newArray(int size) { return new MediaMetadata[size]; }
    };
}
