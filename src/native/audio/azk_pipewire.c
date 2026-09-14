#include "azk_pipewire.h"

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct azk_pw_stream {
    struct pw_thread_loop* loop;
    struct pw_stream* stream;
    int sample_rate;
    int channels;
    azk_pw_fill_fn fill;
    void* userdata;
};

static pthread_once_t pw_once = PTHREAD_ONCE_INIT;
static void initialize_pipewire(void) { pw_init(NULL, NULL); }

static void on_process(void* userdata) {
    azk_pw_stream* owner = userdata;
    struct pw_buffer* wrapper = pw_stream_dequeue_buffer(owner->stream);
    if (!wrapper) return;
    struct spa_buffer* buffer = wrapper->buffer;
    float* destination = buffer->datas[0].data;
    if (!destination) {
        pw_stream_queue_buffer(owner->stream, wrapper);
        return;
    }
    const size_t frame_bytes = (size_t)owner->channels * sizeof(float);
    size_t frames = buffer->datas[0].maxsize / frame_bytes;
    if (wrapper->requested && (size_t)wrapper->requested < frames) frames = (size_t)wrapper->requested;
    size_t written = owner->fill(owner->userdata, destination, frames);
    if (written < frames) memset(destination + written * owner->channels, 0, (frames - written) * frame_bytes);
    buffer->datas[0].chunk->offset = 0;
    buffer->datas[0].chunk->stride = (int32_t)frame_bytes;
    buffer->datas[0].chunk->size = (uint32_t)(frames * frame_bytes);
    pw_stream_queue_buffer(owner->stream, wrapper);
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .process = on_process,
};

azk_pw_stream* azk_pw_open(int sample_rate, int channels, azk_pw_fill_fn fill, void* userdata) {
    pthread_once(&pw_once, initialize_pipewire);
    azk_pw_stream* owner = calloc(1, sizeof(*owner));
    if (!owner) return NULL;
    owner->sample_rate = sample_rate;
    owner->channels = channels;
    owner->fill = fill;
    owner->userdata = userdata;
    owner->loop = pw_thread_loop_new("azk-audio", NULL);
    if (!owner->loop || pw_thread_loop_start(owner->loop) < 0) goto fail;

    pw_thread_loop_lock(owner->loop);
    struct pw_properties* properties = pw_properties_new(
            PW_KEY_MEDIA_TYPE, "Audio",
            PW_KEY_MEDIA_CATEGORY, "Playback",
            PW_KEY_MEDIA_ROLE, "Music",
            PW_KEY_APP_NAME, "artzig",
            NULL);
    owner->stream = pw_stream_new_simple(pw_thread_loop_get_loop(owner->loop),
                                         "artzig-audio", properties,
                                         &stream_events, owner);
    if (!owner->stream) {
        pw_thread_loop_unlock(owner->loop);
        goto fail;
    }
    uint8_t pod_buffer[1024];
    struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(pod_buffer, sizeof(pod_buffer));
    struct spa_audio_info_raw info = SPA_AUDIO_INFO_RAW_INIT(
            .format = SPA_AUDIO_FORMAT_F32,
            .rate = (uint32_t)sample_rate,
            .channels = (uint32_t)channels);
    const struct spa_pod* params[] = {
        spa_format_audio_raw_build(&builder, SPA_PARAM_EnumFormat, &info),
    };
    int result = pw_stream_connect(owner->stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
            PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS | PW_STREAM_FLAG_RT_PROCESS |
                    PW_STREAM_FLAG_INACTIVE,
            params, 1);
    pw_thread_loop_unlock(owner->loop);
    if (result < 0) goto fail;
    return owner;

fail:
    if (owner->stream && owner->loop) {
        pw_thread_loop_lock(owner->loop);
        pw_stream_destroy(owner->stream);
        pw_thread_loop_unlock(owner->loop);
    }
    if (owner->loop) {
        pw_thread_loop_stop(owner->loop);
        pw_thread_loop_destroy(owner->loop);
    }
    free(owner);
    return NULL;
}

void azk_pw_set_active(azk_pw_stream* owner, int active) {
    if (!owner || !owner->stream) return;
    pw_thread_loop_lock(owner->loop);
    pw_stream_set_active(owner->stream, active != 0);
    pw_thread_loop_unlock(owner->loop);
}

int64_t azk_pw_pending_frames(azk_pw_stream* owner) {
    if (!owner || !owner->stream) return 0;
    struct pw_time time;
    memset(&time, 0, sizeof(time));
    if (pw_stream_get_time_n(owner->stream, &time, sizeof(time)) < 0) return 0;
    int64_t frames = 0;
    if (time.rate.denom != 0) {
        frames = time.delay * (int64_t)time.rate.num * owner->sample_rate / (int64_t)time.rate.denom;
    }
    frames += time.buffered;
    return frames < 0 ? 0 : frames;
}

void azk_pw_close(azk_pw_stream* owner) {
    if (!owner) return;
    if (owner->stream) {
        pw_thread_loop_lock(owner->loop);
        pw_stream_destroy(owner->stream);
        pw_thread_loop_unlock(owner->loop);
    }
    if (owner->loop) {
        pw_thread_loop_stop(owner->loop);
        pw_thread_loop_destroy(owner->loop);
    }
    free(owner);
}
