#pragma once

#include <aaudio/AAudio.h>
#include <system/audio.h>
#include <utils/RefBase.h>
#include <atomic>
#include <cstddef>

namespace artzig {

// AOSP ToneGenerator::initAudioTrack needs callback PCM output, a sample rate
// and a session. Native AudioTrack requires AudioFlinger, so this boundary owns
// that operation over the existing hosted AAudio/stock Java AudioTrack stack.
class ToneOutput : public android::RefBase {
public:
    struct Buffer {
        void* samples;
        size_t bytes;
        void* data() const { return samples; }
        size_t size() const { return bytes; }
    };
    class IAudioTrackCallback : public android::RefBase {
    public:
        virtual size_t onMoreData(const Buffer&) = 0;
    protected:
        ~IAudioTrackCallback() override = default;
    };

    ToneOutput(IAudioTrackCallback* callback, audio_stream_type_t stream);
    ~ToneOutput() override;
    bool isInited() const { return mStream != nullptr; }
    uint32_t getSampleRate() const { return AAudioStream_getSampleRate(mStream); }
    int getSessionId() const { return AAudioStream_getSessionId(mStream); }
    void setVolume(float volume);
    void start();
    void stop();
    static float linearToLog(int volume);

private:
    static aaudio_data_callback_result_t fill(AAudioStream*, void*, void*, int32_t);
    // The synthesizer owns the output until its destructor has stopped it.
    // AAudioStream_close joins its callback worker before this pointer expires.
    IAudioTrackCallback* const mCallback;
    AAudioStream* mStream = nullptr;
    float mVolume = 1.0f;
    bool mCallbackStop = false;
    std::atomic<bool> mStopping{false};
    static thread_local ToneOutput* sCallbackOutput;
};

} // namespace artzig
