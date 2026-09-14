// Relocate immutable Android configuration at the native libc boundary.
// Original AOSP callers retain stat/stdio ordering, results and file ownership.
#include <host_paths.h>
#include <cerrno>
#include <cstdio>
#include <sys/stat.h>

extern "C" FILE *__real_fopen(const char *, const char *);
extern "C" int __real_stat(const char *, struct stat *);

extern "C" FILE *__wrap_fopen(const char *path, const char *mode) {
    if (!path) return __real_fopen(path, mode);
    const char *mapped = azk_host_path(path);
    if (!mapped) { errno = ENOMEM; return nullptr; }
    FILE *result = __real_fopen(mapped, mode);
    const int error = errno;
    azk_host_path_release(path, mapped);
    errno = error;
    return result;
}

extern "C" int __wrap_stat(const char *path, struct stat *out) {
    if (!path) return __real_stat(path, out);
    const char *mapped = azk_host_path(path);
    if (!mapped) { errno = ENOMEM; return -1; }
    const int result = __real_stat(mapped, out);
    const int error = errno;
    azk_host_path_release(path, mapped);
    errno = error;
    return result;
}
