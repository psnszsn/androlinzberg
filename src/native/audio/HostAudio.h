// Host media.audio_flinger / media.audio_policy services behind the pinned
// libaudioclient AIDL. The stock services (frameworks/av/services/audioflinger,
// audiopolicy) cannot be compiled: they own HAL modules, mixer threads, effects
// and a policy engine over Android audio HALs that do not exist here. These
// adapters follow AudioFlinger::createTrack / PlaybackThread::Track and
// AudioPolicyManager::getOutputForAttr for one primary output whose Linux side
// is a PipeWire playback stream, and reuse the pinned AudioTrackShared server
// proxy so the control block protocol is the AOSP one.
#pragma once

#include <atomic>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <android/media/BnAudioTrack.h>
#include <azk/audio/BnAudioFlingerServiceUnsupported.h>
#include <azk/audio/BnAudioPolicyServiceUnsupported.h>
#include <binder/IBinder.h>
#include <binder/IMemory.h>
#include <media/AudioCommonTypes.h>
#include <media/AudioIoDescriptor.h>
#include <media/IAudioFlinger.h>
#include <media/VolumeShaper.h>
#include <private/media/AudioTrackShared.h>
#include <system/audio.h>

#include <media/AidlConversionUtil.h>

struct azk_pw_stream;

// IAudioFlinger.cpp / AudioSystem.cpp keep these per file; shared here by the host services.
#define VALUE_OR_RETURN_BINDER(x)                                       \
    ({                                                                  \
        auto _tmp = (x);                                                \
        if (!_tmp.ok()) return ::android::aidl_utils::binderStatusFromStatusT(_tmp.error()); \
        std::move(_tmp.value());                                        \
    })
#define RETURN_BINDER_STATUS_IF_ERROR(x)                                \
    {                                                                   \
        const ::android::status_t _status = (x);                        \
        if (_status != ::android::NO_ERROR) return ::android::aidl_utils::binderStatusFromStatusT(_status); \
    }

namespace artzig::audio {
using ::android::aidl_utils::binderStatusFromStatusT;

// The host output: one mixed primary output feeding the desktop PipeWire sink,
// presented as a speaker device with the period/latency of a stub-like HAL.
constexpr uint32_t kOutputSampleRate = 48000;
constexpr audio_format_t kOutputFormat = AUDIO_FORMAT_PCM_FLOAT;
constexpr audio_channel_mask_t kOutputChannelMask = AUDIO_CHANNEL_OUT_STEREO;
constexpr size_t kOutputFrameCount = 1024;  // HAL period: PipeWire's default 48 kHz quantum
constexpr uint32_t kOutputLatencyMs = 40;   // two periods in flight

// AudioFlinger::nextUniqueId.
class UniqueIds {
public:
    UniqueIds();
    audio_unique_id_t next(audio_unique_id_use_t use);

private:
    std::atomic<uint32_t> mNext[AUDIO_UNIQUE_ID_USE_MAX];
};

// Identity of the primary output as AudioFlinger and the policy manager report it.
struct HostOutput {
    HostOutput();
    UniqueIds ids;
    const audio_module_handle_t moduleId;
    const audio_io_handle_t ioHandle;
    const audio_port_handle_t devicePortId;
    const audio_port_handle_t mixPortId;
    const audio_patch_handle_t patchId;
    audio_patch patch() const;
    android::sp<android::AudioIoDescriptor> descriptor() const;
    audio_port_v7 devicePort() const;
    audio_port_v7 mixPort() const;
};

// Linux side of the seam: a PipeWire playback stream, or a null sink consuming
// frames in real time (like AOSP's audio.stub HAL) when no daemon is reachable.
class HostAudioSink {
public:
    using Fill = size_t (*)(void* cookie, float* destination, size_t frames);
    static std::unique_ptr<HostAudioSink> open(uint32_t sampleRate, uint32_t channels, Fill fill, void* cookie);
    ~HostAudioSink();
    void setActive(bool active);
    int64_t pendingFrames();
    bool isNull() const { return mStream == nullptr; }

private:
    HostAudioSink(uint32_t sampleRate, uint32_t channels, Fill fill, void* cookie);
    void nullSinkLoop();
    azk_pw_stream* mStream = nullptr;
    const uint32_t mSampleRate;
    const uint32_t mChannels;
    const Fill mFill;
    void* const mCookie;
    std::mutex mLock;
    std::condition_variable mCondition;
    bool mActive = false;
    bool mExit = false;
    std::thread mThread;
};

class HostAudioFlinger;
class HostAudioPolicyService;

// PlaybackThread::Track plus its TrackHandle: the IAudioTrack Binder object, the
// shared control block and the mixer-side state machine, over one sink stream.
class HostAudioTrack : public android::media::BnAudioTrack {
public:
    static android::sp<HostAudioTrack> create(HostAudioFlinger& flinger,
                                              const android::IAudioFlinger::CreateTrackInput& input,
                                              android::IAudioFlinger::CreateTrackOutput& output,
                                              audio_stream_type_t streamType, audio_port_handle_t portId,
                                              float portVolume, bool portMuted, android::status_t* status);
    ~HostAudioTrack() override;

    audio_port_handle_t portId() const { return mPortId; }
    audio_session_t sessionId() const { return mSessionId; }
    audio_stream_type_t streamType() const { return mStreamType; }
    void invalidate();

    // IAudioTrack
    android::binder::Status getCblk(std::optional<android::media::SharedFileRegion>* _aidl_return) override;
    android::binder::Status start(int32_t* _aidl_return) override;
    android::binder::Status stop() override;
    android::binder::Status flush() override;
    android::binder::Status pause() override;
    android::binder::Status attachAuxEffect(int32_t effectId, int32_t* _aidl_return) override;
    android::binder::Status setParameters(const std::string& keyValuePairs, int32_t* _aidl_return) override;
    android::binder::Status selectPresentation(int32_t presentationId, int32_t programId, int32_t* _aidl_return) override;
    android::binder::Status getTimestamp(android::media::AudioTimestampInternal* timestamp, int32_t* _aidl_return) override;
    android::binder::Status signal() override;
    android::binder::Status applyVolumeShaper(const android::media::VolumeShaperConfiguration& configuration,
                                              const android::media::VolumeShaperOperation& operation,
                                              int32_t* _aidl_return) override;
    android::binder::Status getVolumeShaperState(int32_t id, std::optional<android::media::VolumeShaperState>* _aidl_return) override;
    android::binder::Status getDualMonoMode(android::media::audio::common::AudioDualMonoMode* _aidl_return) override;
    android::binder::Status setDualMonoMode(android::media::audio::common::AudioDualMonoMode mode) override;
    android::binder::Status getAudioDescriptionMixLevel(float* _aidl_return) override;
    android::binder::Status setAudioDescriptionMixLevel(float leveldB) override;
    android::binder::Status getPlaybackRateParameters(android::media::audio::common::AudioPlaybackRate* _aidl_return) override;
    android::binder::Status setPlaybackRateParameters(const android::media::audio::common::AudioPlaybackRate& playbackRate) override;

private:
    // TrackBase::track_state values a mixer track passes through.
    enum class State { IDLE, FLUSHED, STOPPED, RESUMING, ACTIVE, PAUSING, PAUSED };
    enum FillingStatus { FS_FILLING, FS_FILLED, FS_ACTIVE };

    HostAudioTrack(HostAudioFlinger& flinger, audio_stream_type_t streamType, audio_port_handle_t portId,
                   audio_session_t sessionId, uint32_t sampleRate, audio_format_t format,
                   audio_channel_mask_t channelMask, size_t frameCount, float portVolume, bool portMuted);
    static size_t fillThunk(void* cookie, float* destination, size_t frames);
    size_t fill(float* destination, size_t frames);
    bool isReady_l();
    void reset_l();
    android::status_t activate_l();
    void deactivate_l();
    void publishTimestamp_l(int64_t nowNs);
    void convertFrames(const void* source, float* destination, size_t frames, float left, float right);

    HostAudioFlinger& mFlinger;
    const audio_stream_type_t mStreamType;
    const audio_port_handle_t mPortId;
    const audio_session_t mSessionId;
    const uint32_t mSampleRate;
    const audio_format_t mFormat;
    const audio_channel_mask_t mChannelMask;
    const uint32_t mChannelCount;
    const size_t mFrameSize;
    const size_t mFrameCount;
    const float mPortVolume;
    const bool mPortMuted;

    std::mutex mLock;
    android::sp<android::IMemory> mCblkMemory;
    android::sp<android::IMemory> mSharedBuffer;
    android::audio_track_cblk_t* mCblk = nullptr;
    void* mBuffer = nullptr;
    android::sp<android::AudioTrackServerProxy> mServerProxy;
    android::sp<android::media::VolumeHandler> mVolumeHandler;
    android::sp<android::media::IAudioTrackCallback> mCallback;
    std::unique_ptr<HostAudioSink> mSink;
    std::vector<float> mScratch;

    State mState = State::IDLE;
    FillingStatus mFillingStatus = FS_FILLING;
    bool mActive = false;      // in the output's active track list (startOutput held)
    bool mResetDone = false;
    int32_t mRetryCount = 0;
    int64_t mPresentationCompleteNs = 0;
    bool mWarnedRate = false;
};

class HostAudioFlinger : public android::media::BnAudioFlingerServiceUnsupported {
public:
    HostAudioFlinger();
    ~HostAudioFlinger() override;
    void setPolicy(const android::sp<HostAudioPolicyService>& policy);
    HostAudioPolicyService* policy() const { return mPolicy.get(); }
    const std::shared_ptr<HostOutput>& output() const { return mOutput; }
    float gainForStream(audio_stream_type_t stream) const;
    void removeTrack(HostAudioTrack* track);
    void addRenderedFrames(uint64_t frames) { mRenderedFrames.fetch_add(frames, std::memory_order_relaxed); }

    // IAudioFlingerService
    android::binder::Status createTrack(const android::media::CreateTrackRequest& request,
                                        android::media::CreateTrackResponse* _aidl_return) override;
    android::binder::Status sampleRate(int32_t ioHandle, int32_t* _aidl_return) override;
    android::binder::Status format(int32_t output, android::media::audio::common::AudioFormatDescription* _aidl_return) override;
    android::binder::Status frameCount(int32_t ioHandle, int64_t* _aidl_return) override;
    android::binder::Status latency(int32_t output, int32_t* _aidl_return) override;
    android::binder::Status frameCountHAL(int32_t ioHandle, int64_t* _aidl_return) override;
    android::binder::Status setMasterVolume(float value) override;
    android::binder::Status setMasterMute(bool muted) override;
    android::binder::Status masterVolume(float* _aidl_return) override;
    android::binder::Status masterMute(bool* _aidl_return) override;
    android::binder::Status setMasterBalance(float balance) override;
    android::binder::Status getMasterBalance(float* _aidl_return) override;
    android::binder::Status setStreamVolume(android::media::audio::common::AudioStreamType stream, float value, bool muted, int32_t output) override;
    android::binder::Status setStreamMute(android::media::audio::common::AudioStreamType stream, bool muted) override;
    android::binder::Status setMode(android::media::audio::common::AudioMode mode) override;
    android::binder::Status setMicMute(bool state) override;
    android::binder::Status getMicMute(bool* _aidl_return) override;
    android::binder::Status setParameters(int32_t ioHandle, const std::string& keyValuePairs) override;
    android::binder::Status getParameters(int32_t ioHandle, const std::string& keys, std::string* _aidl_return) override;
    android::binder::Status registerClient(const android::sp<android::media::IAudioFlingerClient>& client) override;
    android::binder::Status getInputBufferSize(int32_t sampleRate, const android::media::audio::common::AudioFormatDescription& format,
                                               const android::media::audio::common::AudioChannelLayout& channelMask, int64_t* _aidl_return) override;
    android::binder::Status getRenderPosition(int32_t output, android::media::RenderPosition* _aidl_return) override;
    android::binder::Status newAudioUniqueId(android::media::AudioUniqueIdUse use, int32_t* _aidl_return) override;
    android::binder::Status acquireAudioSessionId(int32_t audioSession, int32_t pid, int32_t uid) override;
    android::binder::Status releaseAudioSessionId(int32_t audioSession, int32_t pid) override;
    android::binder::Status queryNumberEffects(int32_t* _aidl_return) override;
    android::binder::Status queryEffect(int32_t index, android::media::EffectDescriptor* _aidl_return) override;
    android::binder::Status getEffectDescriptor(const android::media::audio::common::AudioUuid& effectUUID,
                                                const android::media::audio::common::AudioUuid& typeUUID,
                                                int32_t preferredTypeFlag, android::media::EffectDescriptor* _aidl_return) override;
    android::binder::Status getPrimaryOutputSamplingRate(int32_t* _aidl_return) override;
    android::binder::Status getPrimaryOutputFrameCount(int64_t* _aidl_return) override;
    android::binder::Status setLowRamDevice(bool isLowRamDevice, int64_t totalMemory) override;
    android::binder::Status getAudioPort(const android::media::AudioPortFw& port, android::media::AudioPortFw* _aidl_return) override;
    android::binder::Status listAudioPatches(int32_t maxCount, std::vector<android::media::AudioPatchFw>* _aidl_return) override;
    android::binder::Status systemReady() override;
    android::binder::Status audioPolicyReady() override;
    android::binder::Status getMicrophones(std::vector<android::media::MicrophoneInfoFw>* _aidl_return) override;
    android::binder::Status setAudioHalPids(const std::vector<int32_t>& pids) override;
    android::binder::Status setVibratorInfos(const std::vector<android::media::AudioVibratorInfo>& vibratorInfos) override;
    android::binder::Status getMmapPolicyInfos(android::media::audio::common::AudioMMapPolicyType policyType,
                                               std::vector<android::media::audio::common::AudioMMapPolicyInfo>* _aidl_return) override;
    android::binder::Status setRequestedLatencyMode(int32_t output, android::media::audio::common::AudioLatencyMode latencyMode) override;
    android::binder::Status getSupportedLatencyModes(int32_t output, std::vector<android::media::audio::common::AudioLatencyMode>* _aidl_return) override;
    android::binder::Status supportsBluetoothVariableLatency(bool* _aidl_return) override;
    android::binder::Status isBluetoothVariableLatencyEnabled(bool* _aidl_return) override;
    android::binder::Status getSoundDoseInterface(const android::sp<android::media::ISoundDoseCallback>& callback,
                                                  android::sp<android::media::ISoundDose>* _aidl_return) override;
    android::binder::Status invalidateTracks(const std::vector<int32_t>& portIds) override;

private:
    class NotificationClient;
    android::status_t createTrackInternal(android::IAudioFlinger::CreateTrackInput& input,
                                          android::IAudioFlinger::CreateTrackOutput& output);
    void removeNotificationClient(pid_t pid);

    const std::shared_ptr<HostOutput> mOutput;
    android::sp<HostAudioPolicyService> mPolicy;
    std::mutex mLock;
    std::map<pid_t, android::sp<NotificationClient>> mNotificationClients;
    std::vector<HostAudioTrack*> mTracks;
    std::map<pid_t, std::map<audio_session_t, int>> mAudioSessionRefs;
    std::atomic<float> mMasterVolume{1.0f};
    std::atomic<bool> mMasterMute{false};
    std::atomic<float> mStreamVolume[AUDIO_STREAM_CNT];
    std::atomic<bool> mStreamMute[AUDIO_STREAM_CNT];
    std::atomic<uint64_t> mRenderedFrames{0};
    audio_mode_t mMode = AUDIO_MODE_NORMAL;
    bool mMicMute = false;
};

class HostAudioPolicyService : public android::media::BnAudioPolicyServiceUnsupported {
public:
    explicit HostAudioPolicyService(std::shared_ptr<HostOutput> output);
    ~HostAudioPolicyService() override;

    // In-process form of the AudioSystem getOutputForAttr/startOutput/stopOutput/
    // releaseOutput calls AudioFlinger makes on the policy service.
    android::status_t openClient(audio_attributes_t* attr, audio_io_handle_t* output, audio_session_t session,
                                 audio_stream_type_t* stream, uid_t uid, audio_config_t* config,
                                 audio_output_flags_t* flags, android::DeviceIdVector* selectedDeviceIds,
                                 audio_port_handle_t* portId, float* volume, bool* muted);
    android::status_t startClient(audio_port_handle_t portId);
    android::status_t stopClient(audio_port_handle_t portId);
    void releaseClient(audio_port_handle_t portId);

    // IAudioPolicyService
    android::binder::Status getDeviceConnectionState(const android::media::audio::common::AudioDevice& device,
                                                     android::media::AudioPolicyDeviceState* _aidl_return) override;
    android::binder::Status setPhoneState(android::media::audio::common::AudioMode state, int32_t uid) override;
    android::binder::Status getPhoneState(android::media::audio::common::AudioMode* _aidl_return) override;
    android::binder::Status setForceUse(android::media::AudioPolicyForceUse usage, android::media::AudioPolicyForcedConfig config) override;
    android::binder::Status getForceUse(android::media::AudioPolicyForceUse usage, android::media::AudioPolicyForcedConfig* _aidl_return) override;
    android::binder::Status getOutputForAttr(const android::media::audio::common::AudioAttributes& attr, int32_t session,
                                             const android::content::AttributionSourceState& attributionSource,
                                             const android::media::audio::common::AudioConfig& config, int32_t flags,
                                             const std::vector<int32_t>& selectedDeviceIds,
                                             android::media::GetOutputForAttrResponse* _aidl_return) override;
    android::binder::Status getOutput(android::media::audio::common::AudioStreamType stream, int32_t* _aidl_return) override;
    android::binder::Status getStrategyForStream(android::media::audio::common::AudioStreamType stream, int32_t* _aidl_return) override;
    android::binder::Status listAudioProductStrategies(std::vector<android::media::AudioProductStrategy>* _aidl_return) override;
    android::binder::Status getProductStrategyFromAudioAttributes(const android::media::audio::common::AudioAttributes& aa,
                                                                  bool fallbackOnDefault, int32_t* _aidl_return) override;
    android::binder::Status listAudioVolumeGroups(std::vector<android::media::AudioVolumeGroup>* _aidl_return) override;
    android::binder::Status getVolumeGroupFromAudioAttributes(const android::media::audio::common::AudioAttributes& aa,
                                                              bool fallbackOnDefault, int32_t* _aidl_return) override;
    android::binder::Status getDevicesForRoleAndStrategy(int32_t strategy, android::media::DeviceRole role,
                                                         std::vector<android::media::audio::common::AudioDevice>* _aidl_return) override;
    android::binder::Status listAudioPatches(android::media::audio::common::Int* count,
                                             std::vector<android::media::AudioPatchFw>* patches, int32_t* _aidl_return) override;
    android::binder::Status startOutput(int32_t portId) override;
    android::binder::Status stopOutput(int32_t portId) override;
    android::binder::Status releaseOutput(int32_t portId) override;
    android::binder::Status getDevicesForAttributes(const android::media::audio::common::AudioAttributes& attr, bool forVolume,
                                                    std::vector<android::media::audio::common::AudioDevice>* _aidl_return) override;
    android::binder::Status isStreamActive(android::media::audio::common::AudioStreamType stream, int32_t inPastMs, bool* _aidl_return) override;
    android::binder::Status isStreamActiveRemotely(android::media::audio::common::AudioStreamType stream, int32_t inPastMs, bool* _aidl_return) override;
    android::binder::Status isSourceActive(android::media::audio::common::AudioSource source, bool* _aidl_return) override;
    android::binder::Status setSupportedSystemUsages(const std::vector<android::media::audio::common::AudioUsage>& systemUsages) override;
    android::binder::Status getOffloadSupport(const android::media::audio::common::AudioOffloadInfo& info, android::media::AudioOffloadMode* _aidl_return) override;
    android::binder::Status isDirectOutputSupported(const android::media::audio::common::AudioConfigBase& config,
                                                    const android::media::audio::common::AudioAttributes& attributes, bool* _aidl_return) override;
    android::binder::Status listAudioPorts(android::media::AudioPortRole role, android::media::AudioPortType type,
                                           android::media::audio::common::Int* count, std::vector<android::media::AudioPortFw>* ports,
                                           int32_t* _aidl_return) override;
    android::binder::Status listDeclaredDevicePorts(android::media::AudioPortRole role, std::vector<android::media::AudioPortFw>* _aidl_return) override;
    android::binder::Status getAudioPort(int32_t portId, android::media::AudioPortFw* _aidl_return) override;
    android::binder::Status registerClient(const android::sp<android::media::IAudioPolicyServiceClient>& client) override;
    android::binder::Status setAudioPortCallbacksEnabled(bool enabled) override;
    android::binder::Status setAudioVolumeGroupCallbacksEnabled(bool enabled) override;
    android::binder::Status getMasterMono(bool* _aidl_return) override;
    android::binder::Status isHapticPlaybackSupported(bool* _aidl_return) override;
    android::binder::Status isUltrasoundSupported(bool* _aidl_return) override;
    android::binder::Status canBeSpatialized(const std::optional<android::media::audio::common::AudioAttributes>& attr,
                                             const std::optional<android::media::audio::common::AudioConfig>& config,
                                             const std::vector<android::media::audio::common::AudioDevice>& devices, bool* _aidl_return) override;
    android::binder::Status getDirectPlaybackSupport(const android::media::audio::common::AudioAttributes& attr,
                                                     const android::media::audio::common::AudioConfig& config,
                                                     android::media::AudioDirectMode* _aidl_return) override;

private:
    struct Client {
        audio_attributes_t attr;
        audio_session_t session;
        audio_stream_type_t stream;
        uid_t uid;
        bool active = false;
        int64_t stoppedNs = 0;
    };
    std::vector<audio_port_v7> ports_l(audio_port_role_t role, audio_port_type_t type) const;

    const std::shared_ptr<HostOutput> mOutput;
    std::mutex mLock;
    std::map<audio_port_handle_t, Client> mClients;
    std::vector<android::sp<android::media::IAudioPolicyServiceClient>> mNotificationClients;
    std::map<audio_policy_force_use_t, audio_policy_forced_cfg_t> mForceUse;
    audio_mode_t mPhoneState = AUDIO_MODE_NORMAL;
};

// EngineDefaultConfig.h: the default product strategies, their attribute groups
// and the volume group (one per stream) each group belongs to.
struct EngineAttributesGroup {
    audio_stream_type_t stream;
    android::volume_group_t volumeGroup;
    std::vector<audio_attributes_t> attributes;
};
struct EngineStrategy {
    android::product_strategy_t id;
    const char* name;
    std::vector<EngineAttributesGroup> groups;
};
const std::vector<EngineStrategy>& engineStrategies();
// ProductStrategyMap::getProductStrategyForAttributes / getStreamTypeForAttributes:
// the best-scoring attribute group, or the default (STRATEGY_MEDIA / MUSIC) one.
const EngineAttributesGroup* engineGroupForAttributes(const audio_attributes_t& attr, bool fallbackOnDefault,
                                                      const EngineStrategy** strategy);
audio_stream_type_t streamTypeForAttributes(const audio_attributes_t& attr);

}  // namespace artzig::audio
