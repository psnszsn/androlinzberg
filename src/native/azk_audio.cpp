// artzig: AudioTrack backend — PipeWire stream with a real-time null-sink
// fallback (headless CI has no audio server; the clock must still advance).
#include <jni.h>
#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <mutex>
#include <vector>

#include <android/log.h>
#define ALOG(...) __android_log_print(ANDROID_LOG_INFO, "azk-audio", __VA_ARGS__)

namespace {

uint64_t now_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000000000ull + ts.tv_nsec;
}

struct Stream {
    int sample_rate = 48000;
    int channels = 2;
    int frame_bytes = 4;  // s16 * channels
    float volume = 1.0f;

    // ring buffer of raw PCM
    std::mutex mu;
    std::vector<uint8_t> ring;
    size_t head = 0, tail = 0, filled = 0;

    std::atomic<uint64_t> frames_played{0};
    std::atomic<uint64_t> played_at_ns{0};
    std::atomic<bool> playing{false};
    std::atomic<uint32_t> underruns{0};

    // pipewire (null when in fallback mode)
    pw_thread_loop* loop = nullptr;
    pw_stream* pw = nullptr;

    // fallback clock state
    uint64_t fb_last_ns = 0;

    size_t write(const uint8_t* data, size_t len) {
        std::lock_guard<std::mutex> lock(mu);
        size_t space = ring.size() - filled;
        size_t n = len < space ? len : space;
        for (size_t i = 0; i < n; i++) {
            ring[tail] = data[i];
            tail = (tail + 1) % ring.size();
        }
        filled += n;
        return n;
    }

    size_t read(uint8_t* dst, size_t len) {
        std::lock_guard<std::mutex> lock(mu);
        size_t n = len < filled ? len : filled;
        for (size_t i = 0; i < n; i++) {
            dst[i] = ring[head];
            head = (head + 1) % ring.size();
        }
        filled -= n;
        return n;
    }

    void flush() {
        std::lock_guard<std::mutex> lock(mu);
        head = tail = filled = 0;
    }

    // advance the fallback clock: consume frames at real-time rate
    void fallbackTick() {
        if (loop != nullptr) return;  // real sink drives the clock
        uint64_t t = now_ns();
        if (!playing.load()) { fb_last_ns = t; return; }
        if (fb_last_ns == 0) fb_last_ns = t;
        uint64_t due = (t - fb_last_ns) * sample_rate / 1000000000ull;
        if (due == 0) return;
        std::lock_guard<std::mutex> lock(mu);
        uint64_t avail = filled / frame_bytes;
        uint64_t n = due < avail ? due : avail;
        head = (head + size_t(n) * frame_bytes) % ring.size();
        filled -= size_t(n) * frame_bytes;
        frames_played += n;
        played_at_ns = t;
        fb_last_ns = t;
        if (n < due && avail > 0) underruns++;
    }
};

void on_process(void* userdata) {
    Stream* s = static_cast<Stream*>(userdata);
    pw_buffer* b = pw_stream_dequeue_buffer(s->pw);
    if (!b) return;
    spa_buffer* buf = b->buffer;
    uint8_t* dst = static_cast<uint8_t*>(buf->datas[0].data);
    if (!dst) { pw_stream_queue_buffer(s->pw, b); return; }
    size_t max = buf->datas[0].maxsize;
    if (b->requested) {
        size_t want = size_t(b->requested) * s->frame_bytes;
        if (want < max) max = want;
    }
    size_t n = s->playing.load() ? s->read(dst, max) : 0;
    if (n < max) {
        memset(dst + n, 0, max - n);
        if (s->playing.load() && n == 0) s->underruns++;
    }
    // apply volume on s16 samples
    if (s->volume < 0.999f) {
        int16_t* p = reinterpret_cast<int16_t*>(dst);
        for (size_t i = 0; i < n / 2; i++) p[i] = int16_t(p[i] * s->volume);
    }
    s->frames_played += n / s->frame_bytes;
    s->played_at_ns = now_ns();
    buf->datas[0].chunk->offset = 0;
    buf->datas[0].chunk->stride = s->frame_bytes;
    buf->datas[0].chunk->size = uint32_t(max);
    pw_stream_queue_buffer(s->pw, b);
}

const pw_stream_events kStreamEvents = {
    .version = PW_VERSION_STREAM_EVENTS,
    .process = on_process,
};

bool pw_open(Stream* s) {
    static std::once_flag once;
    std::call_once(once, [] { pw_init(nullptr, nullptr); });
    s->loop = pw_thread_loop_new("azk-audio", nullptr);
    if (!s->loop) return false;
    pw_thread_loop_start(s->loop);
    pw_thread_loop_lock(s->loop);
    pw_properties* props = pw_properties_new(
            PW_KEY_MEDIA_TYPE, "Audio",
            PW_KEY_MEDIA_CATEGORY, "Playback",
            PW_KEY_MEDIA_ROLE, "Music",
            PW_KEY_APP_NAME, "androlinzberg",
            nullptr);
    s->pw = pw_stream_new_simple(pw_thread_loop_get_loop(s->loop), "androlinzberg-audio",
                                 props, &kStreamEvents, s);
    if (!s->pw) {
        pw_thread_loop_unlock(s->loop);
        pw_thread_loop_stop(s->loop);
        pw_thread_loop_destroy(s->loop);
        s->loop = nullptr;
        return false;
    }
    uint8_t pod_buf[1024];
    spa_pod_builder pb = SPA_POD_BUILDER_INIT(pod_buf, sizeof(pod_buf));
    spa_audio_info_raw info{};
    info.format = SPA_AUDIO_FORMAT_S16;
    info.rate = uint32_t(s->sample_rate);
    info.channels = uint32_t(s->channels);
    const spa_pod* params[1] = { spa_format_audio_raw_build(&pb, SPA_PARAM_EnumFormat, &info) };
    int res = pw_stream_connect(s->pw, PW_DIRECTION_OUTPUT, PW_ID_ANY,
            pw_stream_flags(PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS |
                            PW_STREAM_FLAG_RT_PROCESS),
            params, 1);
    pw_thread_loop_unlock(s->loop);
    if (res < 0) {
        pw_thread_loop_lock(s->loop);
        pw_stream_destroy(s->pw);
        pw_thread_loop_unlock(s->loop);
        pw_thread_loop_stop(s->loop);
        pw_thread_loop_destroy(s->loop);
        s->pw = nullptr;
        s->loop = nullptr;
        return false;
    }
    return true;
}

Stream* handle(jlong h) { return reinterpret_cast<Stream*>(h); }

jlong AudioTrack_nativeOpen(JNIEnv*, jclass, jint sampleRate, jint channels) {
    Stream* s = new Stream();
    s->sample_rate = sampleRate;
    s->channels = channels;
    s->frame_bytes = 2 * channels;
    s->ring.resize(size_t(s->frame_bytes) * sampleRate);  // 1s of audio
    if (pw_open(s)) {
        ALOG("pipewire stream open: %d Hz, %d ch", sampleRate, channels);
    } else {
        ALOG("pipewire unavailable, using null sink: %d Hz, %d ch", sampleRate, channels);
    }
    return reinterpret_cast<jlong>(s);
}

jint AudioTrack_nativeWrite(JNIEnv* env, jclass, jlong h, jobject buf, jint offset, jint size) {
    Stream* s = handle(h);
    s->fallbackTick();
    uint8_t* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!base) return -1;
    return jint(s->write(base + offset, size_t(size)));
}

jint AudioTrack_nativeWriteArray(JNIEnv* env, jclass, jlong h, jbyteArray arr, jint offset, jint size) {
    Stream* s = handle(h);
    s->fallbackTick();
    jbyte* base = env->GetByteArrayElements(arr, nullptr);
    jint n = jint(s->write(reinterpret_cast<uint8_t*>(base) + offset, size_t(size)));
    env->ReleaseByteArrayElements(arr, base, JNI_ABORT);
    return n;
}

void AudioTrack_nativePlay(JNIEnv*, jclass, jlong h) {
    Stream* s = handle(h);
    s->fb_last_ns = now_ns();
    s->playing = true;
    if (s->pw) {
        pw_thread_loop_lock(s->loop);
        pw_stream_set_active(s->pw, true);
        pw_thread_loop_unlock(s->loop);
    }
}

void AudioTrack_nativePause(JNIEnv*, jclass, jlong h) {
    Stream* s = handle(h);
    s->fallbackTick();
    s->playing = false;
    if (s->pw) {
        pw_thread_loop_lock(s->loop);
        pw_stream_set_active(s->pw, false);
        pw_thread_loop_unlock(s->loop);
    }
}

void AudioTrack_nativeFlush(JNIEnv*, jclass, jlong h) { handle(h)->flush(); }

jlong AudioTrack_nativeGetPosition(JNIEnv*, jclass, jlong h) {
    Stream* s = handle(h);
    s->fallbackTick();
    return jlong(s->frames_played.load());
}

jlong AudioTrack_nativeGetPositionNanos(JNIEnv*, jclass, jlong h) {
    return jlong(handle(h)->played_at_ns.load());
}

jint AudioTrack_nativeGetUnderruns(JNIEnv*, jclass, jlong h) {
    return jint(handle(h)->underruns.load());
}

void AudioTrack_nativeSetVolume(JNIEnv*, jclass, jlong h, jfloat v) {
    handle(h)->volume = v;
}

void AudioTrack_nativeRelease(JNIEnv*, jclass, jlong h) {
    Stream* s = handle(h);
    s->playing = false;
    if (s->pw) {
        pw_thread_loop_lock(s->loop);
        pw_stream_destroy(s->pw);
        pw_thread_loop_unlock(s->loop);
        pw_thread_loop_stop(s->loop);
        pw_thread_loop_destroy(s->loop);
    }
    delete s;
}

const JNINativeMethod kMethods[] = {
    { "nativeOpen", "(II)J", (void*)AudioTrack_nativeOpen },
    { "nativeWrite", "(JLjava/nio/ByteBuffer;II)I", (void*)AudioTrack_nativeWrite },
    { "nativeWriteArray", "(J[BII)I", (void*)AudioTrack_nativeWriteArray },
    { "nativePlay", "(J)V", (void*)AudioTrack_nativePlay },
    { "nativePause", "(J)V", (void*)AudioTrack_nativePause },
    { "nativeFlush", "(J)V", (void*)AudioTrack_nativeFlush },
    { "nativeGetPosition", "(J)J", (void*)AudioTrack_nativeGetPosition },
    { "nativeGetPositionNanos", "(J)J", (void*)AudioTrack_nativeGetPositionNanos },
    { "nativeGetUnderruns", "(J)I", (void*)AudioTrack_nativeGetUnderruns },
    { "nativeSetVolume", "(JF)V", (void*)AudioTrack_nativeSetVolume },
    { "nativeRelease", "(J)V", (void*)AudioTrack_nativeRelease },
};

}  // namespace

extern "C" int azk_register_audio(JNIEnv* env) {
    jclass cls = env->FindClass("android/media/AudioTrack");
    if (!cls) return -1;
    return env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
}
