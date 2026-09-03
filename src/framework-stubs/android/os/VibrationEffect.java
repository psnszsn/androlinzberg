/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class VibrationEffect  implements android.os.Parcelable {
    /** AOSP createWaveform(long[] timings, int repeat): the host records no waveform. */
    public static VibrationEffect createWaveform(long[] timings, int repeat) { return new VibrationEffect(); }
    /** AOSP cropToLengthOrNull: nothing to crop on the host. */
    public VibrationEffect cropToLengthOrNull(int length) { return this; }
    /** AOSP computeCreateWaveformOffOnTimingsOrNull: no waveform to convert. */
    public long[] computeCreateWaveformOffOnTimingsOrNull() { return null; }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<VibrationEffect> CREATOR =
            new android.os.Parcelable.Creator<VibrationEffect>() {
        public VibrationEffect createFromParcel(android.os.Parcel in) { return new VibrationEffect(); }
        public VibrationEffect[] newArray(int size) { return new VibrationEffect[size]; }
    };
}
