/* artzig stub of the AIDL-generated android.hardware.radio.data.ApnTypes enum: hardware/interfaces (the radio HAL)
 * is not vendored; ApnSetting.TYPE_* re-exports these values. Values from the
 * android-16 radio HAL as published through the framework API text. */
package android.hardware.radio.data;

public @interface ApnTypes {
    int NONE = 0;
    int DEFAULT = 1;
    int MMS = 2;
    int SUPL = 4;
    int DUN = 8;
    int HIPRI = 16;
    int FOTA = 32;
    int IMS = 64;
    int CBS = 128;
    int IA = 256;
    int EMERGENCY = 512;
    int MCX = 1024;
    int XCAP = 2048;
    int VSIM = 4096;
    int BIP = 8192;
    int ENTERPRISE = 16384;
    int RCS = 32768;
    int OEM_PAID = 65536;
    int OEM_PRIVATE = 131072;
}
