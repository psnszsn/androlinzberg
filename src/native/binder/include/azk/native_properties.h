#pragma once
#include <stdint.h>
// The pinned SystemProperties JNI uses Bionic's opaque property API. Bind it
// to the existing broker-backed hooks, whose stable handles are shared by NDK
// and Java clients. This header is forced into that original JNI unit and
// included by the patched libbase for its property waits.
struct prop_info;
extern "C" {
const prop_info* bionic___system_property_find(const char*);
void bionic___system_property_read_callback(const prop_info*,
    void (*)(void*, const char*, const char*, uint32_t), void*);
int bionic___system_property_set(const char*, const char*);
bool bionic___system_property_wait(const prop_info*, uint32_t, uint32_t*, const struct timespec*);
}
#define __system_property_find bionic___system_property_find
#define __system_property_read_callback bionic___system_property_read_callback
#define __system_property_set bionic___system_property_set
#define __system_property_wait bionic___system_property_wait
