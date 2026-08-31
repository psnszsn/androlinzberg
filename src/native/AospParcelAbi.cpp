// AOSP C++ ABI stand-ins for the libbinder Parcel members referenced by AOSP
// JNI compiled into the island. Most parcel paths are never taken in-process
// and abort loudly. The exception is CursorWindow's parcel path: cross-process
// provider cursors ship real windows, so parcelForJavaObject returns a bridge
// that forwards the members CursorWindow::writeToParcel/createFromParcel use
// onto the hosted pure-Java android.os.Parcel (which carries descriptors over
// SCM_RIGHTS). The bridge is thread-local and valid for the duration of one
// JNI call, which is how the AOSP JNI uses it.
#include <binder/Parcel.h>
#include <cstdlib>
#include <cstdio>
#include <mutex>
#include <utils/String8.h>
#include <jni.h>

#define AZK_PARCEL_ABORT() \
    do { fprintf(stderr, "azk: libbinder Parcel is not available in-process\n"); abort(); } while (0)

namespace android {

namespace {

struct ParcelBridge {
    JNIEnv* env = nullptr;
    // Global ref: a local ref would dangle once the JNI frame that produced
    // it returns. Replaced (and the old one released) on the next
    // parcelForJavaObject on this thread; one ref per thread stays held.
    jobject parcel = nullptr;
};

thread_local ParcelBridge gBridge;
// The returned Parcel* only keys the thread-local bridge; the real Parcel
// fields are never constructed or touched.
thread_local void* gBridgeStorage;

struct ParcelJni {
    jclass cls;
    jmethodID writeInt;
    jmethodID readInt;
    jmethodID writeString;
    jmethodID readString;
    jmethodID writeRawFileDescriptor;
    jmethodID readRawFileDescriptor;
    jclass fdCls;
    jmethodID fdGetInt;
};

const ParcelJni* parcelJni(JNIEnv* env) {
    static ParcelJni ids;
    static std::once_flag once;
    static bool resolved = false;
    std::call_once(once, [env] {
        jclass cls = env->FindClass("android/os/Parcel");
        if (cls == nullptr) return;
        ids.cls = (jclass) env->NewGlobalRef(cls);
        ids.writeInt = env->GetMethodID(ids.cls, "writeInt", "(I)V");
        ids.readInt = env->GetMethodID(ids.cls, "readInt", "()I");
        ids.writeString = env->GetMethodID(ids.cls, "writeString", "(Ljava/lang/String;)V");
        ids.readString = env->GetMethodID(ids.cls, "readString", "()Ljava/lang/String;");
        ids.writeRawFileDescriptor = env->GetMethodID(ids.cls, "writeRawFileDescriptor", "(I)V");
        ids.readRawFileDescriptor =
                env->GetMethodID(ids.cls, "readRawFileDescriptor", "()Ljava/io/FileDescriptor;");
        jclass fdCls = env->FindClass("java/io/FileDescriptor");
        if (fdCls == nullptr) return;
        ids.fdCls = (jclass) env->NewGlobalRef(fdCls);
        ids.fdGetInt = env->GetMethodID(ids.fdCls, "getInt$", "()I");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
        resolved = true;
    });
    return resolved ? &ids : nullptr;
}

// A pending Java exception poisons further JNI calls; surface it as a parcel
// error and let the JNI glue rethrow when it returns.
bool bridgeBroken(const ParcelBridge& b) {
    return b.env == nullptr || b.parcel == nullptr || b.env->ExceptionCheck();
}

}  // namespace

Parcel* parcelForJavaObject(JNIEnv* env, jobject parcelObj) {
    if (parcelObj == nullptr) return nullptr;
    if (parcelJni(env) == nullptr) return nullptr;
    if (gBridge.parcel != nullptr) env->DeleteGlobalRef(gBridge.parcel);
    gBridge.env = env;
    gBridge.parcel = env->NewGlobalRef(parcelObj);
    return reinterpret_cast<Parcel*>(&gBridgeStorage);
}

status_t Parcel::writeInt32(int32_t v) {
    if (bridgeBroken(gBridge)) return BAD_VALUE;
    const ParcelJni* jni = parcelJni(gBridge.env);
    gBridge.env->CallVoidMethod(gBridge.parcel, jni->writeInt, (jint) v);
    return gBridge.env->ExceptionCheck() ? BAD_VALUE : OK;
}

status_t Parcel::writeUint32(uint32_t v) { return writeInt32((int32_t) v); }
status_t Parcel::writeBool(bool v) { return writeInt32(v ? 1 : 0); }

int32_t Parcel::readInt32() const {
    if (bridgeBroken(gBridge)) return 0;
    const ParcelJni* jni = parcelJni(gBridge.env);
    jint v = gBridge.env->CallIntMethod(gBridge.parcel, jni->readInt);
    return gBridge.env->ExceptionCheck() ? 0 : v;
}

uint32_t Parcel::readUint32() const { return (uint32_t) readInt32(); }

status_t Parcel::readUint32(uint32_t* out) const {
    if (bridgeBroken(gBridge)) return BAD_VALUE;
    *out = (uint32_t) readInt32();
    return gBridge.env->ExceptionCheck() ? BAD_VALUE : OK;
}

status_t Parcel::readBool(bool* out) const {
    uint32_t v = 0;
    status_t status = readUint32(&v);
    *out = v != 0;
    return status;
}

status_t Parcel::writeString8(const String8& s) {
    if (bridgeBroken(gBridge)) return BAD_VALUE;
    const ParcelJni* jni = parcelJni(gBridge.env);
    jstring str = gBridge.env->NewStringUTF(s.c_str());
    if (str == nullptr) return NO_MEMORY;
    gBridge.env->CallVoidMethod(gBridge.parcel, jni->writeString, str);
    gBridge.env->DeleteLocalRef(str);
    return gBridge.env->ExceptionCheck() ? BAD_VALUE : OK;
}

status_t Parcel::readString8(String8* out) const {
    if (bridgeBroken(gBridge)) return BAD_VALUE;
    const ParcelJni* jni = parcelJni(gBridge.env);
    jstring str = (jstring) gBridge.env->CallObjectMethod(gBridge.parcel, jni->readString);
    if (gBridge.env->ExceptionCheck()) return BAD_VALUE;
    if (str == nullptr) {
        *out = String8();
        return OK;
    }
    const char* chars = gBridge.env->GetStringUTFChars(str, nullptr);
    if (chars == nullptr) return NO_MEMORY;
    *out = String8(chars);
    gBridge.env->ReleaseStringUTFChars(str, chars);
    gBridge.env->DeleteLocalRef(str);
    return OK;
}

/// The hosted Parcel dups internally, matching this member's contract (the
/// caller keeps ownership of `fd`).
status_t Parcel::writeDupFileDescriptor(int fd) {
    if (bridgeBroken(gBridge)) return BAD_VALUE;
    const ParcelJni* jni = parcelJni(gBridge.env);
    gBridge.env->CallVoidMethod(gBridge.parcel, jni->writeRawFileDescriptor, (jint) fd);
    return gBridge.env->ExceptionCheck() ? BAD_VALUE : OK;
}

/// Parcel-owned descriptor, per AOSP: the caller must dup to keep it.
/// readRawFileDescriptor on the Java side has exactly that lifetime.
int Parcel::readFileDescriptor() const {
    if (bridgeBroken(gBridge)) return -1;
    const ParcelJni* jni = parcelJni(gBridge.env);
    jobject fd = gBridge.env->CallObjectMethod(gBridge.parcel, jni->readRawFileDescriptor);
    if (gBridge.env->ExceptionCheck() || fd == nullptr) return -1;
    jint raw = gBridge.env->CallIntMethod(fd, jni->fdGetInt);
    gBridge.env->DeleteLocalRef(fd);
    return gBridge.env->ExceptionCheck() ? -1 : raw;
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
