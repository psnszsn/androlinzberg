#pragma once
#include <android/binder_parcel.h>
#include <binder/IBinder.h>
#include <unistd.h>
#include <utils/Flattenable.h>
#include <utils/String8.h>

#include <vector>
extern "C" AParcel* AParcel_create();
extern "C" AParcel* AZKPrototype_Parcel_clone(const AParcel*);
namespace android {
class Parcel {
  AParcel* p;
  bool owned;

 public:
  Parcel() : p(AParcel_create()), owned(true) {}
  explicit Parcel(AParcel* borrowed) : p(borrowed), owned(false) {}
  ~Parcel() {
    if (owned) AParcel_delete(p);
  }
  Parcel(const Parcel&) = delete;
  AParcel* native() const { return p; }
  void adopt(AParcel* n) {
    if (owned) AParcel_delete(p);
    p = n;
    owned = true;
  }
  size_t dataPosition() const { return AParcel_getDataPosition(p); }
  void setDataPosition(size_t n) const { AParcel_setDataPosition(p, n); }
  void markForBinder(const sp<IBinder>&) {
  }  // Only one hosted encoding domain in this experiment.
  status_t writeInt32(int32_t v) { return AParcel_writeInt32(p, v); }
  status_t readInt32(int32_t* v) const { return AParcel_readInt32(p, v); }
  int32_t readInt32() const {
    int32_t v = 0;
    readInt32(&v);
    return v;
  }
  status_t writeFloat(float v) { return AParcel_writeFloat(p, v); }
  status_t readFloat(float* v) const { return AParcel_readFloat(p, v); }
  status_t writeString16(const String16& v) {
    String8 s(v);
    return AParcel_writeString(p, s.c_str(), s.size());
  }
  status_t readString16(String16* out) const {
    std::vector<char> s;
    auto allocator = [](void* p, int32_t n, char** out) {
      if (n < 1) return false;
      auto& v = *static_cast<std::vector<char>*>(p);
      v.resize(n);
      *out = v.data();
      return true;
    };
    auto rc = AParcel_readString(p, &s, allocator);
    if (!rc) *out = String16(s.data());
    return rc;
  }
  status_t writeInterfaceToken(const String16& d) {
    writeInt32(0);
    writeInt32(-1);
    writeInt32(0x53595354);
    return writeString16(d);
  }
  bool checkInterface(const IBinder* b) const {
    int32_t x;
    for (int i = 0; i < 3; i++)
      if (readInt32(&x)) return false;
    String16 d;
    return readString16(&d) == OK && d == b->getInterfaceDescriptor();
  }
  status_t writeStrongBinder(const sp<IBinder>& b) {
    auto* n = b ? b->acquireNative() : nullptr;
    auto rc = AParcel_writeStrongBinder(p, n);
    if (n) AIBinder_decStrong(n);
    return rc;
  }
  status_t readStrongBinder(sp<IBinder>* b) const {
    AIBinder* n = nullptr;
    auto rc = AParcel_readStrongBinder(p, &n);
    if (rc) return rc;
    *b = IBinder::fromNative(n);
    return n ? OK : UNEXPECTED_NULL;
  }
  sp<IBinder> readStrongBinder() const {
    sp<IBinder> b;
    readStrongBinder(&b);
    return b;
  }
  status_t writeByteVector(const std::vector<uint8_t>& v) {
    return AParcel_writeByteArray(p, reinterpret_cast<const int8_t*>(v.data()),
                                  v.size());
  }
  status_t readByteVector(std::vector<uint8_t>* v) const {
    auto allocator = [](void* p, int32_t n, int8_t** out) {
      if (n < 0 || n > 1024 * 1024) return false;
      auto& v = *static_cast<std::vector<uint8_t>*>(p);
      v.resize(n);
      *out = reinterpret_cast<int8_t*>(v.data());
      return true;
    };
    return AParcel_readByteArray(p, v, allocator);
  }
  // Host encoding contains flattened native bytes and an explicit FD vector.
  // Native type flatten/unflatten implementations remain pinned AOSP code.
  template <class T>
  status_t write(const Flattenable<T>& f) {
    std::vector<uint8_t> bytes(f.getFlattenedSize());
    std::vector<int> fds(f.getFdCount());
    void* ptr = bytes.data();
    size_t size = bytes.size(), count = fds.size();
    int* fp = fds.data();
    auto rc = f.flatten(ptr, size, fp, count);
    if (rc) return rc;
    if ((rc = writeByteVector(bytes)) || (rc = writeInt32(fds.size())))
      return rc;
    for (int fd : fds)
      if ((rc = AParcel_writeParcelFileDescriptor(p, fd))) return rc;
    return OK;
  }
  template <class T>
  status_t read(Flattenable<T>& f) const {
    std::vector<uint8_t> bytes;
    int32_t n;
    auto rc = readByteVector(&bytes);
    if (rc) return rc;
    if ((rc = readInt32(&n))) return rc;
    if (n < 0 || n > 64) return BAD_VALUE;
    std::vector<int> fds(n, -1);
    size_t count = n;
    for (auto& fd : fds)
      if ((rc = AParcel_readParcelFileDescriptor(p, &fd))) break;
    if (!rc) {
      const void* ptr = bytes.data();
      size_t size = bytes.size();
      const int* fp = fds.data();
      rc = f.unflatten(ptr, size, fp, count);
    }
    // On success native Flattenables own the descriptors they consume.
    size_t consumed = rc ? 0 : fds.size() - count;
    for (size_t i = consumed; i < fds.size(); i++)
      if (fds[i] >= 0) close(fds[i]);
    return rc;
  }
};
}  // namespace android
