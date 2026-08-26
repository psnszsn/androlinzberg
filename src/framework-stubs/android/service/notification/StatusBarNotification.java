/* artzig stub: notification records are never posted by the host. */
package android.service.notification;

public class StatusBarNotification implements android.os.Parcelable {
    public String getPackageName() { return null; }
    public String getTag() { return null; }
    public int getId() { return 0; }
    public android.app.Notification getNotification() { return null; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<StatusBarNotification> CREATOR =
            new android.os.Parcelable.Creator<StatusBarNotification>() {
        public StatusBarNotification createFromParcel(android.os.Parcel in) {
            return new StatusBarNotification();
        }
        public StatusBarNotification[] newArray(int size) {
            return new StatusBarNotification[size];
        }
    };
}
