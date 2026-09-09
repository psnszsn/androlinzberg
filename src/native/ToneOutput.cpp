#include "ToneOutput.h"
#include <cmath>
#include <chrono>
#include <cstring>
#include <log/log.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <mutex>
#include <unordered_map>
#include <vector>

extern "C" void azk_aaudio_set_legacy_stream(AAudioStreamBuilder*, int32_t);

namespace artzig {

namespace {
// Explicit test observation of the PCM output boundary, independent of the
// synthesizer's private sequencer state. Production playback allocates no trace.
struct Capture {
    jlong stream, rate, written = 0, read = 0, live = 1, stopped = 0;
    std::vector<int16_t> pcm;
};
std::mutex captureMutex;
std::unordered_map<int, Capture> captures;
std::atomic<bool> captureEnabled{false};

void captureOpened(AAudioStream* stream, audio_stream_type_t type) {
    if (!captureEnabled.load()) return;
    std::lock_guard lock(captureMutex);
    captures.emplace(AAudioStream_getSessionId(stream), Capture{type, AAudioStream_getSampleRate(stream)});
}
void captureOutput(AAudioStream* stream, const int16_t* samples, size_t count, bool stopped) {
    if (!captureEnabled.load()) return;
    const auto session = AAudioStream_getSessionId(stream);
    const auto written = AAudioStream_getFramesWritten(stream);
    const auto read = AAudioStream_getFramesRead(stream);
    std::lock_guard lock(captureMutex);
    auto found = captures.find(session);
    if (found == captures.end()) return;
    auto& capture = found->second;
    capture.written = written;
    capture.read = read;
    capture.stopped = stopped;
    if (samples != nullptr && capture.pcm.size() + count <= 192000)
        capture.pcm.insert(capture.pcm.end(), samples, samples + count);
}
void beginCapture(JNIEnv* env, jclass) {
    std::lock_guard lock(captureMutex);
    for (const auto& [id, capture] : captures) {
        if (capture.live) {
            jniThrowRuntimeException(env, "Tone output still active");
            return;
        }
    }
    captures.clear();
    captureEnabled.store(true);
}
jshortArray captureSamples(JNIEnv* env, jclass, jint session) {
    std::lock_guard lock(captureMutex);
    auto found = captures.find(session);
    if (found == captures.end()) return nullptr;
    const auto& samples = found->second.pcm;
    auto result = env->NewShortArray(samples.size());
    if (result != nullptr && !samples.empty())
        env->SetShortArrayRegion(result, 0, samples.size(), samples.data());
    return result;
}
jlongArray captureStatus(JNIEnv* env, jclass, jint session) {
    std::lock_guard lock(captureMutex);
    auto found = captures.find(session);
    if (found == captures.end()) return nullptr;
    const auto& c = found->second;
    const jlong values[] = {c.stream, c.rate, c.written, c.read, c.live, c.stopped};
    auto result = env->NewLongArray(6);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}
} // namespace

extern "C" int azk_register_tone_smoke(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"beginCapture", "()V", reinterpret_cast<void*>(beginCapture)},
        {"samples", "(I)[S", reinterpret_cast<void*>(captureSamples)},
        {"status", "(I)[J", reinterpret_cast<void*>(captureStatus)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/StockToneSmoke", methods, 3);
}

thread_local ToneOutput* ToneOutput::sCallbackOutput = nullptr;

ToneOutput::ToneOutput(IAudioTrackCallback* callback, audio_stream_type_t stream)
    : mCallback(callback) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return;
    // ToneGenerator uses mono PCM16 and 20 ms synthesis blocks. The existing
    // desktop output defaults to 48 kHz; query the opened stream below as usual.
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, 960);
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    azk_aaudio_set_legacy_stream(builder, stream);
    AAudioStreamBuilder_setDataCallback(builder, fill, this);
    const auto status = AAudioStreamBuilder_openStream(builder, &mStream);
    AAudioStreamBuilder_delete(builder);
    if (status != AAUDIO_OK) {
        mStream = nullptr;
        ALOGE("ToneGenerator output initialization failed: %d", status);
    } else captureOpened(mStream, stream);
}

ToneOutput::~ToneOutput() {
    if (mStream != nullptr) {
        const auto session = AAudioStream_getSessionId(mStream);
        AAudioStream_close(mStream);
        if (captureEnabled.load()) {
            std::lock_guard lock(captureMutex);
            auto found = captures.find(session);
            if (found != captures.end()) found->second.live = 0;
        }
    }
}

void ToneOutput::start() {
    if (mStream == nullptr) return;
    // The synth signals its waiter before AAudio consumes the STOP result.
    // A quick replacement must wait for that transition before restarting.
    if (mStopping.load()) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        auto state = AAudioStream_getState(mStream);
        while (state != AAUDIO_STREAM_STATE_STOPPED) {
            if (std::chrono::steady_clock::now() >= deadline) return;
            if (AAudioStream_waitForStateChange(mStream, state, &state, 100000000) != AAUDIO_OK &&
                state == AAUDIO_STREAM_STATE_DISCONNECTED) return;
        }
        mStopping.store(false);
    }
    AAudioStream_requestStart(mStream);
}

void ToneOutput::stop() {
    // AOSP stops AudioTrack from onMoreData. AAudio forbids control calls from
    // a data callback; its STOP result performs the same transition afterward.
    if (sCallbackOutput == this) {
        mStopping.store(true);
        mCallbackStop = true;
    }
    else if (mStream != nullptr) AAudioStream_requestStop(mStream);
}

void ToneOutput::setVolume(float volume) {
    // AudioTrack::setVolume rejects values outside [0,1], retaining old volume.
    if (volume >= 0 && volume <= 1) mVolume = volume;
}

float ToneOutput::linearToLog(int volume) {
    // Pinned AudioSystem::linearToLog. AudioSystem.cpp also requires the absent
    // AudioFlinger/policy native clients; retain its exact 0.5 dB step conversion.
    constexpr float conversion = -0.5f * 2.302585093f / 20.0f;
    return volume ? std::exp(float(100 - volume) * conversion) : 0;
}

aaudio_data_callback_result_t ToneOutput::fill(AAudioStream*, void* userdata,
                                               void* samples, int32_t frames) {
    auto& output = *static_cast<ToneOutput*>(userdata);
    sCallbackOutput = &output;
    output.mCallbackStop = false;
    const size_t bytes = size_t(frames) * sizeof(int16_t);
    const size_t written = output.mCallback->onMoreData({samples, bytes});
    sCallbackOutput = nullptr;
    if (output.mCallbackStop || written > bytes) {
        captureOutput(output.mStream, nullptr, 0, true);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    std::memset(static_cast<char*>(samples) + written, 0, bytes - written);
    auto* pcm = static_cast<int16_t*>(samples);
    for (int32_t i = 0; i < frames; ++i) pcm[i] = int16_t(pcm[i] * output.mVolume);
    captureOutput(output.mStream, pcm, frames, false);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

} // namespace artzig
