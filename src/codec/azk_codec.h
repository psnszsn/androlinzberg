#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AzkCodecDecoder AzkCodecDecoder;
AzkCodecDecoder *azk_codec_open(const char *name);
int azk_codec_configure(AzkCodecDecoder *, const uint8_t *csd, int size,
                        int sample_rate, int channels);
int azk_codec_send(AzkCodecDecoder *, const uint8_t *, int size, int64_t pts_us,
                   int eos);
void azk_codec_flush(AzkCodecDecoder *);
void azk_codec_close(AzkCodecDecoder *);

// Frame indices are independent of PTS, including duplicate timestamps. An
// active index is unique until completion or flush. Submit EOS separately with
// azk_codec_send after sending any encoded data carried by the final work.
int azk_codec_send_work(AzkCodecDecoder *, const uint8_t *, int size,
                        int64_t pts_us, uint64_t frame_index);
// Audio: 0 = frame, 1 = retry, 2 = EOF; -2 = insufficient capacity (metadata
// reports the required size without consuming the frame). meta = bytes/rate/channels.
// Video: 3 = owned AzkHardwareFrame, 1 = retry, 2 = EOF. meta = 0/width/height/dataspace.
// Both return -4 for an output lacking decoder-propagated work identity.
int azk_codec_receive_audio_work(AzkCodecDecoder *, uint8_t *dst, int capacity,
                                 int *meta, int64_t *pts_us, uint64_t *frame_index);
int azk_codec_receive_video_work(AzkCodecDecoder *, int *meta, int64_t *pts_us,
                                 uintptr_t *hardware_frame, uint64_t *frame_index);
// 1 = one completed input work, 0 = none. All outputs must first be consumed.
// Flush discards pending completions as well as queued output.
int azk_codec_poll_completed_work(AzkCodecDecoder *, uint64_t *frame_index);

#ifdef __cplusplus
}
#endif
