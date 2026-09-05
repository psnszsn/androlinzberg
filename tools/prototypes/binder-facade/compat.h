#pragma once
#define __INTRODUCED_IN(x)
#define __INTRODUCED_IN_APEX(x)
#define __INTRODUCED_IN_32(x)
#define __INTRODUCED_IN_64(x)
#define __RENAME(x)
#define __ANDROID_API__ 35
#define LIBBINDER_EXPORTED

#define __assert __assert

#include <cassert>
#include <cstdlib>
[[noreturn]] inline void __assert(const char*, int, const char*) { ::abort(); }

#include <cstring>
#include <optional>
#define _Nonnull
#define _Nullable
#include <cmath>
