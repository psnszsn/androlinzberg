/* artzig stub. */
package android.text.style;
import android.os.Parcel;
import android.text.ParcelableSpan;
public class EasyEditSpan implements ParcelableSpan {
    public static final String EXTRA_TEXT_CHANGED_TYPE = "android.text.style.EXTRA_TEXT_CHANGED_TYPE";
    public static final int TEXT_DELETED = 1, TEXT_MODIFIED = 2;
    public EasyEditSpan() {}
    public EasyEditSpan(Parcel source) {}
    public void setDeleteEnabled(boolean value) {}
    public boolean isDeleteEnabled() { return false; }
    public android.app.PendingIntent getPendingIntent() { return null; }
    public int getSpanTypeId() { return 22; }
    public int getSpanTypeIdInternal() { return 22; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) {}
    public void writeToParcelInternal(Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<EasyEditSpan> CREATOR =
            new android.os.Parcelable.Creator<EasyEditSpan>() {
        public EasyEditSpan createFromParcel(Parcel in) { return new EasyEditSpan(in); }
        public EasyEditSpan[] newArray(int size) { return new EasyEditSpan[size]; }
    };
}
