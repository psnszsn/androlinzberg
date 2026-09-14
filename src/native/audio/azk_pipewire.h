#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct azk_pw_stream azk_pw_stream;
// Produce up to `frames` interleaved float frames; return how many were written.
typedef size_t (*azk_pw_fill_fn)(void* userdata, float* destination, size_t frames);

azk_pw_stream* azk_pw_open(int sample_rate, int channels, azk_pw_fill_fn fill, void* userdata);
void azk_pw_set_active(azk_pw_stream* stream, int active);
// Frames handed to PipeWire that the sink has not yet presented.
int64_t azk_pw_pending_frames(azk_pw_stream* stream);
void azk_pw_close(azk_pw_stream* stream);

#ifdef __cplusplus
}
#endif
