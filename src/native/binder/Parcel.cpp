#include <binder/Parcel.h>
namespace android {
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
