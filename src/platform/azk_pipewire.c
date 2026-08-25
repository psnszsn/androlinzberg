#include "azk_pipewire.h"

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct azk_pw_stream {
    struct pw_thread_loop* loop;
    struct pw_stream* stream;
    int frame_bytes;
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
    uint8_t* destination = buffer->datas[0].data;
    if (!destination) {
        pw_stream_queue_buffer(owner->stream, wrapper);
        return;
    }
    size_t capacity = buffer->datas[0].maxsize;
    if (wrapper->requested) {
        size_t requested = (size_t)wrapper->requested * (size_t)owner->frame_bytes;
        if (requested < capacity) capacity = requested;
    }
    size_t written = owner->fill(owner->userdata, destination, capacity);
    if (written < capacity) memset(destination + written, 0, capacity - written);
    buffer->datas[0].chunk->offset = 0;
    buffer->datas[0].chunk->stride = owner->frame_bytes;
    buffer->datas[0].chunk->size = (uint32_t)capacity;
    pw_stream_queue_buffer(owner->stream, wrapper);
}

static const struct pw_stream_events stream_events = {
    PW_VERSION_STREAM_EVENTS,
    .process = on_process,
};

azk_pw_stream* azk_pw_open(int sample_rate, int channels, int frame_bytes,
                           azk_pw_fill_fn fill, void* userdata) {
    pthread_once(&pw_once, initialize_pipewire);
    azk_pw_stream* owner = calloc(1, sizeof(*owner));
    if (!owner) return NULL;
    owner->frame_bytes = frame_bytes;
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
            .format = SPA_AUDIO_FORMAT_S16,
            .rate = (uint32_t)sample_rate,
            .channels = (uint32_t)channels);
    const struct spa_pod* params[] = {
        spa_format_audio_raw_build(&builder, SPA_PARAM_EnumFormat, &info),
    };
    int result = pw_stream_connect(owner->stream, PW_DIRECTION_OUTPUT, PW_ID_ANY,
            PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS | PW_STREAM_FLAG_RT_PROCESS,
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
