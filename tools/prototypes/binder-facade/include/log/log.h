#pragma once
// Host stderr backend; fatal RefBase contract checks remain fatal.
#include <cstdio>
#include <cstdlib>
#define ALOGE(...)                \
  do {                            \
    fprintf(stderr, __VA_ARGS__); \
    fputc('\n', stderr);          \
  } while (0)
#define ALOGW(...) ALOGE(__VA_ARGS__)
#define ALOGI(...) ALOGE(__VA_ARGS__)
#define ALOGD(...) ALOGE(__VA_ARGS__)
#define ALOGV(...) ((void)0)
#define LOG_ALWAYS_FATAL(...) \
  do {                        \
    ALOGE(__VA_ARGS__);       \
    abort();                  \
  } while (0)
#define LOG_ALWAYS_FATAL_IF(c, ...)       \
  do {                                    \
    if (c) LOG_ALWAYS_FATAL(__VA_ARGS__); \
  } while (0)
#define ALOG_ASSERT(c, ...) LOG_ALWAYS_FATAL_IF(!(c), __VA_ARGS__)
#define android_errorWriteLog(tag, msg) (ALOGE_HELPER(msg))
inline int ALOGE_HELPER(const char* s) {
  fprintf(stderr, "%s\n", s);
  return 0;
}
#include <cinttypes>
#define ALOGE_IF(c, ...)       \
  do {                         \
    if (c) ALOGE(__VA_ARGS__); \
  } while (0)
