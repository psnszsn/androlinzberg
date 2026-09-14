#include <binder/Parcel.h>
#include <binder/Status.h>
#include <binder/IPCThreadState.h>
#include <cerrno>
#include <fcntl.h>
#include <utils/Unicode.h>
#include <cutils/ashmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cerrno>
namespace android {
// Pinned Parcel::setData replaces bytes and releases embedded object/FD
// ownership, with position zero and exact (un-padded) length. AZKB owns storage.
status_t Parcel::setData(const uint8_t* data, size_t size) {
  if (size > INT32_MAX) return BAD_VALUE;
  return AParcel_unmarshal(mParcel, data, size);
}

// Pinned Parcel's byte-array wire format and exception-header decoding.
status_t Parcel::writeByteArray(size_t len, const uint8_t* val) {
  if (len > INT32_MAX) return BAD_VALUE;
  if (!val) return writeInt32(-1);
  status_t ret = writeInt32(static_cast<uint32_t>(len));
  if (ret == NO_ERROR) ret = write(val, len * sizeof(*val));
  return ret;
}
// Pinned Parcel.cpp Blob lifecycle and wire format. Only mAllowFds is mapped
// to the shared Zig Parcel accessor; descriptor transfer remains in writeFileDescriptor.
// ashmem-host.cpp is the original AOSP host backing for descriptor-sized blobs.
static constexpr size_t BLOB_INPLACE_LIMIT = 16 * 1024;
static constexpr int32_t BLOB_INPLACE = 0;
static constexpr int32_t BLOB_ASHMEM_IMMUTABLE = 1;
static constexpr int32_t BLOB_ASHMEM_MUTABLE = 2;

status_t Parcel::writeBlob(size_t len, bool mutableCopy, WritableBlob* outBlob)
{
#ifdef BINDER_DISABLE_BLOB
    (void)len;
    (void)mutableCopy;
    (void)outBlob;
    return INVALID_OPERATION;
#else
    if (len > INT32_MAX) {
        // don't accept size_t values which may have come from an
        // inadvertent conversion from a negative int.
        return BAD_VALUE;
    }

    status_t status;
    if (!allowFds() || len <= BLOB_INPLACE_LIMIT) {
        ALOGV("writeBlob: write in place");
        status = writeInt32(BLOB_INPLACE);
        if (status) return status;

        void* ptr = writeInplace(len);
        if (!ptr) return NO_MEMORY;

        outBlob->init(-1, ptr, len, false);
        return NO_ERROR;
    }

    ALOGV("writeBlob: write to ashmem");
    int fd = ashmem_create_region("Parcel Blob", len);
    if (fd < 0) return NO_MEMORY;

    int result = ashmem_set_prot_region(fd, PROT_READ | PROT_WRITE);
    if (result < 0) {
        status = result;
    } else {
        void* ptr = ::mmap(nullptr, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) {
            status = -errno;
        } else {
            if (!mutableCopy) {
                result = ashmem_set_prot_region(fd, PROT_READ);
            }
            if (result < 0) {
                status = result;
            } else {
                status = writeInt32(mutableCopy ? BLOB_ASHMEM_MUTABLE : BLOB_ASHMEM_IMMUTABLE);
                if (!status) {
                    status = writeFileDescriptor(fd, true /*takeOwnership*/);
                    if (!status) {
                        outBlob->init(fd, ptr, len, mutableCopy);
                        return NO_ERROR;
                    }
                }
            }
        }
        if (::munmap(ptr, len) == -1) {
            ALOGW("munmap() failed: %s", strerror(errno));
        }
    }
    ::close(fd);
    return status;
#endif
}

status_t Parcel::writeDupImmutableBlobFileDescriptor(int fd)
{
    // Must match up with what's done in writeBlob.
    if (!allowFds()) return FDS_NOT_ALLOWED;
    status_t status = writeInt32(BLOB_ASHMEM_IMMUTABLE);
    if (status) return status;
    return writeDupFileDescriptor(fd);
}

status_t Parcel::readBlob(size_t len, ReadableBlob* outBlob) const
{
#ifdef BINDER_DISABLE_BLOB
    (void)len;
    (void)outBlob;
    return INVALID_OPERATION;
#else
    int32_t blobType;
    status_t status = readInt32(&blobType);
    if (status) return status;

    if (blobType == BLOB_INPLACE) {
        ALOGV("readBlob: read in place");
        const void* ptr = readInplace(len);
        if (!ptr) return BAD_VALUE;

        outBlob->init(-1, const_cast<void*>(ptr), len, false);
        return NO_ERROR;
    }

    ALOGV("readBlob: read from ashmem");
    bool isMutable = (blobType == BLOB_ASHMEM_MUTABLE);
    int fd = readFileDescriptor();
    if (fd == int(BAD_TYPE)) return BAD_VALUE;

    if (!ashmem_valid(fd)) {
        ALOGE("invalid fd");
        return BAD_VALUE;
    }
    int size = ashmem_get_size_region(fd);
    if (size < 0 || size_t(size) < len) {
        ALOGE("request size %zu does not match fd size %d", len, size);
        return BAD_VALUE;
    }
    void* ptr = ::mmap(nullptr, len, isMutable ? PROT_READ | PROT_WRITE : PROT_READ,
            MAP_SHARED, fd, 0);
    if (ptr == MAP_FAILED) return NO_MEMORY;

    outBlob->init(fd, ptr, len, isMutable);
    return NO_ERROR;
#endif
}

Parcel::Blob::Blob() :
        mFd(-1), mData(nullptr), mSize(0), mMutable(false) {
}

Parcel::Blob::~Blob() {
    release();
}

void Parcel::Blob::release() {
    if (mFd != -1 && mData) {
        if (::munmap(mData, mSize) == -1) {
            ALOGW("munmap() failed: %s", strerror(errno));
        }
    }
    clear();
}

void Parcel::Blob::init(int fd, void* data, size_t size, bool isMutable) {
    mFd = fd;
    mData = data;
    mSize = size;
    mMutable = isMutable;
}

void Parcel::Blob::clear() {
    mFd = -1;
    mData = nullptr;
    mSize = 0;
    mMutable = false;
}

Parcel::Parcel() : mParcel(AParcel_create()), mOwned(true) {}
Parcel::Parcel(AParcel *p) : mParcel(p), mOwned(false) {}
Parcel::~Parcel() {
  if (mOwned)
    azk_parcel_destroyOwned(mParcel);
}
Parcel *Parcel::forNative(AParcel *p) {
  if (!p)
    return nullptr;
  return static_cast<Parcel *>(azk_parcel_cppView(
      p, [](AParcel *n) -> void * { return new Parcel(n); },
      [](void *n) { delete static_cast<Parcel *>(n); }));
}
// AOSP Parcel::writeNoException/readExceptionCode delegate to binder::Status.
status_t Parcel::writeNoException() {
  binder::Status status;
  return status.writeToParcel(this);
}
int32_t Parcel::readExceptionCode() const {
  binder::Status status;
  status.readFromParcel(*this);
  return status.exceptionCode();
}
void Parcel::markSensitive() const { AParcel_markSensitive(mParcel); }
bool Parcel::allowFds() const { return AParcel_getAllowFds(mParcel); }
status_t Parcel::write(const void *src, size_t n) {
  if (!src && n)
    return BAD_VALUE;
  void *dst = writeInplace(n);
  if (!dst && n)
    return errorCheck();
  if (n)
    memcpy(dst, src, n);
  return OK;
}
status_t Parcel::read(void *dst, size_t n) const {
  if (!dst && n)
    return BAD_VALUE;
  auto *src = readInplace(n);
  if (!src && n)
    return NOT_ENOUGH_DATA;
  if (n)
    memcpy(dst, src, n);
  return OK;
}
status_t Parcel::writeString8(const char *s, size_t n) {
  if (!s)
    return writeInt32(-1);
  if (n > INT32_MAX - 1)
    return BAD_VALUE;
  auto rc = writeInt32(n);
  if (rc)
    return rc;
  auto *p = static_cast<char *>(writeInplace(n + 1));
  if (!p)
    return NO_MEMORY;
  memcpy(p, s, n);
  p[n] = 0;
  return OK;
}
status_t Parcel::readString8(String8 *out) const {
  int32_t n;
  auto s = readInt32(&n);
  if (s)
    return s;
  if (n < 0) {
    out->clear();
    return UNEXPECTED_NULL;
  }
  auto *p = static_cast<const char *>(readInplace(size_t(n) + 1));
  if (!p || p[n])
    return BAD_VALUE;
  out->setTo(p, n);
  return OK;
}
status_t Parcel::writeString16(const char16_t *s, size_t n) {
  if (!s)
    return writeInt32(-1);
  if (n > (INT32_MAX - 2) / 2)
    return BAD_VALUE;
  auto rc = writeInt32(n);
  if (rc)
    return rc;
  auto *p = static_cast<char16_t *>(writeInplace((n + 1) * 2));
  if (!p)
    return NO_MEMORY;
  memcpy(p, s, n * 2);
  p[n] = 0;
  return OK;
}
status_t Parcel::readString16(std::optional<String16> *out) const {
  int32_t n;
  auto s = readInt32(&n);
  if (s)
    return s;
  if (n == -1) {
    out->reset();
    return OK;
  }
  if (n < 0 || n > (INT32_MAX - 2) / 2)
    return BAD_VALUE;
  auto *p = static_cast<const char16_t *>(readInplace((size_t(n) + 1) * 2));
  if (!p || p[n])
    return BAD_VALUE;
  out->emplace(p, n);
  return OK;
}
status_t Parcel::readString16(String16 *out) const {
  std::optional<String16> s;
  auto rc = readString16(&s);
  if (rc)
    return rc;
  if (!s)
    return UNEXPECTED_NULL;
  *out = std::move(*s);
  return OK;
}
// Source: pinned Parcel::{readString16Inplace,writeUtf8AsUtf16,
// readUtf8FromUtf16,readData,writeData}. Keep the stock Unicode conversion,
// nullable framing and partial-read mutation over the shared Zig storage.
const char16_t *Parcel::readString16Inplace(size_t *length) const {
  const int32_t size = readInt32();
  if (size >= 0 && size < INT32_MAX) {
    const auto *str = static_cast<const char16_t *>(
        readInplace((size_t(size) + 1) * sizeof(char16_t)));
    if (str && str[size] == u'\0') {
      *length = size;
      return str;
    }
    if (str)
      android_errorWriteLog(0x534e4554, "172655291");
  }
  *length = 0;
  return nullptr;
}
status_t Parcel::writeUtf8AsUtf16(const std::string &str) {
  const auto *bytes = reinterpret_cast<const uint8_t *>(str.data());
  const ssize_t length = utf8_to_utf16_length(bytes, str.size());
  if (length < 0 || length > INT32_MAX)
    return BAD_VALUE;
  const auto status = writeInt32(length);
  if (status != OK)
    return status;
  auto *dst = static_cast<char16_t *>(
      writeInplace((size_t(length) + 1) * sizeof(char16_t)));
  if (!dst)
    return NO_MEMORY;
  utf8_to_utf16(bytes, str.size(), dst, size_t(length) + 1);
  return OK;
}
status_t Parcel::readUtf8FromUtf16(std::string *str) const {
  size_t length;
  const char16_t *src = readString16Inplace(&length);
  if (!src)
    return UNEXPECTED_NULL;
  if (length == 0) {
    str->clear();
    return OK;
  }
  const ssize_t bytes = utf16_to_utf8_length(src, length) + 1;
  if (bytes < 1)
    return BAD_VALUE;
  str->resize(bytes);
  utf16_to_utf8(src, length, str->data(), bytes);
  str->resize(bytes - 1);
  return OK;
}
namespace {
template <class T, class Read>
status_t readNullableUtf8(const Parcel &parcel, std::optional<T> *out,
                          Read read) {
  const size_t position = parcel.dataPosition();
  int32_t peek;
  const auto status = parcel.readInt32(&peek);
  if (status != OK)
    return status;
  if (peek == -1) {
    out->reset();
    return OK;
  }
  out->emplace(T());
  parcel.setDataPosition(position);
  return read(&**out);
}
template <class T, class Read>
status_t readNullableUtf8(const Parcel &parcel, std::unique_ptr<T> *out,
                          Read read) {
  const size_t position = parcel.dataPosition();
  int32_t peek;
  const auto status = parcel.readInt32(&peek);
  if (status != OK)
    return status;
  if (peek == -1) {
    out->reset();
    return OK;
  }
  T *value = new (std::nothrow) T;
  if (!value)
    return NO_MEMORY;
  out->reset(value);
  parcel.setDataPosition(position);
  return read(out->get());
}
template <class T>
status_t writeUtf8Vector(Parcel &parcel, const std::vector<T> &values) {
  if (values.size() > INT32_MAX)
    return BAD_VALUE;
  auto status = parcel.writeInt32(values.size());
  if (status != OK)
    return status;
  for (const auto &value : values) {
    status = parcel.writeUtf8AsUtf16(value);
    if (status != OK)
      return status;
  }
  return OK;
}
template <class T>
status_t readUtf8Vector(const Parcel &parcel, std::vector<T> *values) {
  int32_t size;
  auto status = parcel.readInt32(&size);
  if (status != OK)
    return status;
  if (size < 0)
    return UNEXPECTED_NULL;
  if (size_t(size) > parcel.dataAvail())
    return BAD_VALUE;
  values->clear();
  values->resize(size);
  for (auto &value : *values) {
    status = parcel.readUtf8FromUtf16(&value);
    if (status != OK)
      return status;
  }
  return OK;
}
} // namespace
status_t Parcel::writeUtf8AsUtf16(const std::optional<std::string> &str) {
  return str ? writeUtf8AsUtf16(*str) : writeInt32(-1);
}
status_t Parcel::writeUtf8AsUtf16(const std::unique_ptr<std::string> &str) {
  return str ? writeUtf8AsUtf16(*str) : writeInt32(-1);
}
status_t Parcel::readUtf8FromUtf16(std::optional<std::string> *str) const {
  return readNullableUtf8(
      *this, str, [this](auto *value) { return readUtf8FromUtf16(value); });
}
status_t Parcel::readUtf8FromUtf16(std::unique_ptr<std::string> *str) const {
  return readNullableUtf8(
      *this, str, [this](auto *value) { return readUtf8FromUtf16(value); });
}
status_t
Parcel::writeUtf8VectorAsUtf16Vector(const std::vector<std::string> &v) {
  return writeUtf8Vector(*this, v);
}
status_t Parcel::writeUtf8VectorAsUtf16Vector(
    const std::optional<std::vector<std::optional<std::string>>> &v) {
  return v ? writeUtf8Vector(*this, *v) : writeInt32(-1);
}
status_t Parcel::writeUtf8VectorAsUtf16Vector(
    const std::unique_ptr<std::vector<std::unique_ptr<std::string>>> &v) {
  return v ? writeUtf8Vector(*this, *v) : writeInt32(-1);
}
status_t
Parcel::readUtf8VectorFromUtf16Vector(std::vector<std::string> *v) const {
  return readUtf8Vector(*this, v);
}
status_t Parcel::readUtf8VectorFromUtf16Vector(
    std::optional<std::vector<std::optional<std::string>>> *v) const {
  return readNullableUtf8(
      *this, v, [this](auto *values) { return readUtf8Vector(*this, values); });
}
status_t Parcel::readUtf8VectorFromUtf16Vector(
    std::unique_ptr<std::vector<std::unique_ptr<std::string>>> *v) const {
  return readNullableUtf8(
      *this, v, [this](auto *values) { return readUtf8Vector(*this, values); });
}
const char *Parcel::readCString() const {
  if (!dataAvail())
    return nullptr;
  auto *start = reinterpret_cast<const char *>(data() + dataPosition());
  auto *end = static_cast<const char *>(memchr(start, 0, dataAvail()));
  if (!end)
    return nullptr;
  return static_cast<const char *>(readInplace(end - start + 1));
}
// Follows pinned Parcel::writeInterfaceToken/enforceInterface. The AZKB
// storage and transaction state own the host seam; all callers share SYST.
status_t Parcel::writeInterfaceToken(const String16 &d) {
  return writeInterfaceToken(d.c_str(), d.size());
}
status_t Parcel::writeInterfaceToken(const char16_t *d, size_t len) {
  auto *state = IPCThreadState::self();
  writeInt32(state->getStrictModePolicy() | (1 << 31)); // PENALTY_GATHER
  azk_parcel_noteRequestHeader(mParcel);
  writeInt32(state->shouldPropagateWorkSource() ? state->getCallingWorkSourceUid()
                                              : IPCThreadState::kUnsetWorkSource);
  writeInt32(0x53595354);
  return writeString16(d, len);
}
bool Parcel::enforceInterface(const String16 &d, IPCThreadState *state) const {
  return enforceInterface(d.c_str(), d.size(), state);
}
bool Parcel::enforceInterface(const char16_t *d, size_t len, IPCThreadState *state) const {
  if (!state) state = IPCThreadState::self();
  int32_t policy = readInt32();
  state->setStrictModePolicy((state->getLastTransactionBinderFlags() & IBinder::FLAG_ONEWAY) ? 0 : policy);
  azk_parcel_noteRequestHeader(mParcel);
  state->setCallingWorkSourceUidWithoutPropagation(readInt32());
  if (readInt32() != 0x53595354) return false;
  size_t gotLen;
  const char16_t *got = readString16Inplace(&gotLen);
  return got && gotLen == len && (!len || memcmp(got, d, len * sizeof(char16_t)) == 0);
}
// Pinned Parcel comparison and string validation, operating on AZKB bytes.
int Parcel::compareData(const Parcel &other) const {
  if (dataSize() != other.dataSize()) return dataSize() < other.dataSize() ? -1 : 1;
  return dataSize() ? memcmp(data(), other.data(), dataSize()) : 0;
}
status_t Parcel::compareDataInRange(size_t offset, const Parcel &other,
                                  size_t otherOffset, size_t len, int *result) const {
  if (len > INT32_MAX || offset > INT32_MAX || otherOffset > INT32_MAX ||
      offset > dataSize() || len > dataSize() - offset ||
      otherOffset > other.dataSize() || len > other.dataSize() - otherOffset) return BAD_VALUE;
  *result = len ? memcmp(data() + offset, other.data() + otherOffset, len) : 0;
  return OK;
}
const char *Parcel::readString8Inplace(size_t *outLen) const {
  int32_t size = readInt32();
  if (size >= 0 && size < INT32_MAX) {
    const char *str = static_cast<const char *>(readInplace(size + 1));
    if (str && str[size] == 0) { *outLen = size; return str; }
  }
  *outLen = 0;
  return nullptr;
}
size_t Parcel::getOpenAshmemSize() const {
  size_t result = 0;
  for (size_t i = 0; i < objectsCount(); ++i) {
    int fd = azk_parcel_ownedFd(mParcel, i);
    if (fd < 0 || !ashmem_valid(fd)) continue;
    int size = ashmem_get_size_region(fd);
    if (size < 0) continue;
    if (__builtin_add_overflow(result, static_cast<size_t>(size), &result)) return SIZE_MAX;
  }
  return result;
}
status_t Parcel::writeStrongBinder(const sp<IBinder> &b) {
  auto *n = b ? b->acquireNative() : nullptr;
  auto s = AParcel_writeStrongBinder(mParcel, n);
  if (n)
    AIBinder_decStrong(n);
  return s;
}
status_t Parcel::readNullableStrongBinder(sp<IBinder> *b) const {
  AIBinder *n = nullptr;
  auto s = AParcel_readStrongBinder(mParcel, &n);
  if (s)
    return s;
  *b = IBinder::fromNative(n);
  return OK;
}
status_t Parcel::readUniqueFileDescriptor(base::unique_fd *out) const {
  int fd = readFileDescriptor();
  if (fd < 0)
    return fd;
  int dup = azk_parcel_dupFd(fd);
  if (dup < 0)
    return BAD_VALUE;
  out->reset(dup);
  return OK;
}
// Pinned ParcelFileDescriptor framing includes a reliable-channel marker,
// unlike the NDK's raw descriptor entry. Keep that protocol at this API seam.
status_t Parcel::writeDupParcelFileDescriptor(int fd) {
  base::unique_fd duplicate(fcntl(fd, F_DUPFD_CLOEXEC, 0));
  if (duplicate.get() < 0)
    return -errno;
  auto status = writeInt32(0);
  return status ? status : writeDupFileDescriptor(duplicate.get());
}
int Parcel::readParcelFileDescriptor() const {
  const int32_t hasComm = readInt32();
  const int fd = readFileDescriptor();
  if (hasComm &&
      azk_parcel_detachParcelFileDescriptor(readFileDescriptor()) != OK)
    return BAD_TYPE;
  return fd;
}
status_t Parcel::readUniqueParcelFileDescriptor(base::unique_fd *out) const {
  const int fd = readParcelFileDescriptor();
  if (fd == BAD_TYPE)
    return BAD_TYPE;
  const int duplicate = azk_parcel_dupFd(fd);
  if (duplicate < 0)
    return BAD_VALUE;
  out->reset(duplicate);
  return OK;
}
status_t Parcel::writeNativeHandle(const native_handle_t *h) {
  if (!h || h->version != sizeof(native_handle_t) || h->numFds < 0 ||
      h->numFds > 64 || h->numInts < 0 || h->numInts > 4096)
    return BAD_VALUE;
  auto s = writeInt32(h->numFds);
  if (s)
    return s;
  if ((s = writeInt32(h->numInts)))
    return s;
  for (int i = 0; i < h->numFds; i++)
    if ((s = writeDupFileDescriptor(h->data[i])))
      return s;
  return write(h->data + h->numFds, h->numInts * sizeof(int));
}
native_handle_t *Parcel::readNativeHandle() const {
  int32_t f, n;
  if (readInt32(&f) || readInt32(&n) || f < 0 || f > 64 || n < 0 || n > 4096)
    return nullptr;
  auto *h = native_handle_create(f, n);
  if (!h)
    return nullptr;
  std::fill(h->data, h->data + f, -1);
  for (int i = 0; i < f; i++) {
    int fd = readFileDescriptor();
    if (fd < 0 || (h->data[i] = azk_parcel_dupFd(fd)) < 0) {
      native_handle_close(h);
      native_handle_delete(h);
      return nullptr;
    }
  }
  if (read(h->data + f, n * sizeof(int))) {
    native_handle_close(h);
    native_handle_delete(h);
    return nullptr;
  }
  return h;
}
} // namespace android

// Pinned binder_libbinder.h language views retain the AParcel owner's lifetime.
android::Parcel *AParcel_viewPlatformParcel(AParcel *parcel) {
  return android::Parcel::forNative(parcel);
}
const android::Parcel *AParcel_viewPlatformParcel(const AParcel *parcel) {
  return android::Parcel::forNative(const_cast<AParcel *>(parcel));
}
