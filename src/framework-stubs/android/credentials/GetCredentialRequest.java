/* artzig stub. */
package android.credentials;
import java.util.ArrayList;
import java.util.List;
public final class GetCredentialRequest  implements android.os.Parcelable {
    public List<CredentialOption> getCredentialOptions() { return new ArrayList<>(); }
    public android.os.Bundle getData() { return new android.os.Bundle(); }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<GetCredentialRequest> CREATOR =
            new android.os.Parcelable.Creator<GetCredentialRequest>() {
        public GetCredentialRequest createFromParcel(android.os.Parcel in) { return new GetCredentialRequest(); }
        public GetCredentialRequest[] newArray(int size) { return new GetCredentialRequest[size]; }
    };
}
