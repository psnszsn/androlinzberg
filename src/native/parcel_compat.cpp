// artzig: link-time stand-ins for the libbinder Parcel members referenced by the
// input JNI's parcel paths (never taken in-process: parcelForJavaObject -> null,
// and our Java Parcel is pure-java).
#include <binder/Parcel.h>
#include <cstdlib>
#include <cstdio>
#include <utils/String8.h>
#include <jni.h>

#define AZK_PARCEL_ABORT() \
    do { fprintf(stderr, "azk: libbinder Parcel is not available in-process\n"); abort(); } while (0)

namespace android {

Parcel* parcelForJavaObject(JNIEnv*, jobject) { return nullptr; }

status_t Parcel::errorCheck() const { AZK_PARCEL_ABORT(); }
bool Parcel::readBool() const { AZK_PARCEL_ABORT(); }
int8_t Parcel::readByte() const { AZK_PARCEL_ABORT(); }
status_t Parcel::readByteVector(std::vector<uint8_t>*) const { AZK_PARCEL_ABORT(); }
const char* Parcel::readCString() const { AZK_PARCEL_ABORT(); }
float Parcel::readFloat() const { AZK_PARCEL_ABORT(); }
status_t Parcel::readFloat(float*) const { AZK_PARCEL_ABORT(); }
int32_t Parcel::readInt32() const { AZK_PARCEL_ABORT(); }
int64_t Parcel::readInt64() const { AZK_PARCEL_ABORT(); }
uint32_t Parcel::readUint32() const { AZK_PARCEL_ABORT(); }
status_t Parcel::writeBool(bool) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeByte(int8_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeByteVector(const std::vector<uint8_t>&) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeCString(const char*) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeFloat(float) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeInt32(int32_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeInt64(int64_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeUint32(uint32_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::readBool(bool*) const { AZK_PARCEL_ABORT(); }
int Parcel::readFileDescriptor() const { AZK_PARCEL_ABORT(); }
status_t Parcel::readString8(String8*) const { AZK_PARCEL_ABORT(); }
status_t Parcel::readUint32(uint32_t*) const { AZK_PARCEL_ABORT(); }
status_t Parcel::read(void*, size_t) const { AZK_PARCEL_ABORT(); }
status_t Parcel::writeDupFileDescriptor(int) { AZK_PARCEL_ABORT(); }
void* Parcel::writeInplace(size_t) { AZK_PARCEL_ABORT(); }
status_t Parcel::writeString8(const String8&) { AZK_PARCEL_ABORT(); }

// liblog compat: verbose gate used by CursorWindow logging.
bool android_util_Log_isVerboseLogEnabled(const char*) { return false; }

}  // namespace android
