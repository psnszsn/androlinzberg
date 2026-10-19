#pragma once
#include <cerrno>
#include <unistd.h>
// This host glibc pidfd header omits its C++ linkage guard and tests _PIDFD_H
// without defining it. Import the real declarations as C and complete its
// guard before the original JNI includes it again (2.44 adds pidfd_info).
extern "C" {
#include <sys/pidfd.h>
}
#ifndef _PIDFD_H
#define _PIDFD_H 1
#endif
extern "C" int azk_process_uid_change(uid_t);
extern "C" int azk_process_gid_change(gid_t);
// Share libcore's immutable identity decision. Real namespace children still
// perform the host kernel setters, with their original errno contract.
inline int azk_process_setuid(uid_t uid) {
    const int decision = azk_process_uid_change(uid);
    if (decision == 0) return setuid(uid);
    if (decision > 0) return 0;
    errno = -decision;
    return -1;
}
inline int azk_process_setgid(gid_t gid) {
    const int decision = azk_process_gid_change(gid);
    if (decision == 0) return setgid(gid);
    if (decision > 0) return 0;
    errno = -decision;
    return -1;
}
#define setuid azk_process_setuid
#define setgid azk_process_setgid
