#pragma once
// Source note: follows pinned libbinder Parcel's public serialization
// contracts. Its kernel/RPC storage cannot be reused: this object owns or
// borrows the shared Zig Parcel used by Java, NDK, and hosted native clients.
#include <algorithm>
#include <android-base/unique_fd.h>
#include <array>
#include <azk/binder.h>
#include <binder/IBinder.h>
#include <binder/IInterface.h>
#include <binder/Parcelable.h>
#include <bit>
#include <climits>
#include <cstring>
#include <cutils/native_handle.h>
#include <log/log.h>
#include <memory>
#include <optional>
#include <type_traits>
#include <utils/Flattenable.h>
#include <utils/String8.h>
#include <utils/Vector.h>
#include <vector>
extern "C" int32_t AParcel_appendFrom(const AParcel *, AParcel *, int32_t,
                                      int32_t);
namespace android {
class Parcel {
public:
  // Pinned Parcel blob accessors; the Parcel owns descriptors and Blob owns its mapping.
  class Blob {
  public:
    Blob();
    ~Blob();
    void clear();
    void release();
    size_t size() const { return mSize; }
    int fd() const { return mFd; }
    bool isMutable() const { return mMutable; }
  protected:
    void init(int fd, void* data, size_t size, bool isMutable);
    int mFd;
    void* mData;
    size_t mSize;
    bool mMutable;
  };
  class ReadableBlob : public Blob {
    friend class Parcel;
  public:
    const void* data() const { return mData; }
    void* mutableData() { return isMutable() ? mData : nullptr; }
  };
  class WritableBlob : public Blob {
    friend class Parcel;
  public:
    void* data() { return mData; }
  };
  status_t writeBlob(size_t len, bool mutableCopy, WritableBlob* outBlob);
  status_t readBlob(size_t len, ReadableBlob* outBlob) const;
  status_t writeDupImmutableBlobFileDescriptor(int fd);
  Parcel();
  ~Parcel();
  Parcel(const Parcel &) = delete;
  Parcel &operator=(const Parcel &) = delete;
  explicit Parcel(AParcel *borrowed);
  AParcel *native() const { return mParcel; }
  static Parcel *forNative(AParcel *);
  status_t writeNoException();
  int32_t readExceptionCode() const;
  status_t writeByteArray(size_t len, const uint8_t* val);
  size_t dataPosition() const { return AParcel_getDataPosition(mParcel); }
  size_t dataSize() const { return AParcel_getDataSize(mParcel); }
  size_t dataAvail() const {
    return dataPosition() >= dataSize() ? 0 : dataSize() - dataPosition();
  }
  size_t dataCapacity() const { return azk_parcel_capacity(mParcel); }
  const uint8_t *data() const {
    return static_cast<const uint8_t *>(azk_parcel_data(mParcel));
  }
  void setDataPosition(size_t n) const {
    if (n > INT32_MAX) {
      setError(BAD_VALUE);
      return;
    }
    AParcel_setDataPosition(mParcel, n);
  }
  status_t setDataSize(size_t n) { return azk_parcel_setSize(mParcel, n); }
  status_t setDataCapacity(size_t n) {
    return azk_parcel_setCapacity(mParcel, n);
  }
  status_t errorCheck() const { return azk_parcel_error(mParcel); }
  void setError(status_t s) const { azk_parcel_setError(mParcel, s); }
  status_t appendFrom(const Parcel *p, size_t start, size_t count) {
    if (!p || start > INT32_MAX || count > INT32_MAX)
      return BAD_VALUE;
    return AParcel_appendFrom(p->mParcel, mParcel, start, count);
  }
  void freeData() { AParcel_reset(mParcel); }
  void markForBinder(const sp<IBinder> &) {
  } // All hosted endpoints share one encoding.
  bool isForRpc() const { return false; }
  void markSensitive() const;
  bool hasFileDescriptors() const { return azk_parcel_hasFds(mParcel); }
  bool pushAllowFds(bool b) { return azk_parcel_allowFds(mParcel, b); }
  void restoreAllowFds(bool b) { azk_parcel_allowFds(mParcel, b); }
  bool allowFds() const;
  status_t write(const void *, size_t);
  status_t read(void *, size_t) const;
  void *writeInplace(size_t n) { return azk_parcel_writeInplace(mParcel, n); }
  const void *readInplace(size_t n) const {
    return azk_parcel_readInplace(mParcel, n);
  }
  status_t writeInt32(int32_t v) {
    auto s = AParcel_writeInt32(mParcel, v);
    return s ? s : errorCheck();
  }
  status_t readInt32(int32_t *v) const { return AParcel_readInt32(mParcel, v); }
  int32_t readInt32() const {
    int32_t v{};
    readInt32(&v);
    return v;
  }
  status_t writeInt64(int64_t v) {
    auto s = AParcel_writeInt64(mParcel, v);
    return s ? s : errorCheck();
  }
  status_t readInt64(int64_t *v) const { return AParcel_readInt64(mParcel, v); }
  int64_t readInt64() const {
    int64_t v{};
    readInt64(&v);
    return v;
  }
  status_t writeFloat(float v) {
    auto s = AParcel_writeFloat(mParcel, v);
    return s ? s : errorCheck();
  }
  status_t readFloat(float *v) const { return AParcel_readFloat(mParcel, v); }
  float readFloat() const {
    float v{};
    readFloat(&v);
    return v;
  }
  status_t writeDouble(double v) {
    auto s = AParcel_writeDouble(mParcel, v);
    return s ? s : errorCheck();
  }
  status_t readDouble(double *v) const {
    return AParcel_readDouble(mParcel, v);
  }
  double readDouble() const {
    double v{};
    readDouble(&v);
    return v;
  }
  status_t writeUint32(uint32_t v) {
    return writeInt32(static_cast<int32_t>(v));
  }
  status_t readUint32(uint32_t *v) const {
    int32_t x{};
    auto s = readInt32(&x);
    *v = static_cast<uint32_t>(x);
    return s;
  }
  uint32_t readUint32() const {
    uint32_t v{};
    readUint32(&v);
    return v;
  }
  status_t writeUint64(uint64_t v) {
    return writeInt64(static_cast<int64_t>(v));
  }
  status_t readUint64(uint64_t *v) const {
    int64_t x{};
    auto s = readInt64(&x);
    *v = static_cast<uint64_t>(x);
    return s;
  }
  uint64_t readUint64() const {
    uint64_t v{};
    readUint64(&v);
    return v;
  }
  status_t writeByte(int8_t v) { return writeInt32(static_cast<int32_t>(v)); }
  status_t readByte(int8_t *v) const {
    int32_t x{};
    auto s = readInt32(&x);
    *v = static_cast<int8_t>(x);
    return s;
  }
  int8_t readByte() const {
    int8_t v{};
    readByte(&v);
    return v;
  }
  status_t writeChar(char16_t v) { return writeInt32(static_cast<int32_t>(v)); }
  status_t readChar(char16_t *v) const {
    int32_t x{};
    auto s = readInt32(&x);
    *v = static_cast<char16_t>(x);
    return s;
  }
  char16_t readChar() const {
    char16_t v{};
    readChar(&v);
    return v;
  }
  status_t writeBool(bool v) { return writeInt32(static_cast<int32_t>(v)); }
  status_t readBool(bool *v) const {
    int32_t x{};
    auto s = readInt32(&x);
    *v = static_cast<bool>(x);
    return s;
  }
  bool readBool() const {
    bool v{};
    readBool(&v);
    return v;
  }
  status_t writeString8(const String8 &s) {
    return writeString8(s.c_str(), s.size());
  }
  status_t writeString8(const char *, size_t);
  String8 readString8() const {
    String8 s;
    readString8(&s);
    return s;
  }
  status_t readString8(String8 *) const;
  status_t writeString16(const String16 &s) {
    return writeString16(s.c_str(), s.size());
  }
  status_t writeString16(const char16_t *, size_t);
  String16 readString16() const {
    String16 s;
    readString16(&s);
    return s;
  }
  status_t readString16(String16 *) const;
  status_t readString16(std::optional<String16> *) const;
  status_t writeUtf8AsUtf16(const std::string &s);
  status_t writeUtf8AsUtf16(const std::optional<std::string> &s) {
    return s ? writeUtf8AsUtf16(*s) : writeInt32(-1);
  }
  status_t readUtf8FromUtf16(std::string *s) const;
  status_t readUtf8FromUtf16(std::optional<std::string> *s) const {
    // Pinned readData(nullable): a missing length preserves the destination;
    // null resets it, and a present string is reconstructed before conversion.
    const size_t start = dataPosition();
    int32_t length;
    status_t status = readInt32(&length);
    if (status != OK) return status;
    if (length == -1) { s->reset(); return OK; }
    s->emplace(std::string());
    setDataPosition(start);
    return readUtf8FromUtf16(&**s);
  }
  status_t writeCString(const char *s) {
    return s ? write(s, strlen(s) + 1) : BAD_VALUE;
  }
  const char *readCString() const;
  status_t writeInterfaceToken(const String16 &);
  bool enforceInterface(const String16 &,
                        class IPCThreadState * = nullptr) const;
  bool checkInterface(const IBinder *b) const {
    return b && enforceInterface(b->getInterfaceDescriptor());
  }
  status_t enforceNoDataAvail() const {
    return dataAvail() == 0 ? OK : BAD_VALUE;
  }
  status_t writeStrongBinder(const sp<IBinder> &);
  template <class T, std::enable_if_t<std::is_base_of_v<IInterface, T>, bool> = true>
  status_t writeStrongBinder(const sp<T> &value) {
    return writeStrongBinder(T::asBinder(value));
  }
  status_t readNullableStrongBinder(sp<IBinder> *) const;
  status_t readStrongBinder(sp<IBinder> *b) const {
    auto s = readNullableStrongBinder(b);
    return s ? s : (*b ? OK : UNEXPECTED_NULL);
  }
  sp<IBinder> readStrongBinder() const {
    sp<IBinder> b;
    readNullableStrongBinder(&b);
    return b;
  }
  status_t writeFileDescriptor(int fd, bool take = false) {
    return fd < 0 ? BAD_VALUE : azk_parcel_writeFd(mParcel, fd, take);
  }
  status_t writeDupFileDescriptor(int fd) {
    return fd < 0 ? BAD_VALUE : AParcel_writeParcelFileDescriptor(mParcel, fd);
  }
  int readFileDescriptor() const {
    int fd = -1;
    auto s = azk_parcel_readFileDescriptorBorrowed(mParcel, &fd);
    return s ? s : fd;
  }
  status_t readUniqueFileDescriptor(base::unique_fd *) const;
  status_t writeUniqueFileDescriptor(const base::unique_fd &fd) {
    return writeDupFileDescriptor(fd.get());
  }
  status_t writeNativeHandle(const native_handle_t *);
  native_handle_t *readNativeHandle() const;
  status_t writeParcelable(const Parcelable &v) {
    auto s = writeInt32(1);
    return s ? s : v.writeToParcel(this);
  }
  status_t readParcelable(Parcelable *v) const {
    int32_t present;
    auto s = readInt32(&present);
    if (s)
      return s;
    return present == 1 ? v->readFromParcel(this) : UNEXPECTED_NULL;
  }
  template <class T> status_t writeParcelable(const std::optional<T> &v) {
    auto s = writeInt32(v ? 1 : 0);
    return s ? s : (v ? v->writeToParcel(this) : OK);
  }
  template <class T> status_t readParcelable(std::optional<T> *v) const {
    int32_t present;
    auto s = readInt32(&present);
    if (s)
      return s;
    if (!present) {
      v->reset();
      return OK;
    }
    if (present != 1)
      return UNEXPECTED_NULL;
    v->emplace();
    return (*v)->readFromParcel(this);
  }
  template <class T>
  status_t writeNullableParcelable(const std::optional<T> &v) {
    return writeParcelable(v);
  }
  template <class T>
  status_t writeNullableParcelable(const std::unique_ptr<T> &v) {
    return writeRawNullableParcelable(v.get());
  }
  status_t writeRawNullableParcelable(const Parcelable *v) {
    return v ? writeParcelable(*v) : writeInt32(0);
  }
  template <class T>
  status_t readNullableParcelable(std::optional<T> *v) const {
    return readParcelable(v);
  }
  template <class T>
  status_t readNullableParcelable(std::unique_ptr<T> *v) const {
    int32_t present;
    auto s = readInt32(&present);
    if (s)
      return s;
    if (!present) {
      v->reset();
      return OK;
    }
    if (present != 1)
      return UNEXPECTED_NULL;
    *v = std::make_unique<T>();
    return (*v)->readFromParcel(this);
  }
  // Pinned Parcel size-only helpers are used for AIDL out parameters and GPU metadata.
  template <class T> status_t writeVectorSize(const std::vector<T> &v) {
    return v.size() > INT32_MAX ? BAD_VALUE : writeInt32(static_cast<int32_t>(v.size()));
  }
  template <class T> status_t writeVectorSize(const std::optional<std::vector<T>> &v) {
    return v ? writeVectorSize(*v) : writeInt32(-1);
  }
  template <class T> status_t writeVectorSize(const std::unique_ptr<std::vector<T>> &v) {
    return v ? writeVectorSize(*v) : writeInt32(-1);
  }
  template <class T> status_t resizeOutVector(std::vector<T> *v) const {
    int32_t size;
    auto status = readOutVectorSizeWithCheck(sizeof(T), &size);
    if (status != OK) return status;
    if (size < 0) return UNEXPECTED_NULL;
    v->resize(static_cast<size_t>(size));
    return OK;
  }
  template <class T> status_t resizeOutVector(std::optional<std::vector<T>> *v) const {
    int32_t size;
    auto status = readOutVectorSizeWithCheck(sizeof(T), &size);
    if (status != OK) return status;
    v->reset();
    if (size >= 0) v->emplace(static_cast<size_t>(size));
    return OK;
  }
  template <class T> status_t resizeOutVector(std::unique_ptr<std::vector<T>> *v) const {
    int32_t size;
    auto status = readOutVectorSizeWithCheck(sizeof(T), &size);
    if (status != OK) return status;
    v->reset();
    if (size >= 0) v->reset(new std::vector<T>(static_cast<size_t>(size)));
    return OK;
  }
  status_t writeUtf8VectorAsUtf16Vector(const std::vector<std::string> &v) {
    return writeVector(v);
  }
  status_t readUtf8VectorFromUtf16Vector(std::vector<std::string> *v) const {
    return readUtf8Vector(v);
  }
  status_t writeUtf8VectorAsUtf16Vector(
      const std::optional<std::vector<std::optional<std::string>>> &v) {
    return writeNullableUtf8Vector(v);
  }
  status_t writeUtf8VectorAsUtf16Vector(
      const std::unique_ptr<std::vector<std::unique_ptr<std::string>>> &v) {
    return writeNullableUtf8Vector(v);
  }
  status_t readUtf8VectorFromUtf16Vector(
      std::optional<std::vector<std::optional<std::string>>> *v) const {
    return readNullableUtf8Vector(v);
  }
  status_t readUtf8VectorFromUtf16Vector(
      std::unique_ptr<std::vector<std::unique_ptr<std::string>>> *v) const {
    return readNullableUtf8Vector(v);
  }
  template <class T> status_t readStrongBinder(sp<T> *v) const {
    sp<IBinder> binder;
    auto status = readStrongBinder(&binder);
    if (status == OK) {
      *v = interface_cast<T>(binder);
      if (*v == nullptr) return UNKNOWN_ERROR;
    }
    return status;
  }
  template <class T> status_t readNullableStrongBinder(sp<T> *v) const {
    sp<IBinder> binder;
    auto status = readNullableStrongBinder(&binder);
    if (status == OK) {
      *v = interface_cast<T>(binder);
      if (*v == nullptr && binder != nullptr) return UNKNOWN_ERROR;
    }
    return status;
  }
  template <class T> status_t writeVector(const std::vector<T> &v) {
    if (v.size() > INT32_MAX)
      return BAD_VALUE;
    auto s = writeInt32(v.size());
    if (s)
      return s;
    if constexpr (std::is_same_v<T, uint8_t> || std::is_same_v<T, int8_t>)
      return write(v.data(), v.size());
    else {
      for (const auto &x : v)
        if ((s = writeData(static_cast<T>(x))))
          return s;
      return OK;
    }
  }
  template <class T> status_t readVector(std::vector<T> *v) const {
    int32_t n;
    auto s = readInt32(&n);
    if (s)
      return s;
    if (n < 0)
      return UNEXPECTED_NULL;
    if (static_cast<size_t>(n) > dataAvail())
      return BAD_VALUE;
    v->clear();
    v->reserve(n);
    if constexpr (std::is_same_v<T, uint8_t> || std::is_same_v<T, int8_t>) {
      v->resize(n);
      return read(v->data(), n);
    } else {
      for (int32_t i = 0; i < n; i++) {
        T value{};
        if ((s = readData(&value)))
          return s;
        v->push_back(std::move(value));
      }
      return OK;
    }
  }
  status_t writeByteVector(const std::vector<uint8_t> &v) {
    return writeVector(v);
  }
  status_t readByteVector(std::vector<uint8_t> *v) const {
    return readVector(v);
  }
  status_t writeInt32Vector(const std::vector<int32_t> &v) {
    return writeVector(v);
  }
  status_t readInt32Vector(std::vector<int32_t> *v) const {
    return readVector(v);
  }
  status_t writeInt64Vector(const std::vector<int64_t> &v) {
    return writeVector(v);
  }
  status_t readInt64Vector(std::vector<int64_t> *v) const {
    return readVector(v);
  }
  status_t writeUint64Vector(const std::vector<uint64_t> &v) {
    return writeVector(v);
  }
  status_t readUint64Vector(std::vector<uint64_t> *v) const {
    return readVector(v);
  }
  status_t writeFloatVector(const std::vector<float> &v) {
    return writeVector(v);
  }
  status_t readFloatVector(std::vector<float> *v) const {
    return readVector(v);
  }
  status_t writeDoubleVector(const std::vector<double> &v) {
    return writeVector(v);
  }
  status_t readDoubleVector(std::vector<double> *v) const {
    return readVector(v);
  }
  status_t writeBoolVector(const std::vector<bool> &v) {
    return writeVector(v);
  }
  status_t readBoolVector(std::vector<bool> *v) const { return readVector(v); }
  status_t writeString16Vector(const std::vector<String16> &v) {
    return writeVector(v);
  }
  status_t readString16Vector(std::vector<String16> *v) const {
    return readVector(v);
  }
  // AOSP Parcel::write/readData(vector<enum>): the backing integers are
  // contiguous, including byte-backed enums (which are not padded per item).
  template <class T> status_t writeEnumVector(const std::vector<T> &v) {
    static_assert(std::is_enum_v<T>);
    if (v.size() > INT32_MAX)
      return BAD_VALUE;
    auto s = writeInt32(v.size());
    return s ? s : write(v.data(), v.size() * sizeof(T));
  }
  template <class T> status_t readEnumVector(std::vector<T> *v) const {
    static_assert(std::is_enum_v<T>);
    int32_t n;
    auto s = readInt32(&n);
    if (s)
      return s;
    if (n < 0)
      return UNEXPECTED_NULL;
    if (static_cast<size_t>(n) > dataAvail())
      return BAD_VALUE;
    v->clear();
    const size_t bytes = static_cast<size_t>(n) * sizeof(T);
    const void *data = readInplace(bytes);
    if (!data)
      return BAD_VALUE;
    v->resize(n);
    memcpy(v->data(), data, bytes);
    return OK;
  }
  template <class T> status_t writeParcelableVector(const std::vector<T> &v) {
    return writeVector(v);
  }
  template <class T> status_t readParcelableVector(std::vector<T> *v) const {
    return readVector(v);
  }
  template <class T>
  status_t writeParcelableVector(const std::optional<std::vector<std::optional<T>>> &v) {
    return v ? writeVector(*v) : writeInt32(-1);
  }
  template <class T>
  status_t readParcelableVector(std::optional<std::vector<std::optional<T>>> *v) const {
    // AOSP nullable-container reader peeks at the -1 sentinel, then rewinds
    // and reads a newly constructed vector, preserving nullable elements.
    const size_t start = dataPosition();
    int32_t n;
    auto s = readInt32(&n);
    if (s)
      return s;
    if (n == -1) {
      v->reset();
      return OK;
    }
    v->emplace(std::vector<std::optional<T>>());
    setDataPosition(start);
    return readVector(&**v);
  }
  template <class T> status_t write(const Flattenable<T> &);
  template <class T> status_t read(Flattenable<T> &) const;
  template <class T> status_t write(const LightFlattenable<T> &v) {
    void *p = writeInplace(v.getFlattenedSize());
    return p ? v.flatten(p, v.getFlattenedSize()) : NO_MEMORY;
  }
  template <class T> status_t read(LightFlattenable<T> &v) const {
    if (!v.isFixedSize())
      return INVALID_OPERATION;
    auto *p = readInplace(v.getFlattenedSize());
    return p ? v.unflatten(p, v.getFlattenedSize()) : NOT_ENOUGH_DATA;
  }
  template <class T> status_t writeData(const T &v) {
    if constexpr (std::is_enum_v<T>)
      return writeData(static_cast<std::underlying_type_t<T>>(v));
    else if constexpr (std::is_same_v<T, bool>)
      return writeBool(v);
    else if constexpr (std::is_integral_v<T> && sizeof(T) <= 4)
      return writeInt32(v);
    else if constexpr (std::is_integral_v<T>)
      return writeInt64(v);
    else if constexpr (std::is_same_v<T, float>)
      return writeFloat(v);
    else if constexpr (std::is_same_v<T, double>)
      return writeDouble(v);
    else if constexpr (std::is_same_v<T, String16>)
      return writeString16(v);
    else if constexpr (std::is_same_v<T, std::string>)
      return writeUtf8AsUtf16(v);
    else
      return writeParcelable(v);
  }
  template <class T> status_t readData(T *v) const {
    if constexpr (std::is_enum_v<T>) {
      std::underlying_type_t<T> x;
      auto s = readData(&x);
      *v = static_cast<T>(x);
      return s;
    } else if constexpr (std::is_integral_v<T> && sizeof(T) <= 4) {
      int32_t x;
      auto s = readInt32(&x);
      *v = static_cast<T>(x);
      return s;
    } else if constexpr (std::is_integral_v<T>) {
      int64_t x;
      auto s = readInt64(&x);
      *v = static_cast<T>(x);
      return s;
    } else if constexpr (std::is_same_v<T, float>)
      return readFloat(v);
    else if constexpr (std::is_same_v<T, double>)
      return readDouble(v);
    else if constexpr (std::is_same_v<T, String16>)
      return readString16(v);
    else if constexpr (std::is_same_v<T, std::string>)
      return readUtf8FromUtf16(v);
    else
      return readParcelable(v);
  }

private:
  // Pinned readOutVectorSizeWithCheck: reject signed overflow and allocations >= 1 MB.
  status_t readOutVectorSizeWithCheck(size_t elementSize, int32_t *size) const {
    auto status = readInt32(size);
    if (status != OK || *size < 0) return status;
    LOG_ALWAYS_FATAL_IF(elementSize > INT32_MAX, "Oversized Parcel vector element");
    int32_t bytes;
    if (__builtin_smul_overflow(static_cast<int32_t>(elementSize), *size, &bytes)
        || bytes >= 1000000) return NO_MEMORY;
    return OK;
  }
  template <class T> status_t readUtf8Vector(std::vector<T> *v) const {
    int32_t size;
    auto status = readInt32(&size);
    if (status != OK) return status;
    if (size < 0) return UNEXPECTED_NULL;
    if (static_cast<size_t>(size) > dataAvail()) return BAD_VALUE;
    v->clear();
    v->resize(static_cast<size_t>(size));
    for (auto &entry : *v) {
      if constexpr (std::is_same_v<T, std::string>) {
        status = readUtf8FromUtf16(&entry);
      } else {
        const size_t position = dataPosition();
        int32_t peek;
        status = readInt32(&peek);
        if (status != OK) return status;
        if (peek == -1) { entry.reset(); continue; }
        setDataPosition(position);
        if constexpr (std::is_same_v<T, std::optional<std::string>>) entry.emplace();
        else entry = std::make_unique<std::string>();
        status = readUtf8FromUtf16(&*entry);
      }
      if (status != OK) return status;
    }
    return OK;
  }
  template <class T> status_t writeNullableUtf8Vector(const T &v) {
    if (!v) return writeInt32(-1);
    auto status = writeVectorSize(*v);
    if (status != OK) return status;
    for (const auto &entry : *v) {
      status = entry ? writeUtf8AsUtf16(*entry) : writeInt32(-1);
      if (status != OK) return status;
    }
    return OK;
  }
  template <class T> status_t readNullableUtf8Vector(T *v) const {
    const size_t position = dataPosition();
    int32_t peek;
    auto status = readInt32(&peek);
    if (status != OK) return status;
    if (peek == -1) { v->reset(); return OK; }
    setDataPosition(position);
    if constexpr (std::is_same_v<T, std::optional<std::vector<std::optional<std::string>>>>)
      v->emplace();
    else *v = std::make_unique<std::vector<std::unique_ptr<std::string>>>();
    return readUtf8Vector(&**v);
  }
  AParcel *mParcel;
  bool mOwned;
};
// AOSP Parcel::write/read(FlattenableHelperInterface): size, fd count, aligned
// native payload, then owned descriptors. Native unflatten takes FD ownership,
// including its error cleanup; the adapter cleans only failed descriptor reads.
template <class T> status_t Parcel::write(const Flattenable<T> &v) {
  const size_t n = v.getFlattenedSize(), count = v.getFdCount();
  if (n > INT32_MAX || count > 64)
    return BAD_VALUE;
  auto s = writeInt32(n);
  if (s)
    return s;
  if ((s = writeInt32(count)))
    return s;
  void *bytes = writeInplace(n);
  if (!bytes)
    return NO_MEMORY;
  std::vector<int> fds(count);
  void *ptr = bytes;
  int *fp = fds.data();
  size_t left = n, fdleft = count;
  if ((s = v.flatten(ptr, left, fp, fdleft)))
    return s;
  for (int fd : fds)
    if ((s = writeDupFileDescriptor(fd)))
      return s;
  return OK;
}
template <class T> status_t Parcel::read(Flattenable<T> &v) const {
  int32_t n, count;
  auto s = readInt32(&n);
  if (s)
    return s;
  if ((s = readInt32(&count)))
    return s;
  if (n < 0 || count < 0 || count > 64)
    return BAD_VALUE;
  const void *bytes = readInplace(n);
  if (!bytes)
    return NOT_ENOUGH_DATA;
  std::vector<int> fds(count, -1);
  for (int i = 0; i < count; i++) {
    int borrowed = readFileDescriptor();
    fds[i] = borrowed < 0 ? -1 : azk_parcel_dupFd(borrowed);
    if (fds[i] < 0) {
      for (int fd : fds)
        if (fd >= 0)
          azk_parcel_closeFd(fd);
      return BAD_VALUE;
    }
  }
  const void *ptr = bytes;
  const int *fp = fds.data();
  size_t left = n, fdleft = count;
  return v.unflatten(ptr, left, fp, fdleft);
}
} // namespace android
