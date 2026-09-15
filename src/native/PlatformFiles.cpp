// Relocate immutable Android configuration at the native libc boundary.
// Original AOSP callers retain stat/stdio ordering, results and file ownership.
#include <host_paths.h>
#include <cerrno>
#include <cstdio>
#include <sys/stat.h>
#include <dirent.h>
#include <ft2build.h>
#include FT_FREETYPE_H

extern "C" FILE *__real_fopen(const char *, const char *);
extern "C" int __real_stat(const char *, struct stat *);
extern "C" DIR *__real_opendir(const char *);
extern "C" FT_Error __real_FT_Open_Face(FT_Library, const FT_Open_Args *, FT_Long, FT_Face *);

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

extern "C" DIR *__wrap_opendir(const char *path) {
    if (!path) return __real_opendir(path);
    const char *mapped = azk_host_path(path);
    if (!mapped) { errno = ENOMEM; return nullptr; }
    DIR *result = __real_opendir(mapped);
    const int error = errno;
    azk_host_path_release(path, mapped);
    errno = error;
    return result;
}

// PDFium's original Android font manager passes /system/fonts paths to the
// system FreeType DSO, whose internal fopen cannot use this ELF's link wrapper.
extern "C" FT_Error __wrap_FT_Open_Face(FT_Library library, const FT_Open_Args *args,
        FT_Long index, FT_Face *face) {
    if (!args || !(args->flags & FT_OPEN_PATHNAME) || !args->pathname)
        return __real_FT_Open_Face(library, args, index, face);
    const char *mapped = azk_host_path(args->pathname);
    if (!mapped) return FT_Err_Out_Of_Memory;
    FT_Open_Args relocated = *args;
    relocated.pathname = const_cast<char *>(mapped);
    const FT_Error result = __real_FT_Open_Face(library, &relocated, index, face);
    azk_host_path_release(args->pathname, mapped);
    return result;
}
