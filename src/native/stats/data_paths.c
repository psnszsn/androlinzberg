// Stock statsd keeps its state at fixed /data/misc paths. The session's data
// partition is relocated, so resolve that one prefix under ARTZIG_DATA_DIR at
// statsd's file boundary; every other path keeps its libc behavior.
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <sqlite3.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char* resolve(const char* path, char buffer[PATH_MAX]) {
    const char* root = getenv("ARTZIG_DATA_DIR");
    if (path == NULL || root == NULL || strncmp(path, "/data/misc/", strlen("/data/misc/")) != 0) return path;
    return snprintf(buffer, PATH_MAX, "%s%s", root, path + strlen("/data")) < PATH_MAX ? buffer : path;
}

#define HOST(name) ((__typeof__(&name))dlsym(RTLD_NEXT, #name))

int open(const char* path, int flags, ...) {
    va_list arguments;
    va_start(arguments, flags);
    const mode_t mode = (flags & (O_CREAT | O_TMPFILE)) ? va_arg(arguments, mode_t) : 0;
    va_end(arguments);
    char buffer[PATH_MAX];
    return HOST(open)(resolve(path, buffer), flags, mode);
}
DIR* opendir(const char* path) {
    char buffer[PATH_MAX];
    return HOST(opendir)(resolve(path, buffer));
}
int stat(const char* path, struct stat* result) {
    char buffer[PATH_MAX];
    return HOST(stat)(resolve(path, buffer), result);
}
int access(const char* path, int mode) {
    char buffer[PATH_MAX];
    return HOST(access)(resolve(path, buffer), mode);
}
int mkdir(const char* path, mode_t mode) {
    char buffer[PATH_MAX];
    return HOST(mkdir)(resolve(path, buffer), mode);
}
int remove(const char* path) {
    char buffer[PATH_MAX];
    return HOST(remove)(resolve(path, buffer));
}
int rename(const char* from, const char* to) {
    char source[PATH_MAX], target[PATH_MAX];
    return HOST(rename)(resolve(from, source), resolve(to, target));
}
int sqlite3_open(const char* path, sqlite3** database) {
    char buffer[PATH_MAX];
    return HOST(sqlite3_open)(resolve(path, buffer), database);
}
int sqlite3_open_v2(const char* path, sqlite3** database, int flags, const char* vfs) {
    char buffer[PATH_MAX];
    return HOST(sqlite3_open_v2)(resolve(path, buffer), database, flags, vfs);
}
