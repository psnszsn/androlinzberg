// AudioFlinger PlaybackThread::Track / TrackHandle (frameworks/av/services/
// audioflinger/Tracks.cpp) and the mixer-thread track handling of
// MixerThread::prepareTracks_l (Threads.cpp) for one track over one host sink
// stream. The stock files cannot be compiled: they belong to the HAL-backed
// mixer thread model. The control block protocol is the pinned
// AudioTrackServerProxy; PipeWire resamples and mixes the per-track streams.
#include "HostAudio.h"

#include <audio_utils/minifloat.h>
#include <audio_utils/primitives.h>
#include <audio_utils/roundup.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <media/AidlConversion.h>
#include <media/AudioResamplerPublic.h>
#include <media/AudioSystem.h>
#include <media/ShmemCompat.h>
#include <utils/Log.h>
#include <utils/Timers.h>

#include <linux/futex.h>
#include <sys/syscall.h>

#include <algorithm>
#include <cmath>

#include "azk_pipewire.h"

#undef LOG_TAG
#define LOG_TAG "HostAudioTrack"

namespace artzig::audio {
using namespace android;
using android::binder::Status;

namespace {
// Threads.cpp: consecutive mixer cycles a track may underrun before it is disabled.
constexpr int32_t kMaxTrackRetries = 50;
constexpr int32_t kMaxTrackStartupRetries = 50;

Status statusFromT(status_t status) { return binderStatusFromStatusT(status); }
}  // namespace

// ---------------------------------------------------------------------------
// HostAudioSink

std::unique_ptr<HostAudioSink> HostAudioSink::open(uint32_t sampleRate, uint32_t channels, Fill fill, void* cookie) {
    std::unique_ptr<HostAudioSink> sink(new HostAudioSink(sampleRate, channels, fill, cookie));
    sink->mStream = azk_pw_open(static_cast<int>(sampleRate), static_cast<int>(channels), fill, cookie);
    if (sink->mStream == nullptr) {
        // audio.stub behavior: consume frames in real time without a device.
        ALOGW("PipeWire unavailable; rendering %u Hz/%u ch track to a null sink", sampleRate, channels);
        sink->mThread = std::thread([sink = sink.get()] { sink->nullSinkLoop(); });
    }
    return sink;
}

HostAudioSink::HostAudioSink(uint32_t sampleRate, uint32_t channels, Fill fill, void* cookie)
    : mSampleRate(sampleRate), mChannels(channels), mFill(fill), mCookie(cookie) {}

HostAudioSink::~HostAudioSink() {
    if (mStream != nullptr) {
        azk_pw_close(mStream);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mLock);
        mExit = true;
    }
    mCondition.notify_all();
    if (mThread.joinable()) mThread.join();
}

void HostAudioSink::setActive(bool active) {
    if (mStream != nullptr) {
        azk_pw_set_active(mStream, active ? 1 : 0);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mLock);
        mActive = active;
    }
    mCondition.notify_all();
}

int64_t HostAudioSink::pendingFrames() {
    return mStream != nullptr ? azk_pw_pending_frames(mStream) : 0;
}

void HostAudioSink::nullSinkLoop() {
    std::vector<float> scratch(kOutputFrameCount * mChannels);
    const auto period = std::chrono::nanoseconds(1000000000LL * kOutputFrameCount / mSampleRate);
    auto next = std::chrono::steady_clock::now();
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(mLock);
            mCondition.wait(lock, [this] { return mActive || mExit; });
            if (mExit) return;
        }
        mFill(mCookie, scratch.data(), kOutputFrameCount);
        next += period;
        std::this_thread::sleep_until(next);
        if (std::chrono::steady_clock::now() - next > period) next = std::chrono::steady_clock::now();
    }
}

// ---------------------------------------------------------------------------
// HostAudioTrack

sp<HostAudioTrack> HostAudioTrack::create(HostAudioFlinger& flinger, const IAudioFlinger::CreateTrackInput& input,
                                          IAudioFlinger::CreateTrackOutput& output, audio_stream_type_t streamType,
                                          audio_port_handle_t portId, float portVolume, bool portMuted,
                                          status_t* status) {
    // PlaybackThread::createTrack_l for a MIXER thread.
    const uint32_t sampleRate = input.config.sample_rate != 0 ? input.config.sample_rate : kOutputSampleRate;
    output.sampleRate = sampleRate;
    const audio_format_t format = input.config.format;
    const audio_channel_mask_t channelMask = input.config.channel_mask;
    const uint32_t channelCount = audio_channel_count_from_out_mask(channelMask);
    audio_output_flags_t flags = input.flags;
    size_t frameCount = input.frameCount;
    size_t notificationFrameCount = input.notificationFrameCount;
    const sp<IMemory>& sharedBuffer = input.sharedBuffer;

    if ((flags & AUDIO_OUTPUT_FLAG_FAST) != 0) {
        ALOGD("AUDIO_OUTPUT_FLAG_FAST denied: no fast mixer on this output");
        flags = static_cast<audio_output_flags_t>(flags & ~AUDIO_OUTPUT_FLAG_FAST);
    }
    if (!audio_is_linear_pcm(format) || !audio_is_valid_format(format)) {
        ALOGE("createTrack_l() Bad parameter: format %#x", format);
        *status = BAD_VALUE;
        return nullptr;
    }
    if (channelCount == 0 || channelCount > FCC_LIMIT || !audio_is_output_channel(channelMask)) {
        ALOGE("createTrack_l() Bad parameter: channelMask %#x", channelMask);
        *status = BAD_VALUE;
        return nullptr;
    }
    if (sampleRate == 0 || sampleRate > kOutputSampleRate * AUDIO_RESAMPLER_DOWN_RATIO_MAX) {
        ALOGE("createTrack_l() Bad parameter: sampleRate %u", sampleRate);
        *status = BAD_VALUE;
        return nullptr;
    }
    const size_t frameSize = audio_bytes_per_frame(channelCount, format);
    if (sharedBuffer != nullptr) {
        if (sharedBuffer->size() == 0 || sharedBuffer->size() % frameSize != 0 ||
            sharedBuffer->unsecurePointer() == nullptr) {
            ALOGE("createTrack_l() Bad parameter: shared buffer size %zu", sharedBuffer->size());
            *status = BAD_VALUE;
            return nullptr;
        }
        frameCount = sharedBuffer->size() / frameSize;
    } else {
        const size_t minFrameCount = AudioSystem::calculateMinFrameCount(
                kOutputLatencyMs, kOutputFrameCount, kOutputSampleRate, sampleRate, input.speed);
        if (frameCount < minFrameCount) frameCount = minFrameCount;
    }
    if (sharedBuffer == nullptr) {
        const int nBuffering =
                (uint64_t{frameCount} * kOutputSampleRate) / (uint64_t{kOutputFrameCount} * sampleRate) == 3 ? 3 : 2;
        size_t maxNotificationFrames = frameCount / nBuffering;
        if ((input.flags & AUDIO_OUTPUT_FLAG_FAST) != 0) {
            const size_t maxNotificationFramesFastDenied = 20 * sampleRate / 1000;
            maxNotificationFrames = std::min(maxNotificationFrames, maxNotificationFramesFastDenied);
        }
        if (notificationFrameCount == 0 || notificationFrameCount > maxNotificationFrames) {
            notificationFrameCount = maxNotificationFrames;
        }
    }
    output.flags = flags;
    output.frameCount = frameCount;
    output.notificationFrameCount = notificationFrameCount;

    sp<HostAudioTrack> track(new HostAudioTrack(flinger, streamType, portId, input.sessionId, sampleRate, format,
                                                channelMask, frameCount, portVolume, portMuted));
    // TrackBase: the control block, followed by the ring buffer for streaming tracks.
    const size_t bufferSize = sharedBuffer != nullptr ? 0 : roundup(frameCount) * frameSize;
    const size_t size = sizeof(android::audio_track_cblk_t) + bufferSize;
    sp<MemoryHeapBase> heap = sp<MemoryHeapBase>::make(size, 0, "AudioTrack cblk");
    if (heap->getBase() == nullptr || heap->getBase() == MAP_FAILED) {
        ALOGE("not enough memory for AudioTrack size=%zu", size);
        *status = NO_MEMORY;
        return nullptr;
    }
    track->mCblkMemory = sp<MemoryBase>::make(heap, 0, size);
    track->mCblk = static_cast<android::audio_track_cblk_t*>(track->mCblkMemory->unsecurePointer());
    new (track->mCblk) android::audio_track_cblk_t();
    if (sharedBuffer != nullptr) {
        track->mSharedBuffer = sharedBuffer;
        track->mBuffer = sharedBuffer->unsecurePointer();
        track->mServerProxy = sp<StaticAudioTrackServerProxy>::make(track->mCblk, track->mBuffer, frameCount,
                                                                     frameSize, sampleRate);
        track->mFillingStatus = FS_FILLED;
    } else {
        track->mBuffer = reinterpret_cast<char*>(track->mCblk) + sizeof(android::audio_track_cblk_t);
        memset(track->mBuffer, 0, bufferSize);
        track->mServerProxy = sp<AudioTrackServerProxy>::make(track->mCblk, track->mBuffer, frameCount, frameSize,
                                                               false /*clientInServer*/, sampleRate);
        track->mFillingStatus = FS_FILLING;
    }
    track->mVolumeHandler = sp<media::VolumeHandler>::make(sampleRate);
    track->mCallback = input.audioTrackCallback;
    track->mScratch.resize(kOutputFrameCount * channelCount);
    track->mSink = HostAudioSink::open(sampleRate, channelCount, &HostAudioTrack::fillThunk, track.get());
    *status = NO_ERROR;
    return track;
}

HostAudioTrack::HostAudioTrack(HostAudioFlinger& flinger, audio_stream_type_t streamType, audio_port_handle_t portId,
                               audio_session_t sessionId, uint32_t sampleRate, audio_format_t format,
                               audio_channel_mask_t channelMask, size_t frameCount, float portVolume, bool portMuted)
    : mFlinger(flinger),
      mStreamType(streamType),
      mPortId(portId),
      mSessionId(sessionId),
      mSampleRate(sampleRate),
      mFormat(format),
      mChannelMask(channelMask),
      mChannelCount(audio_channel_count_from_out_mask(channelMask)),
      mFrameSize(audio_bytes_per_frame(mChannelCount, format)),
      mFrameCount(frameCount),
      mPortVolume(portVolume),
      mPortMuted(portMuted) {}

HostAudioTrack::~HostAudioTrack() {
    // Track::destroy: leave the active list, then release the policy output.
    mSink.reset();  // no more fill callbacks
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mActive) deactivate_l();
    }
    mFlinger.policy()->releaseClient(mPortId);
    mFlinger.removeTrack(this);
    mServerProxy.clear();
    mCblkMemory.clear();
    mSharedBuffer.clear();
}

void HostAudioTrack::invalidate() {
    std::lock_guard<std::mutex> lock(mLock);
    android_atomic_or(CBLK_INVALID, &mCblk->mFlags);
    android_atomic_release_store(0x40000000, &mCblk->mFutex);
    (void)syscall(__NR_futex, &mCblk->mFutex, FUTEX_WAKE, INT_MAX);
}

// TrackBase::isReady.
bool HostAudioTrack::isReady_l() {
    if (mFillingStatus != FS_FILLING || mState == State::STOPPED || mState == State::PAUSING) return true;
    const size_t bufferSizeInFrames = mServerProxy->getBufferSizeInFrames();
    const size_t startThresholdInFrames = mServerProxy->getStartThresholdInFrames();
    const size_t framesToBeReady =
            std::clamp(std::min(startThresholdInFrames, bufferSizeInFrames), size_t(1), mFrameCount);
    if (mServerProxy->framesReady() >= framesToBeReady || (mCblk->mFlags & CBLK_FORCEREADY)) {
        mFillingStatus = FS_FILLED;
        android_atomic_and(~CBLK_FORCEREADY, &mCblk->mFlags);
        return true;
    }
    return false;
}

// Track::reset.
void HostAudioTrack::reset_l() {
    if (mResetDone) return;
    android_atomic_and(~CBLK_FORCEREADY, &mCblk->mFlags);
    mFillingStatus = FS_FILLING;
    mResetDone = true;
    if (mState == State::FLUSHED) mState = State::IDLE;
}

// PlaybackThread::addTrack_l for a track not yet in the active list.
status_t HostAudioTrack::activate_l() {
    if (mActive) return ALREADY_EXISTS;
    if (mSharedBuffer == nullptr) mFillingStatus = FS_FILLING;
    mRetryCount = kMaxTrackStartupRetries;
    const status_t status = mFlinger.policy()->startClient(mPortId);
    if (status != NO_ERROR) return status;
    mActive = true;
    mPresentationCompleteNs = 0;
    mSink->setActive(true);
    return NO_ERROR;
}

// PlaybackThread::removeTracks_l.
void HostAudioTrack::deactivate_l() {
    if (!mActive) return;
    mActive = false;
    mFlinger.policy()->stopClient(mPortId);
}

size_t HostAudioTrack::fillThunk(void* cookie, float* destination, size_t frames) {
    return static_cast<HostAudioTrack*>(cookie)->fill(destination, frames);
}

void HostAudioTrack::convertFrames(const void* source, float* destination, size_t frames, float left, float right) {
    const size_t samples = frames * mChannelCount;
    switch (mFormat) {
        case AUDIO_FORMAT_PCM_FLOAT:
            memcpy(destination, source, samples * sizeof(float));
            break;
        case AUDIO_FORMAT_PCM_16_BIT:
            memcpy_to_float_from_i16(destination, static_cast<const int16_t*>(source), samples);
            break;
        case AUDIO_FORMAT_PCM_8_BIT:
            memcpy_to_float_from_u8(destination, static_cast<const uint8_t*>(source), samples);
            break;
        case AUDIO_FORMAT_PCM_32_BIT:
            memcpy_to_float_from_i32(destination, static_cast<const int32_t*>(source), samples);
            break;
        case AUDIO_FORMAT_PCM_8_24_BIT:
            memcpy_to_float_from_q8_23(destination, static_cast<const int32_t*>(source), samples);
            break;
        case AUDIO_FORMAT_PCM_24_BIT_PACKED:
            memcpy_to_float_from_p24(destination, static_cast<const uint8_t*>(source), samples);
            break;
        default:
            memset(destination, 0, samples * sizeof(float));
            break;
    }
    if (mChannelCount == 2) {
        for (size_t i = 0; i < frames; i++) {
            destination[2 * i] *= left;
            destination[2 * i + 1] *= right;
        }
    } else {
        for (size_t i = 0; i < samples; i++) destination[i] *= left;
    }
}

// One mixer cycle for this track: MixerThread::prepareTracks_l plus the frames
// AudioMixer would pull through the server proxy.
size_t HostAudioTrack::fill(float* destination, size_t frames) {
    std::lock_guard<std::mutex> lock(mLock);
    const int64_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    size_t written = 0;
    if (!mActive) return 0;

    if (mState == State::PAUSING) {
        mState = State::PAUSED;
        mPresentationCompleteNs = 0;
    }
    if (mState == State::RESUMING) mState = State::ACTIVE;

    const bool isStatic = mSharedBuffer != nullptr;
    const size_t framesReady = mServerProxy->framesReady();
    const bool ready = isReady_l() && mState != State::PAUSED;

    // Each track has its own sink stream, so a short track never stalls another
    // ready track: mix what is available and tally the shortfall as underrun.
    if (ready && framesReady > 0) {
        if (mSampleRate != mServerProxy->getSampleRate() && !mWarnedRate) {
            mWarnedRate = true;
            ALOGW("unimplemented: AudioTrack setSampleRate after creation (portId %d)", mPortId);
        }
        const gain_minifloat_packed_t vlr = mServerProxy->getVolumeLR();
        const float streamGain = mPortMuted ? 0.0f : mPortVolume * mFlinger.gainForStream(mStreamType);
        const auto [shaperVolume, shaperActive] = mVolumeHandler->getVolume(mServerProxy->framesReleased());
        const float left = float_from_gain(gain_minifloat_unpack_left(vlr)) * streamGain * shaperVolume;
        const float right = float_from_gain(gain_minifloat_unpack_right(vlr)) * streamGain * shaperVolume;
        while (written < frames) {
            AudioTrackServerProxy::Buffer buffer;
            buffer.mFrameCount = frames - written;
            if (mServerProxy->obtainBuffer(&buffer) != NO_ERROR || buffer.mFrameCount == 0) break;
            convertFrames(buffer.mRaw, destination + written * mChannelCount, buffer.mFrameCount, left, right);
            written += buffer.mFrameCount;
            mServerProxy->releaseBuffer(&buffer);
        }
        mFlinger.addRenderedFrames(written);
        const bool underrun = mState == State::ACTIVE && !isStatic && written < frames;
        mServerProxy->tallyUnderrunFrames(underrun ? static_cast<uint32_t>(frames - written) : 0);
        mRetryCount = kMaxTrackRetries;
        mFillingStatus = FS_ACTIVE;
    } else {
        if (mState == State::ACTIVE && !isStatic) {
            mServerProxy->tallyUnderrunFrames(static_cast<uint32_t>(frames));
        }
        if (isStatic || mState == State::STOPPED || mState == State::PAUSED) {
            // presentationComplete: let the sink drain, then leave the active list.
            if (mPresentationCompleteNs == 0) mPresentationCompleteNs = now + int64_t(kOutputLatencyMs) * 1000000;
            if (now >= mPresentationCompleteNs) {
                if (mState == State::STOPPED) {
                    mServerProxy->setStreamEndDone();
                    reset_l();
                }
                deactivate_l();
            }
        } else if (--mRetryCount <= 0) {
            ALOGI("BUFFER TIMEOUT: remove track(%d) from active list due to underrun", mPortId);
            android_atomic_or(CBLK_DISABLED, &mCblk->mFlags);
            deactivate_l();
        }
    }
    publishTimestamp_l(now);
    return written;
}

// Track::updateTrackFrameInfo with the sink's presentation delay as the kernel position.
void HostAudioTrack::publishTimestamp_l(int64_t nowNs) {
    ExtendedTimestamp timestamp;
    const int64_t released = mServerProxy->framesReleased();
    const int64_t pending = mSink != nullptr ? mSink->pendingFrames() : 0;
    const int64_t kernel = std::max<int64_t>(0, released - pending);
    timestamp.mPosition[ExtendedTimestamp::LOCATION_SERVER] = released;
    timestamp.mTimeNs[ExtendedTimestamp::LOCATION_SERVER] = nowNs;
    timestamp.mPosition[ExtendedTimestamp::LOCATION_KERNEL] = kernel;
    timestamp.mTimeNs[ExtendedTimestamp::LOCATION_KERNEL] = nowNs;
    timestamp.mFlushed = mServerProxy->framesFlushed();
    mServerProxy->setDrained(kernel >= released && mServerProxy->framesReadySafe() == 0);
    mServerProxy->setTimestamp(timestamp);
}

// ---------------------------------------------------------------------------
// IAudioTrack

Status HostAudioTrack::getCblk(std::optional<media::SharedFileRegion>* _aidl_return) {
    media::SharedFileRegion region;
    if (!convertIMemoryToSharedFileRegion(mCblkMemory, &region)) {
        _aidl_return->reset();
        return Status::ok();
    }
    *_aidl_return = std::move(region);
    return Status::ok();
}

// Track::start.
Status HostAudioTrack::start(int32_t* _aidl_return) {
    std::lock_guard<std::mutex> lock(mLock);
    const State state = mState;
    if (state == State::PAUSED || state == State::PAUSING) {
        mState = State::RESUMING;
    } else {
        mState = State::ACTIVE;
    }
    if (state == State::FLUSHED || state == State::STOPPED || state == State::IDLE) {
        mPresentationCompleteNs = 0;
    }
    status_t status = activate_l();
    if (status == ALREADY_EXISTS) status = NO_ERROR;
    if (status != NO_ERROR) {
        mState = state;
    } else {
        android_atomic_and(~CBLK_DISABLED, &mCblk->mFlags);
        mResetDone = false;
        mServerProxy->start();
        AudioTrackServerProxy::Buffer buffer;
        buffer.mFrameCount = 1;
        (void)mServerProxy->obtainBuffer(&buffer, true /*ackFlush*/);
    }
    *_aidl_return = status;
    return Status::ok();
}

// Track::stop: a mixer track drains what it already has before leaving.
Status HostAudioTrack::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    const State state = mState;
    if (state == State::RESUMING || state == State::ACTIVE || state == State::PAUSING || state == State::PAUSED) {
        if (!mActive) {
            reset_l();
            mState = State::STOPPED;
        } else {
            mState = State::STOPPED;
            mPresentationCompleteNs = 0;
        }
    }
    return Status::ok();
}

// Track::pause.
Status HostAudioTrack::pause() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mState == State::ACTIVE || mState == State::RESUMING) mState = State::PAUSING;
    return Status::ok();
}

// Track::flush.
Status HostAudioTrack::flush() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mActive) (void)mServerProxy->flushBufferIfNeeded();
    if (mState != State::STOPPED && mState != State::PAUSED && mState != State::PAUSING && mState != State::IDLE &&
        mState != State::FLUSHED) {
        return Status::ok();
    }
    mState = State::FLUSHED;
    if (!mActive) reset_l();
    return Status::ok();
}

Status HostAudioTrack::attachAuxEffect(int32_t effectId, int32_t* _aidl_return) {
    // Track::attachAuxEffect: detaching is always fine; no effect chains exist here.
    *_aidl_return = effectId == 0 ? NO_ERROR : INVALID_OPERATION;
    return Status::ok();
}

Status HostAudioTrack::setParameters(const std::string& /*keyValuePairs*/, int32_t* _aidl_return) {
    *_aidl_return = PERMISSION_DENIED;  // Track::setParameters for a mixer track
    return Status::ok();
}

Status HostAudioTrack::selectPresentation(int32_t /*presentationId*/, int32_t /*programId*/, int32_t* _aidl_return) {
    *_aidl_return = INVALID_OPERATION;
    return Status::ok();
}

Status HostAudioTrack::getTimestamp(media::AudioTimestampInternal* /*timestamp*/, int32_t* _aidl_return) {
    *_aidl_return = INVALID_OPERATION;  // Track::getTimestamp: PCM tracks use the shared queue
    return Status::ok();
}

Status HostAudioTrack::signal() {
    return Status::ok();  // the sink polls the control block every cycle
}

Status HostAudioTrack::applyVolumeShaper(const media::VolumeShaperConfiguration& configuration,
                                         const media::VolumeShaperOperation& operation, int32_t* _aidl_return) {
    sp<media::VolumeShaper::Configuration> config = sp<media::VolumeShaper::Configuration>::make();
    sp<media::VolumeShaper::Operation> op = sp<media::VolumeShaper::Operation>::make();
    if (config->readFromParcelable(configuration) != NO_ERROR || op->readFromParcelable(operation) != NO_ERROR) {
        *_aidl_return = BAD_VALUE;
        return Status::ok();
    }
    std::lock_guard<std::mutex> lock(mLock);
    *_aidl_return = mVolumeHandler->applyVolumeShaper(config, op);
    return Status::ok();
}

Status HostAudioTrack::getVolumeShaperState(int32_t id, std::optional<media::VolumeShaperState>* _aidl_return) {
    std::lock_guard<std::mutex> lock(mLock);
    sp<media::VolumeShaper::State> state = mVolumeHandler->getVolumeShaperState(id);
    if (state == nullptr) {
        _aidl_return->reset();
    } else {
        media::VolumeShaperState parcelable;
        state->writeToParcelable(&parcelable);
        *_aidl_return = std::move(parcelable);
    }
    return Status::ok();
}

Status HostAudioTrack::getDualMonoMode(media::audio::common::AudioDualMonoMode* /*_aidl_return*/) {
    return statusFromT(INVALID_OPERATION);  // mixer tracks: Track::getDualMonoMode
}

Status HostAudioTrack::setDualMonoMode(media::audio::common::AudioDualMonoMode /*mode*/) {
    return statusFromT(INVALID_OPERATION);
}

Status HostAudioTrack::getAudioDescriptionMixLevel(float* /*_aidl_return*/) {
    return statusFromT(INVALID_OPERATION);
}

Status HostAudioTrack::setAudioDescriptionMixLevel(float /*leveldB*/) {
    return statusFromT(INVALID_OPERATION);
}

Status HostAudioTrack::getPlaybackRateParameters(media::audio::common::AudioPlaybackRate* /*_aidl_return*/) {
    return statusFromT(INVALID_OPERATION);  // mixer tracks use the control block queue
}

Status HostAudioTrack::setPlaybackRateParameters(const media::audio::common::AudioPlaybackRate& /*playbackRate*/) {
    return statusFromT(INVALID_OPERATION);
}

}  // namespace artzig::audio
