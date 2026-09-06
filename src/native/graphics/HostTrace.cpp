// SurfaceFlinger's tracing facade on the host. Follow tracing_perfetto.cpp's
// atrace branch; the Android Perfetto service is not part of this process.
#include <tracing_perfetto.h>
#include <cutils/trace.h>
#include <cstdarg>
#include <cstdio>

namespace tracing_perfetto {
void registerWithPerfetto(bool) {}
void traceBegin(uint64_t tag, const char *name) { atrace_begin(tag, name); }
void traceEnd(uint64_t tag) { atrace_end(tag); }
void traceAsyncBegin(uint64_t tag, const char *name, int32_t cookie) {
  atrace_async_begin(tag, name, cookie);
}
void traceAsyncEnd(uint64_t tag, const char *name, int32_t cookie) {
  atrace_async_end(tag, name, cookie);
}
void traceAsyncBeginForTrack(uint64_t tag, const char *name, const char *track,
                             int32_t cookie) {
  atrace_async_for_track_begin(tag, track, name, cookie);
}
void traceAsyncEndForTrack(uint64_t tag, const char *track, int32_t cookie) {
  atrace_async_for_track_end(tag, track, cookie);
}
void traceInstant(uint64_t tag, const char *name) { atrace_instant(tag, name); }
void traceInstantForTrack(uint64_t tag, const char *track, const char *name) {
  atrace_instant_for_track(tag, track, name);
}
void traceCounter(uint64_t tag, const char *name, int64_t value) {
  atrace_int64(tag, name, value);
}
void traceCounter32(uint64_t tag, const char *name, int32_t value) {
  atrace_int(tag, name, value);
}
bool isTagEnabled(uint64_t tag) { return atrace_is_tag_enabled(tag); }
void traceFormatBegin(uint64_t tag, const char *format, ...) {
  if (!isTagEnabled(tag))
    return;
  char buffer[256];
  va_list args;
  va_start(args, format);
  vsnprintf(buffer, sizeof(buffer), format, args);
  va_end(args);
  traceBegin(tag, buffer);
}
void traceFormatInstant(uint64_t tag, const char *format, ...) {
  if (!isTagEnabled(tag))
    return;
  char buffer[256];
  va_list args;
  va_start(args, format);
  vsnprintf(buffer, sizeof(buffer), format, args);
  va_end(args);
  traceInstant(tag, buffer);
}
} // namespace tracing_perfetto
