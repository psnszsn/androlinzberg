#pragma once
#include <binder/Parcel.h>
namespace android::binder {
// Only transaction status and null exception paths required by IFpsListener.
class Status {
  status_t code = OK;

 public:
  enum { EX_NULL_POINTER = -4 };
  static Status ok() { return {}; }
  static Status fromStatusT(status_t x) {
    Status s;
    s.code = x;
    return s;
  }
  static Status fromExceptionCode(int32_t x) { return fromStatusT(x); }
  void setFromStatusT(status_t x) { code = x; }
  bool isOk() const { return code == OK; }
  status_t writeOverParcel(Parcel* p) const {
    if (!p) return BAD_VALUE;
    p->setDataPosition(0);
    return p->writeInt32(code);
  }
};
}  // namespace android::binder
