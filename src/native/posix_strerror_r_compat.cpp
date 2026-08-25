// artzig: replaces libbase's posix_strerror_r.cpp, which compiles with
// _GNU_SOURCE undefined (breaks libstdc++ headers). glibc exports the XSI
// variant as __xpg_strerror_r; call it directly.
#include <stddef.h>
extern "C" int __xpg_strerror_r(int, char*, size_t);
namespace android {
namespace base {
extern "C" int posix_strerror_r(int errnum, char* buf, size_t buflen) {
    return __xpg_strerror_r(errnum, buf, buflen);
}
}  // namespace base
}  // namespace android
