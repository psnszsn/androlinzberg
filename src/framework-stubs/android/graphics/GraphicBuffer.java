/* artzig stub of AOSP android.graphics.GraphicBuffer: the real class wraps a
 * native GraphicBuffer (gralloc) through JNI the host does not provide; the
 * graphics tree excludes it. Linkage only: IActivityManager parcels it as a
 * task-snapshot type. Never instantiated on this host. */
package android.graphics;
import android.os.Parcel;
import android.os.Parcelable;
public class GraphicBuffer implements Parcelable {
    private GraphicBuffer() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException("GraphicBuffer is not hosted");
    }
    public static final Creator<GraphicBuffer> CREATOR = new Creator<GraphicBuffer>() {
        @Override public GraphicBuffer createFromParcel(Parcel in) {
            throw new UnsupportedOperationException("GraphicBuffer is not hosted");
        }
        @Override public GraphicBuffer[] newArray(int size) { return new GraphicBuffer[size]; }
    };
}
