/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class Person  implements android.os.Parcelable {
    private CharSequence mName;
    private android.graphics.drawable.Icon mIcon;
    private String mUri;
    private String mKey;
    private boolean mImportant;
    private boolean mBot;
    public CharSequence getName() { return mName; }
    public android.graphics.drawable.Icon getIcon() { return mIcon; }
    public String getUri() { return mUri; }
    public String getKey() { return mKey; }
    public boolean isImportant() { return mImportant; }
    public boolean isBot() { return mBot; }
    public Builder toBuilder() {
        return new Builder().setName(mName).setIcon(mIcon).setUri(mUri).setKey(mKey)
                .setImportant(mImportant).setBot(mBot);
    }
    public static class Builder {
        private final Person mPerson = new Person();
        public Builder setName(CharSequence name) { mPerson.mName = name; return this; }
        public Builder setKey(String key) { mPerson.mKey = key; return this; }
        public Builder setIcon(android.graphics.drawable.Icon icon) {
            mPerson.mIcon = icon;
            return this;
        }
        public Builder setUri(String uri) { mPerson.mUri = uri; return this; }
        public Builder setImportant(boolean important) {
            mPerson.mImportant = important;
            return this;
        }
        public Builder setBot(boolean bot) { mPerson.mBot = bot; return this; }
        public Person build() { return mPerson; }
    }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<Person> CREATOR =
            new android.os.Parcelable.Creator<Person>() {
        public Person createFromParcel(android.os.Parcel in) { return new Person(); }
        public Person[] newArray(int size) { return new Person[size]; }
    };
}
