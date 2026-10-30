// Declarations stock statsd and libsysutils reach transitively through Bionic
// and Android's libc++ headers.
#pragma once
#include <android/api-level.h>
#include <fcntl.h>
#include <signal.h>
#ifdef __cplusplus
#include <functional>
#endif
