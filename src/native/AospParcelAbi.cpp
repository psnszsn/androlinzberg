// AOSP C++ ABI stand-ins for the libbinder Parcel members referenced by AOSP
// JNI compiled into the island. Most parcel paths are never taken in-process
// and abort loudly. The exception is CursorWindow's parcel path: cross-process
// provider cursors ship real windows, so parcelForJavaObject resolves the Java
// android.os.Parcel's native object (mNativePtr, an AParcel owned by the zig
// libbinder_ndk) and the members CursorWindow::writeToParcel/createFromParcel
// use operate on it directly through the NDK C ABI plus a tiny azk_parcel_*
// shim — no JNI round trips, and the String8/descriptor wire layout is the
// real one. The bridge is thread-local and valid for the duration of one JNI
// call, which is how the AOSP JNI uses it.
#include <binder/Parcel.h>
#include <cstdlib>
#include <cstdio>
#include <mutex>
#include <utils/String8.h>
#include <jni.h>
#include <android/binder_parcel.h>

extern "C" {
int32_t azk_parcel_writeString8(AParcel* parcel, const char* str, size_t len);
int32_t azk_parcel_readString8(const AParcel* parcel, char** out, size_t* outLen);
void azk_parcel_freeString8(char* ptr, size_t len);
int32_t azk_parcel_readFileDescriptorBorrowed(const AParcel* parcel, int* fd);
}

#define AZK_PARCEL_ABORT() \
    do { fprintf(stderr, "azk: libbinder Parcel is not available in-process\n"); abort(); } while (0)

namespace android {

namespace {

// The Java Parcel's AParcel for the JNI call in progress on this thread.
thread_local AParcel* gNative = nullptr;
// The returned Parcel* only keys the thread-local bridge; the real Parcel
// fields are never constructed or touched.
thread_local void* gBridgeStorage;

jfieldID nativePtrField(JNIEnv* env) {
    static std::once_flag once;
    static jfieldID field = nullptr;
    std::call_once(once, [env] {
        jclass cls = env->FindClass("android/os/Parcel");
        if (cls == nullptr) return;
        field = env->GetFieldID(cls, "mNativePtr", "J");
        env->DeleteLocalRef(cls);
    });
    if (field == nullptr && env->ExceptionCheck()) env->ExceptionClear();
    return field;
}

status_t fromNdk(binder_status_t status) { return status == STATUS_OK ? OK : BAD_VALUE; }

}  // namespace

Parcel* parcelForJavaObject(JNIEnv* env, jobject parcelObj) {
    if (parcelObj == nullptr) return nullptr;
    jfieldID field = nativePtrField(env);
    if (field == nullptr) return nullptr;
    jlong ptr = env->GetLongField(parcelObj, field);
    if (ptr == 0) return nullptr;
    gNative = reinterpret_cast<AParcel*>(static_cast<intptr_t>(ptr));
    return reinterpret_cast<Parcel*>(&gBridgeStorage);
}

status_t Parcel::writeInt32(int32_t v) {
    if (gNative == nullptr) return BAD_VALUE;
    return fromNdk(AParcel_writeInt32(gNative, v));
}

status_t Parcel::writeUint32(uint32_t v) { return writeInt32((int32_t) v); }
status_t Parcel::writeBool(bool v) { return writeInt32(v ? 1 : 0); }

int32_t Parcel::readInt32() const {
    int32_t v = 0;
    if (gNative == nullptr) return 0;
    return AParcel_readInt32(gNative, &v) == STATUS_OK ? v : 0;
}

uint32_t Parcel::readUint32() const { return (uint32_t) readInt32(); }

status_t Parcel::readUint32(uint32_t* out) const {
    int32_t v = 0;
    if (gNative == nullptr) return BAD_VALUE;
    status_t status = fromNdk(AParcel_readInt32(gNative, &v));
    *out = (uint32_t) v;
    return status;
}

status_t Parcel::readBool(bool* out) const {
    uint32_t v = 0;
    status_t status = readUint32(&v);
    *out = v != 0;
    return status;
}

status_t Parcel::writeString8(const String8& s) {
    if (gNative == nullptr) return BAD_VALUE;
    return fromNdk(azk_parcel_writeString8(gNative, s.c_str(), s.size()));
}

// android 16: CursorWindow uses the value-returning overload.
String8 Parcel::readString8() const {
    String8 out;
    (void)readString8(&out);
    return out;
}

status_t Parcel::readString8(String8* out) const {
    if (gNative == nullptr) return BAD_VALUE;
    char* chars = nullptr;
    size_t len = 0;
    if (azk_parcel_readString8(gNative, &chars, &len) != STATUS_OK) return BAD_VALUE;
    if (chars == nullptr) {
        *out = String8();
        return OK;
    }
    *out = String8(chars, len);
    azk_parcel_freeString8(chars, len);
    return OK;
}

/// The NDK write dups internally, matching this member's contract (the caller
/// keeps ownership of `fd`).
status_t Parcel::writeDupFileDescriptor(int fd) {
    if (gNative == nullptr) return BAD_VALUE;
    return fromNdk(AParcel_writeParcelFileDescriptor(gNative, fd));
}

/// Parcel-owned descriptor, per AOSP: the caller must dup to keep it.
int Parcel::readFileDescriptor() const {
    int fd = -1;
    if (gNative == nullptr) return -1;
    return azk_parcel_readFileDescriptorBorrowed(gNative, &fd) == STATUS_OK ? fd : -1;
}

// Everything below stays unreachable in-process and aborts loudly.
status_t Parcel::errorCheck() const { AZK_PARCEL_ABORT(); }
bool Parcel::readBool() const { AZK_PARCEL_ABORT(); }
int8_t Parcel::readByte() const { AZK_PARCEL_ABORT(); }
status_t Parcel::readByteVector(std::vector<uint8_t>*) const { AZK_PARCEL_ABORT(); }
const char* Parcel::readCString() const { AZK_PARCEL_ABORT(); }
float Parcel::readFloat() const { AZK_PARCEL_ABORT(); }
status_t Parcel::readFloat(float*) const { AZK_PARCEL_ABORT(); }
int64_t Parcel::readInt64() const { AZK_PARCEL_ABORT(); }
status_t Parcel::writeByte(int8_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeByteVector(const std::vector<uint8_t>&) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeCString(const char*) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeFloat(float) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeInt64(int64_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::read(void*, size_t) const { AZK_PARCEL_ABORT(); }
void* Parcel::writeInplace(size_t) { AZK_PARCEL_ABORT(); }

// liblog compat: verbose gate used by CursorWindow logging.
bool android_util_Log_isVerboseLogEnabled(const char*) { return false; }

}  // namespace android
