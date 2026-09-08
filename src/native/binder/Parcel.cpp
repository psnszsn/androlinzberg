#include <binder/Parcel.h>
#include <binder/Status.h>
#include <utils/Unicode.h>
#include <cutils/ashmem.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cerrno>
namespace android {
// Pinned Parcel's byte-array wire format and exception-header decoding.
status_t Parcel::writeByteArray(size_t len, const uint8_t* val) {
  if (len > INT32_MAX) return BAD_VALUE;
  if (!val) return writeInt32(-1);
  status_t ret = writeInt32(static_cast<uint32_t>(len));
  if (ret == NO_ERROR) ret = write(val, len * sizeof(*val));
  return ret;
}
int32_t Parcel::readExceptionCode() const {
  binder::Status status;
  status.readFromParcel(*this);
  return status.exceptionCode();
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

// Pinned Parcel::writeNoException delegates to the stock exception header writer.
status_t Parcel::writeNoException() {
  binder::Status status;
  return status.writeToParcel(this);
}
// Pinned Parcel UTF conversion and readString16Inplace failure semantics over
// the shared storage. String8/String16 convenience constructors hide failures.
status_t Parcel::writeUtf8AsUtf16(const std::string &s) {
  const auto *data = reinterpret_cast<const uint8_t *>(s.data());
  const ssize_t length = utf8_to_utf16_length(data, s.size());
  if (length < 0 || length > INT32_MAX) return BAD_VALUE;
  auto status = writeInt32(static_cast<int32_t>(length));
  if (status != OK) return status;
  auto *out = static_cast<char16_t *>(writeInplace((size_t(length) + 1) * sizeof(char16_t)));
  if (!out) return NO_MEMORY;
  utf8_to_utf16(data, s.size(), out, size_t(length) + 1);
  return OK;
}
status_t Parcel::readUtf8FromUtf16(std::string *s) const {
  const int32_t length = readInt32();
  if (length < 0 || length == INT32_MAX) return UNEXPECTED_NULL;
  const auto *data = static_cast<const char16_t *>(
      readInplace((size_t(length) + 1) * sizeof(char16_t)));
  if (!data || data[length] != u'\0') return UNEXPECTED_NULL;
  if (length == 0) { s->clear(); return OK; }
  const ssize_t bytes = utf16_to_utf8_length(data, size_t(length)) + 1;
  if (bytes < 1) return BAD_VALUE;
  s->resize(size_t(bytes));
  utf16_to_utf8(data, size_t(length), s->data(), size_t(bytes));
  s->resize(size_t(bytes) - 1);
  return OK;
}
Parcel::Parcel() : mParcel(AParcel_create()), mOwned(true) {}
Parcel::Parcel(AParcel *p) : mParcel(p), mOwned(false) {}
Parcel::~Parcel() {
  if (mOwned)
    AParcel_delete(mParcel);
}
Parcel *Parcel::forNative(AParcel *p) {
  if (!p)
    return nullptr;
  return static_cast<Parcel *>(azk_parcel_cppView(
      p, [](AParcel *n) -> void * { return new Parcel(n); },
      [](void *n) { delete static_cast<Parcel *>(n); }));
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
const char *Parcel::readCString() const {
  if (!dataAvail())
    return nullptr;
  auto *start = reinterpret_cast<const char *>(data() + dataPosition());
  auto *end = static_cast<const char *>(memchr(start, 0, dataAvail()));
  if (!end)
    return nullptr;
  return static_cast<const char *>(readInplace(end - start + 1));
}
status_t Parcel::writeInterfaceToken(const String16 &d) {
  auto s = writeInt32(0);
  if (s)
    return s;
  if ((s = writeInt32(-1)))
    return s;
  if ((s = writeInt32(0x53595354)))
    return s;
  return writeString16(d);
}
bool Parcel::enforceInterface(const String16 &d, IPCThreadState *) const {
  int32_t policy, work, header;
  if (readInt32(&policy) || readInt32(&work) || readInt32(&header) ||
      header != 0x53595354)
    return false;
  String16 got;
  return readString16(&got) == OK && got == d;
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
