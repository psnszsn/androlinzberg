/* artzig stub: android.widget.RemoteViews — annotation + placeholder types only. */
package android.widget;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
public class RemoteViews implements android.os.Parcelable {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface RemoteView {}
    public interface OnClickHandler {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static class RemoteCollectionItems implements android.os.Parcelable {
        public int describeContents() { return 0; }
        public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final android.os.Parcelable.Creator<RemoteCollectionItems> CREATOR =
                new android.os.Parcelable.Creator<RemoteCollectionItems>() {
            public RemoteCollectionItems createFromParcel(android.os.Parcel in) { return new RemoteCollectionItems(); }
            public RemoteCollectionItems[] newArray(int size) { return new RemoteCollectionItems[size]; }
        };
    }
    public static class RemoteResponse {}
    public interface InteractionHandler {}
    public static final android.os.Parcelable.Creator<RemoteViews> CREATOR =
            new android.os.Parcelable.Creator<RemoteViews>() {
        public RemoteViews createFromParcel(android.os.Parcel in) { return new RemoteViews(); }
        public RemoteViews[] newArray(int size) { return new RemoteViews[size]; }
    };
}
