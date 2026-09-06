#pragma once
// The pinned NDK C++ headers call bionic's __assert(file, line, message).
// glibc declares __assert(message, file, line), and binder_status.h's fallback
// macro breaks a subsequent glibc assert.h declaration. Supply the bionic
// overload before those headers and preserve a function, not a statement macro.
#define __assert __assert
#include <cassert>
// glibc hides this declaration when NDEBUG is set.
extern "C" {
void __assert_fail(const char *, const char *, unsigned int,
                   const char *) noexcept __attribute__((__noreturn__));
}
[[noreturn]] inline void __assert(const char *file, int line,
                                  const char *message) {
  __assert_fail(message, file, static_cast<unsigned>(line), "NDK assertion");
}

// Numeric API constants used by the pinned NDK's availability expressions.
#ifndef __ANDROID_API_U__
#define __ANDROID_API_U__ 34
#endif
#ifndef __ANDROID_API_V__
#define __ANDROID_API_V__ 35
#endif
#ifndef __ANDROID_API_B__
#define __ANDROID_API_B__ 36
#endif

#ifndef __ANDROID_API_S__
#define __ANDROID_API_S__ 31
#endif
#ifndef __ANDROID_API_FUTURE__
#define __ANDROID_API_FUTURE__ 10000
#endif

// Pinned libcutils/properties.cpp forwards to the shared Zig property area.
// These bionic C entry points are intentionally absent from glibc headers.
extern "C" int __system_property_get(const char *, char *);
extern "C" int __system_property_set(const char *, const char *);
