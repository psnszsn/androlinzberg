#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct azk_pw_stream azk_pw_stream;
typedef size_t (*azk_pw_fill_fn)(void* userdata, uint8_t* destination, size_t capacity);

azk_pw_stream* azk_pw_open(int sample_rate, int channels, int frame_bytes,
                           azk_pw_fill_fn fill, void* userdata);
void azk_pw_set_active(azk_pw_stream* stream, int active);
void azk_pw_close(azk_pw_stream* stream);

#ifdef __cplusplus
}
#endif
