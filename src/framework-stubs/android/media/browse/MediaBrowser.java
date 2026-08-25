/* artzig stub: only MediaItem is used (via MediaBrowserServiceCompat). */
package android.media.browse;

import android.media.MediaDescription;
import android.os.Parcel;
import android.os.Parcelable;

public final class MediaBrowser {
    public static class MediaItem implements Parcelable {
        public static final int FLAG_BROWSABLE = 1;
        public static final int FLAG_PLAYABLE = 2;
        private final MediaDescription mDescription;
        private final int mFlags;
        public MediaItem(MediaDescription description, int flags) {
            mDescription = description;
            mFlags = flags;
        }
        public MediaDescription getDescription() { return mDescription; }
        public int getFlags() { return mFlags; }
        public boolean isBrowsable() { return (mFlags & FLAG_BROWSABLE) != 0; }
        public boolean isPlayable() { return (mFlags & FLAG_PLAYABLE) != 0; }
        public String getMediaId() { return mDescription != null ? mDescription.getMediaId() : null; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public static final Parcelable.Creator<MediaItem> CREATOR = new Parcelable.Creator<MediaItem>() {
            @Override public MediaItem createFromParcel(Parcel in) { return new MediaItem(null, 0); }
            @Override public MediaItem[] newArray(int size) { return new MediaItem[size]; }
        };
    }
}
