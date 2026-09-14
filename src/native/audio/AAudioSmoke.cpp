// Test registration only: dev.artzig.StockAAudioSmoke drives the stock AAudio
// C ABI (libaaudio legacy path over the stock AudioTrack client and the host
// audio services) through builder validation, a blocking-write stream and a
// data-callback stream.
#include <aaudio/AAudio.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <thread>

namespace {
// Returns the failing line, or 0; the island is built without exceptions.
#define CHECK(condition)                         \
    do {                                         \
        if (!(condition)) return __LINE__;       \
    } while (0)

struct CallbackState {
    std::atomic<int> count{0};
    std::atomic<bool> bad{false};
};

aaudio_data_callback_result_t fill(AAudioStream* stream, void* userData, void* audioData, int32_t numFrames) {
    auto* state = static_cast<CallbackState*>(userData);
    if (stream == nullptr || audioData == nullptr || numFrames <= 0) {
        state->bad = true;
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    memset(audioData, 0, static_cast<size_t>(numFrames) * AAudioStream_getChannelCount(stream) * sizeof(int16_t));
    state->count++;
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void failed(AAudioStream*, void* userData, aaudio_result_t) {
    static_cast<CallbackState*>(userData)->bad = true;
}

template <typename Predicate>
bool poll(Predicate predicate) {
    for (int i = 0; i < 1000; i++) {
        if (predicate()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    return false;
}

bool settle(AAudioStream* stream, aaudio_stream_state_t inputState, aaudio_stream_state_t expected) {
    aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
    return AAudioStream_waitForStateChange(stream, inputState, &next, 2000000000LL) == AAUDIO_OK && next == expected;
}

int smoke() {
    CHECK(AAudioStreamBuilder_delete(nullptr) == AAUDIO_ERROR_NULL);
    AAudioStreamBuilder* builder = nullptr;
    CHECK(AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder != nullptr);
    AAudioStream* stream = reinterpret_cast<AAudioStream*>(1);
    AAudioStreamBuilder_setSampleRate(builder, 1);
    CHECK(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_ERROR_INVALID_RATE && stream == nullptr);
    AAudioStreamBuilder_setSampleRate(builder, 8000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, 64);
    // No input device: the stock record path reports its failure and opens nothing.
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    CHECK(AAudioStreamBuilder_openStream(builder, &stream) < 0 && stream == nullptr);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    CHECK(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream != nullptr);
    CHECK(AAudioStream_getSessionId(stream) == AAUDIO_SESSION_ID_NONE);
    CHECK(AAudioStream_getDeviceId(stream) > 0);

    AAudioStreamBuilder_setSessionId(builder, -2);
    AAudioStream* sessionStream = nullptr;
    CHECK(AAudioStreamBuilder_openStream(builder, &sessionStream) < 0 && sessionStream == nullptr);
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
    CHECK(AAudioStreamBuilder_openStream(builder, &sessionStream) == AAUDIO_OK);
    const int32_t session = AAudioStream_getSessionId(sessionStream);
    CHECK(session > 0 && AAudioStream_close(sessionStream) == AAUDIO_OK);
    AAudioStreamBuilder_setSessionId(builder, session);
    CHECK(AAudioStreamBuilder_openStream(builder, &sessionStream) == AAUDIO_OK);
    CHECK(AAudioStream_getSessionId(sessionStream) == session && AAudioStream_close(sessionStream) == AAUDIO_OK);
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_NONE);

    CHECK(AAudioStream_getState(stream) == AAUDIO_STREAM_STATE_OPEN);
    CHECK(AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_I16);
    CHECK(AAudioStream_getSampleRate(stream) == 8000 && AAudioStream_getChannelCount(stream) == 1);
    CHECK(AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_SHARED);
    CHECK(AAudioStream_getPerformanceMode(stream) == AAUDIO_PERFORMANCE_MODE_NONE);
    const int32_t burst = AAudioStream_getFramesPerBurst(stream);
    CHECK(burst > 0 && AAudioStream_getBufferCapacityInFrames(stream) >= burst);
    CHECK(AAudioStream_setBufferSizeInFrames(stream, 1) > 0);
    aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
    CHECK(AAudioStream_waitForStateChange(stream, AAUDIO_STREAM_STATE_OPEN, &next, 0) == AAUDIO_ERROR_TIMEOUT);
    CHECK(next == AAUDIO_STREAM_STATE_OPEN);
    CHECK(AAudioStream_waitForStateChange(stream, AAUDIO_STREAM_STATE_UNINITIALIZED, nullptr, 0) == AAUDIO_OK);
    int64_t position = -1;
    int64_t timestamp = -1;
    CHECK(AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &position, &timestamp) < 0);

    int16_t pcm[160];
    memset(pcm, 0, sizeof(pcm));
    const int32_t written = AAudioStream_write(stream, pcm, 160, 0);
    CHECK(written > 0 && written <= 160);
    CHECK(AAudioStream_getFramesWritten(stream) == written && AAudioStream_getFramesRead(stream) == 0);
    CHECK(AAudioStream_requestStart(stream) == AAUDIO_OK);
    CHECK(AAudioStream_getDeviceId(stream) > 0);
    CHECK(AAudioStream_requestStart(stream) == AAUDIO_ERROR_INVALID_STATE);
    CHECK(settle(stream, AAUDIO_STREAM_STATE_STARTING, AAUDIO_STREAM_STATE_STARTED));
    // Blocking writes keep the stock legacy stream fed while the host output drains it.
    for (int i = 0; i < 20; i++) CHECK(AAudioStream_write(stream, pcm, 160, 500000000LL) == 160);
    CHECK(poll([&] { return AAudioStream_getFramesRead(stream) >= burst; }));
    CHECK(AAudioStream_requestFlush(stream) == AAUDIO_ERROR_INVALID_STATE);
    CHECK(AAudioStream_requestPause(stream) == AAUDIO_OK);
    CHECK(settle(stream, AAUDIO_STREAM_STATE_PAUSING, AAUDIO_STREAM_STATE_PAUSED));
    CHECK(AAudioStream_requestFlush(stream) == AAUDIO_OK);
    CHECK(settle(stream, AAUDIO_STREAM_STATE_FLUSHING, AAUDIO_STREAM_STATE_FLUSHED));
    CHECK(AAudioStream_requestStop(stream) == AAUDIO_OK);
    CHECK(settle(stream, AAUDIO_STREAM_STATE_STOPPING, AAUDIO_STREAM_STATE_STOPPED));
    CHECK(AAudioStream_close(stream) == AAUDIO_OK);

    CallbackState state;
    AAudioStream* callbackStream = nullptr;
    AAudioStreamBuilder_setDataCallback(builder, fill, &state);
    AAudioStreamBuilder_setErrorCallback(builder, failed, &state);
    CHECK(AAudioStreamBuilder_openStream(builder, &callbackStream) == AAUDIO_OK);
    CHECK(AAudioStream_setBufferSizeInFrames(callbackStream, 160) > 0);
    CHECK(AAudioStream_requestStart(callbackStream) == AAUDIO_OK);
    CHECK(AAudioStream_write(callbackStream, pcm, 0, 0) == AAUDIO_ERROR_INVALID_STATE);
    // The first timestamps report position 0 until the output presents frames.
    CHECK(poll([&] {
        return (AAudioStream_getTimestamp(callbackStream, CLOCK_MONOTONIC, &position, &timestamp) == AAUDIO_OK &&
                position > 0) || state.bad.load();
    }));
    CHECK(position > 0 && timestamp > 0);
    CHECK(state.count.load() > 0 && !state.bad.load());
    CHECK(AAudioStream_requestStop(callbackStream) == AAUDIO_OK);
    CHECK(settle(callbackStream, AAUDIO_STREAM_STATE_STOPPING, AAUDIO_STREAM_STATE_STOPPED));
    CHECK(AAudioStream_close(callbackStream) == AAUDIO_OK);
    CHECK(AAudioStreamBuilder_delete(builder) == AAUDIO_OK);
    return 0;
}

void runNative(JNIEnv* env, jclass) {
    const int line = smoke();
    if (line != 0) {
        const std::string message = "AAudio contract failed at AAudioSmoke.cpp:" + std::to_string(line);
        jniThrowException(env, "java/lang/AssertionError", message.c_str());
    }
}
}  // namespace

extern "C" int azk_register_aaudio_smoke(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"runNative", "()V", reinterpret_cast<void*>(runNative)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/StockAAudioSmoke", methods, 1);
}
