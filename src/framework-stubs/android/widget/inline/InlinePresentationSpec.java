/* artzig stub (auto-generated placeholder; members added on demand). */
package android.widget.inline;
public class InlinePresentationSpec implements android.os.Parcelable {
    public android.util.Size getMinSize() { return new android.util.Size(0, 0); }
    public android.util.Size getMaxSize() { return new android.util.Size(0, 0); }
    public android.os.Bundle getStyle() { return new android.os.Bundle(); }
    public void filterContentTypes() {}    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<InlinePresentationSpec> CREATOR =
            new android.os.Parcelable.Creator<InlinePresentationSpec>() {
        public InlinePresentationSpec createFromParcel(android.os.Parcel in) { return new InlinePresentationSpec(); }
        public InlinePresentationSpec[] newArray(int size) { return new InlinePresentationSpec[size]; }
    };
}
