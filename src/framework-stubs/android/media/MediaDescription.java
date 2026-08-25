/* artzig stub. */
package android.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public class MediaDescription implements Parcelable {
    private String mMediaId;
    private CharSequence mTitle, mSubtitle, mDescription;
    private Bitmap mIcon;
    private Uri mIconUri, mMediaUri;
    private Bundle mExtras;

    private MediaDescription() {}
    public String getMediaId() { return mMediaId; }
    public CharSequence getTitle() { return mTitle; }
    public CharSequence getSubtitle() { return mSubtitle; }
    public CharSequence getDescription() { return mDescription; }
    public Bitmap getIconBitmap() { return mIcon; }
    public Uri getIconUri() { return mIconUri; }
    public Uri getMediaUri() { return mMediaUri; }
    public Bundle getExtras() { return mExtras; }

    public static class Builder {
        private final MediaDescription d = new MediaDescription();
        public Builder() {}
        public Builder setMediaId(String mediaId) { d.mMediaId = mediaId; return this; }
        public Builder setTitle(CharSequence title) { d.mTitle = title; return this; }
        public Builder setSubtitle(CharSequence subtitle) { d.mSubtitle = subtitle; return this; }
        public Builder setDescription(CharSequence description) { d.mDescription = description; return this; }
        public Builder setIconBitmap(Bitmap icon) { d.mIcon = icon; return this; }
        public Builder setIconUri(Uri iconUri) { d.mIconUri = iconUri; return this; }
        public Builder setMediaUri(Uri mediaUri) { d.mMediaUri = mediaUri; return this; }
        public Builder setExtras(Bundle extras) { d.mExtras = extras; return this; }
        public MediaDescription build() { return d; }
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<MediaDescription> CREATOR = new Parcelable.Creator<MediaDescription>() {
        @Override public MediaDescription createFromParcel(Parcel in) { return new MediaDescription(); }
        @Override public MediaDescription[] newArray(int size) { return new MediaDescription[size]; }
    };
}
