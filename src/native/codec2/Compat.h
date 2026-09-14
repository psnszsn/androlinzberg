#pragma once
// Pinned Codec2 relies on bionic headers to expose the Android API-level
// declarations transitively. Supply those same declarations with host libc.
#undef __INTRODUCED_IN
#include <android/versioning.h>
#include <android/api-level.h>
#include <sys/ioctl.h>
#include <binder/Parcel.h>
#include <binder/unique_fd.h>
