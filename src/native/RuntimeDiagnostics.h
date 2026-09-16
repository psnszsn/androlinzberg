#pragma once
#include <malloc.h>
extern "C" struct mallinfo2 azk_android_mallinfo();
extern "C" int azk_android_mallopt(int, int);
// Original Debug JNI expects Bionic's size_t counters and its allocator totals.
// Include host declarations first, then adapt only this translation unit's ABI.
#ifdef AZK_STOCK_DEBUG_ABI
#define mallinfo mallinfo2
#define mallinfo2(...) azk_android_mallinfo(__VA_ARGS__)
#define mallopt azk_android_mallopt
#define M_LOG_STATS (-205)
#define LoadKernelConfigs LoadDebugStackConfigs
#endif
