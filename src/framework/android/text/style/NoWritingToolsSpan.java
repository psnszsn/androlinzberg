/* artzig stub. */
package android.text.style;
import android.os.Parcel;
import android.text.ParcelableSpan;
public final class NoWritingToolsSpan implements ParcelableSpan {
    public NoWritingToolsSpan() {}
    public int getSpanTypeId() { return 31; }
    public int getSpanTypeIdInternal() { return 31; }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) {}
    public void writeToParcelInternal(Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<NoWritingToolsSpan> CREATOR =
            new android.os.Parcelable.Creator<NoWritingToolsSpan>() {
        public NoWritingToolsSpan createFromParcel(Parcel in) { return new NoWritingToolsSpan(); }
        public NoWritingToolsSpan[] newArray(int size) { return new NoWritingToolsSpan[size]; }
    };
}
