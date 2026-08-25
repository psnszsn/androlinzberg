/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class Person  implements android.os.Parcelable {
    public CharSequence getName() { return ""; }
    public static class Builder {
        public Builder setName(CharSequence name) { return this; }
        public Builder setKey(String key) { return this; }
        public Builder setIcon(Object icon) { return this; }
        public Person build() { return new Person(); }
    }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<Person> CREATOR =
            new android.os.Parcelable.Creator<Person>() {
        public Person createFromParcel(android.os.Parcel in) { return new Person(); }
        public Person[] newArray(int size) { return new Person[size]; }
    };
}
