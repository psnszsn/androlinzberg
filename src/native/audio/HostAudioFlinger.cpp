// AudioFlinger (frameworks/av/services/audioflinger/AudioFlinger.cpp) for one
// primary output: createTrack ordering, client registration and io descriptor
// events, unique ids, session references, master/stream gains. The stock
// service cannot be compiled: it opens HAL modules and owns mixer/HAL threads.
#include "HostAudio.h"

#include <binder/IPCThreadState.h>
#include <media/AidlConversion.h>
#include <utils/Log.h>

#include <algorithm>

#undef LOG_TAG
#define LOG_TAG "HostAudioFlinger"

namespace artzig::audio {
using namespace android;
using android::binder::Status;

// ---------------------------------------------------------------------------
// UniqueIds: AudioFlinger::AudioFlinger() / nextUniqueId.

UniqueIds::UniqueIds() {
    // Session and client ids start above the reserved (`moving base`) range.
    constexpr uint32_t movingBase = 100;
    for (unsigned use = AUDIO_UNIQUE_ID_USE_UNSPECIFIED; use < AUDIO_UNIQUE_ID_USE_MAX; use++) {
        mNext[use] = ((use == AUDIO_UNIQUE_ID_USE_SESSION || use == AUDIO_UNIQUE_ID_USE_CLIENT) ? movingBase : 1) *
                     AUDIO_UNIQUE_ID_USE_MAX;
    }
}

audio_unique_id_t UniqueIds::next(audio_unique_id_use_t use) {
    for (int retry = 0; retry < 3; retry++) {
        const uint32_t base = mNext[use].fetch_add(AUDIO_UNIQUE_ID_USE_MAX, std::memory_order_acq_rel);
        if (!(base == 0 || base == (~0u & ~AUDIO_UNIQUE_ID_USE_MASK))) {
            return static_cast<audio_unique_id_t>(base | use);
        }
    }
    return AUDIO_UNIQUE_ID_ALLOCATE;
}

// ---------------------------------------------------------------------------
// HostOutput

HostOutput::HostOutput()
    : moduleId(static_cast<audio_module_handle_t>(ids.next(AUDIO_UNIQUE_ID_USE_MODULE))),
      ioHandle(static_cast<audio_io_handle_t>(ids.next(AUDIO_UNIQUE_ID_USE_OUTPUT))),
      devicePortId(static_cast<audio_port_handle_t>(ids.next(AUDIO_UNIQUE_ID_USE_UNSPECIFIED))),
      mixPortId(static_cast<audio_port_handle_t>(ids.next(AUDIO_UNIQUE_ID_USE_UNSPECIFIED))),
      patchId(static_cast<audio_patch_handle_t>(ids.next(AUDIO_UNIQUE_ID_USE_PATCH))) {}

static void fillProfiles(audio_port_v7& port) {
    port.num_audio_profiles = 2;
    const audio_format_t formats[] = {AUDIO_FORMAT_PCM_16_BIT, AUDIO_FORMAT_PCM_FLOAT};
    for (unsigned i = 0; i < port.num_audio_profiles; i++) {
        audio_profile& profile = port.audio_profiles[i];
        profile.format = formats[i];
        profile.num_sample_rates = 2;
        profile.sample_rates[0] = 44100;
        profile.sample_rates[1] = 48000;
        profile.num_channel_masks = 2;
        profile.channel_masks[0] = AUDIO_CHANNEL_OUT_MONO;
        profile.channel_masks[1] = AUDIO_CHANNEL_OUT_STEREO;
        profile.encapsulation_type = AUDIO_ENCAPSULATION_TYPE_NONE;
    }
}

audio_port_v7 HostOutput::devicePort() const {
    audio_port_v7 port{};
    port.id = devicePortId;
    port.role = AUDIO_PORT_ROLE_SINK;
    port.type = AUDIO_PORT_TYPE_DEVICE;
    strlcpy(port.name, "Speaker", sizeof(port.name));
    fillProfiles(port);
    port.active_config.id = devicePortId;
    port.active_config.role = AUDIO_PORT_ROLE_SINK;
    port.active_config.type = AUDIO_PORT_TYPE_DEVICE;
    port.active_config.config_mask = AUDIO_PORT_CONFIG_SAMPLE_RATE | AUDIO_PORT_CONFIG_CHANNEL_MASK | AUDIO_PORT_CONFIG_FORMAT;
    port.active_config.sample_rate = kOutputSampleRate;
    port.active_config.channel_mask = kOutputChannelMask;
    port.active_config.format = kOutputFormat;
    port.active_config.ext.device.hw_module = moduleId;
    port.active_config.ext.device.type = AUDIO_DEVICE_OUT_SPEAKER;
    port.ext.device.hw_module = moduleId;
    port.ext.device.type = AUDIO_DEVICE_OUT_SPEAKER;
    return port;
}

audio_port_v7 HostOutput::mixPort() const {
    audio_port_v7 port{};
    port.id = mixPortId;
    port.role = AUDIO_PORT_ROLE_SOURCE;
    port.type = AUDIO_PORT_TYPE_MIX;
    strlcpy(port.name, "primary output", sizeof(port.name));
    fillProfiles(port);
    port.active_config.id = mixPortId;
    port.active_config.role = AUDIO_PORT_ROLE_SOURCE;
    port.active_config.type = AUDIO_PORT_TYPE_MIX;
    port.active_config.config_mask = AUDIO_PORT_CONFIG_SAMPLE_RATE | AUDIO_PORT_CONFIG_CHANNEL_MASK | AUDIO_PORT_CONFIG_FORMAT;
    port.active_config.sample_rate = kOutputSampleRate;
    port.active_config.channel_mask = kOutputChannelMask;
    port.active_config.format = kOutputFormat;
    port.active_config.ext.mix.hw_module = moduleId;
    port.active_config.ext.mix.handle = ioHandle;
    port.ext.mix.hw_module = moduleId;
    port.ext.mix.handle = ioHandle;
    port.ext.mix.latency_class = AUDIO_LATENCY_NORMAL;
    return port;
}

audio_patch HostOutput::patch() const {
    audio_patch patch{};
    patch.id = patchId;
    patch.num_sources = 1;
    patch.sources[0] = mixPort().active_config;
    patch.num_sinks = 1;
    patch.sinks[0] = devicePort().active_config;
    return patch;
}

sp<AudioIoDescriptor> HostOutput::descriptor() const {
    return sp<AudioIoDescriptor>::make(ioHandle, patch(), false /*isInput*/, kOutputSampleRate, kOutputFormat,
                                       kOutputChannelMask, kOutputFrameCount, kOutputFrameCount, kOutputLatencyMs,
                                       mixPortId);
}

// ---------------------------------------------------------------------------
// HostAudioFlinger

class HostAudioFlinger::NotificationClient : public IBinder::DeathRecipient {
public:
    NotificationClient(HostAudioFlinger& flinger, const sp<media::IAudioFlingerClient>& client, pid_t pid)
        : mFlinger(flinger), mClient(client), mPid(pid) {}
    const sp<media::IAudioFlingerClient>& client() const { return mClient; }
    void binderDied(const wp<IBinder>& /*who*/) override { mFlinger.removeNotificationClient(mPid); }

private:
    HostAudioFlinger& mFlinger;
    const sp<media::IAudioFlingerClient> mClient;
    const pid_t mPid;
};

HostAudioFlinger::HostAudioFlinger() : mOutput(std::make_shared<HostOutput>()) {
    for (auto& volume : mStreamVolume) volume = 1.0f;
    for (auto& mute : mStreamMute) mute = false;
}

HostAudioFlinger::~HostAudioFlinger() = default;

void HostAudioFlinger::setPolicy(const sp<HostAudioPolicyService>& policy) {
    mPolicy = policy;
}

float HostAudioFlinger::gainForStream(audio_stream_type_t stream) const {
    if (mMasterMute.load(std::memory_order_relaxed)) return 0.0f;
    float gain = mMasterVolume.load(std::memory_order_relaxed);
    if (stream >= 0 && stream < AUDIO_STREAM_CNT) {
        if (mStreamMute[stream].load(std::memory_order_relaxed)) return 0.0f;
        gain *= mStreamVolume[stream].load(std::memory_order_relaxed);
    }
    return gain;
}

void HostAudioFlinger::removeTrack(HostAudioTrack* track) {
    std::lock_guard<std::mutex> lock(mLock);
    mTracks.erase(std::remove(mTracks.begin(), mTracks.end(), track), mTracks.end());
}

void HostAudioFlinger::removeNotificationClient(pid_t pid) {
    std::lock_guard<std::mutex> lock(mLock);
    mNotificationClients.erase(pid);
    mAudioSessionRefs.erase(pid);
}

// AudioFlinger::createTrack.
status_t HostAudioFlinger::createTrackInternal(IAudioFlinger::CreateTrackInput& input,
                                               IAudioFlinger::CreateTrackOutput& output) {
    const uid_t callingUid = IPCThreadState::self()->getCallingUid();
    const pid_t callingPid = IPCThreadState::self()->getCallingPid();
    // afutils::checkAttributionSourcePackage: an app may only speak for itself.
    input.clientInfo.attributionSource.uid = static_cast<int32_t>(callingUid);
    input.clientInfo.attributionSource.pid = static_cast<int32_t>(callingPid);

    audio_session_t sessionId = input.sessionId;
    if (sessionId == AUDIO_SESSION_ALLOCATE) {
        sessionId = static_cast<audio_session_t>(mOutput->ids.next(AUDIO_UNIQUE_ID_USE_SESSION));
    } else if (audio_unique_id_get_use(sessionId) != AUDIO_UNIQUE_ID_USE_SESSION) {
        ALOGE("createTrack() invalid session ID %d", sessionId);
        return BAD_VALUE;
    }
    output.sessionId = sessionId;
    output.outputId = AUDIO_IO_HANDLE_NONE;
    DeviceIdVector selectedDeviceIds;
    if (input.selectedDeviceId != AUDIO_PORT_HANDLE_NONE) selectedDeviceIds.push_back(input.selectedDeviceId);

    audio_attributes_t localAttr = input.attr;
    audio_stream_type_t streamType = AUDIO_STREAM_DEFAULT;
    audio_port_handle_t portId = AUDIO_PORT_HANDLE_NONE;
    float volume = 1.0f;
    bool muted = false;
    status_t lStatus = mPolicy->openClient(&localAttr, &output.outputId, sessionId, &streamType, callingUid,
                                                 &input.config, &input.flags, &selectedDeviceIds, &portId, &volume,
                                                 &muted);
    output.selectedDeviceIds = selectedDeviceIds;
    if (lStatus != NO_ERROR || output.outputId == AUDIO_IO_HANDLE_NONE) {
        ALOGE("createTrack() getOutputForAttr() return error %d or invalid output handle", lStatus);
        if (lStatus == NO_ERROR) lStatus = BAD_VALUE;
        goto Exit;
    }
    if (uint32_t(streamType) >= AUDIO_STREAM_CNT) {
        ALOGE("createTrack() invalid stream type %d", streamType);
        lStatus = BAD_VALUE;
        goto Exit;
    }
    if (!audio_is_output_channel(input.config.channel_mask)) {
        ALOGE("createTrack() invalid channel mask %#x", input.config.channel_mask);
        lStatus = BAD_VALUE;
        goto Exit;
    }
    if (!audio_is_valid_format(input.config.format)) {
        ALOGE("createTrack() invalid format %#x", input.config.format);
        lStatus = BAD_VALUE;
        goto Exit;
    }
    if (output.outputId != mOutput->ioHandle) {
        ALOGE("no playback thread found for output handle %d", output.outputId);
        lStatus = BAD_VALUE;
        goto Exit;
    }
    {
        output.sampleRate = input.config.sample_rate;
        output.frameCount = input.frameCount;
        output.notificationFrameCount = input.notificationFrameCount;
        output.flags = input.flags;
        output.streamType = streamType;
        input.sessionId = sessionId;
        sp<HostAudioTrack> track =
                HostAudioTrack::create(*this, input, output, streamType, portId, volume, muted, &lStatus);
        output.afFrameCount = kOutputFrameCount;
        output.afSampleRate = kOutputSampleRate;
        output.afChannelMask = kOutputChannelMask;
        output.afFormat = kOutputFormat;
        output.afLatencyMs = kOutputLatencyMs;
        output.afTrackFlags = AUDIO_OUTPUT_FLAG_PRIMARY;
        output.portId = portId;
        if (lStatus == NO_ERROR) {
            std::lock_guard<std::mutex> lock(mLock);
            mTracks.push_back(track.get());
            output.audioTrack = track;
        }
    }
Exit:
    if (lStatus != NO_ERROR && output.outputId != AUDIO_IO_HANDLE_NONE) {
        mPolicy->releaseClient(portId);
    }
    return lStatus;
}

Status HostAudioFlinger::createTrack(const media::CreateTrackRequest& request, media::CreateTrackResponse* _aidl_return) {
    IAudioFlinger::CreateTrackInput input = VALUE_OR_RETURN_BINDER(IAudioFlinger::CreateTrackInput::fromAidl(request));
    IAudioFlinger::CreateTrackOutput output{};
    const status_t status = createTrackInternal(input, output);
    if (status != NO_ERROR) return binderStatusFromStatusT(status);
    *_aidl_return = VALUE_OR_RETURN_BINDER(output.toAidl());
    return Status::ok();
}

Status HostAudioFlinger::sampleRate(int32_t ioHandle, int32_t* _aidl_return) {
    if (ioHandle != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = kOutputSampleRate;
    return Status::ok();
}

Status HostAudioFlinger::format(int32_t output, media::audio::common::AudioFormatDescription* _aidl_return) {
    if (output != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_format_t_AudioFormatDescription(kOutputFormat));
    return Status::ok();
}

Status HostAudioFlinger::frameCount(int32_t ioHandle, int64_t* _aidl_return) {
    if (ioHandle != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = kOutputFrameCount;
    return Status::ok();
}

Status HostAudioFlinger::latency(int32_t output, int32_t* _aidl_return) {
    if (output != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = kOutputLatencyMs;
    return Status::ok();
}

Status HostAudioFlinger::frameCountHAL(int32_t ioHandle, int64_t* _aidl_return) {
    return frameCount(ioHandle, _aidl_return);
}

Status HostAudioFlinger::setMasterVolume(float value) {
    mMasterVolume = value;
    return Status::ok();
}

Status HostAudioFlinger::setMasterMute(bool muted) {
    mMasterMute = muted;
    return Status::ok();
}

Status HostAudioFlinger::masterVolume(float* _aidl_return) {
    *_aidl_return = mMasterVolume;
    return Status::ok();
}

Status HostAudioFlinger::masterMute(bool* _aidl_return) {
    *_aidl_return = mMasterMute;
    return Status::ok();
}

Status HostAudioFlinger::setMasterBalance(float balance) {
    if (std::isnan(balance) || std::fabs(balance) > 1.f) return binderStatusFromStatusT(BAD_VALUE);
    return Status::ok();  // a stereo desktop sink has no balance control; AudioFlinger stores 0
}

Status HostAudioFlinger::getMasterBalance(float* _aidl_return) {
    *_aidl_return = 0.0f;
    return Status::ok();
}

Status HostAudioFlinger::setStreamVolume(media::audio::common::AudioStreamType stream, float value, bool muted,
                                         int32_t /*output*/) {
    const audio_stream_type_t legacy = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioStreamType_audio_stream_type_t(stream));
    if (uint32_t(legacy) >= AUDIO_STREAM_CNT || legacy == AUDIO_STREAM_PATCH) return binderStatusFromStatusT(BAD_VALUE);
    mStreamVolume[legacy] = value;
    mStreamMute[legacy] = muted;
    return Status::ok();
}

Status HostAudioFlinger::setStreamMute(media::audio::common::AudioStreamType stream, bool muted) {
    const audio_stream_type_t legacy = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioStreamType_audio_stream_type_t(stream));
    if (uint32_t(legacy) >= AUDIO_STREAM_CNT || legacy == AUDIO_STREAM_PATCH) return binderStatusFromStatusT(BAD_VALUE);
    mStreamMute[legacy] = muted;
    return Status::ok();
}

Status HostAudioFlinger::setMode(media::audio::common::AudioMode mode) {
    const audio_mode_t legacy = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioMode_audio_mode_t(mode));
    if (uint32_t(legacy) >= AUDIO_MODE_CNT) return binderStatusFromStatusT(BAD_VALUE);
    std::lock_guard<std::mutex> lock(mLock);
    mMode = legacy;
    return Status::ok();
}

Status HostAudioFlinger::setMicMute(bool state) {
    std::lock_guard<std::mutex> lock(mLock);
    mMicMute = state;
    return Status::ok();
}

Status HostAudioFlinger::getMicMute(bool* _aidl_return) {
    std::lock_guard<std::mutex> lock(mLock);
    *_aidl_return = mMicMute;
    return Status::ok();
}

Status HostAudioFlinger::setParameters(int32_t ioHandle, const std::string& keyValuePairs) {
    // The host output has no HAL parameters; AudioFlinger forwards these to the HAL.
    ALOGW("unimplemented: IAudioFlinger.setParameters(%d, \"%s\")", ioHandle, keyValuePairs.c_str());
    return Status::ok();
}

Status HostAudioFlinger::getParameters(int32_t /*ioHandle*/, const std::string& /*keys*/, std::string* _aidl_return) {
    _aidl_return->clear();
    return Status::ok();
}

// AudioFlinger::registerClient: link to death and replay the open outputs.
Status HostAudioFlinger::registerClient(const sp<media::IAudioFlingerClient>& client) {
    if (client == nullptr) return Status::ok();
    const pid_t pid = IPCThreadState::self()->getCallingPid();
    sp<NotificationClient> notificationClient;
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mNotificationClients.count(pid) != 0) return Status::ok();
        notificationClient = sp<NotificationClient>::make(*this, client, pid);
        mNotificationClients[pid] = notificationClient;
        IInterface::asBinder(client)->linkToDeath(notificationClient);
    }
    const media::AudioIoConfigEvent event =
            VALUE_OR_RETURN_BINDER(legacy2aidl_audio_io_config_event_t_AudioIoConfigEvent(AUDIO_OUTPUT_REGISTERED));
    const media::AudioIoDescriptor descriptor =
            VALUE_OR_RETURN_BINDER(legacy2aidl_AudioIoDescriptor_AudioIoDescriptor(mOutput->descriptor()));
    client->ioConfigChanged(event, descriptor);
    return Status::ok();
}

Status HostAudioFlinger::getInputBufferSize(int32_t /*sampleRate*/, const media::audio::common::AudioFormatDescription& /*format*/,
                                            const media::audio::common::AudioChannelLayout& /*channelMask*/, int64_t* _aidl_return) {
    *_aidl_return = 0;  // no input module: AudioRecord.getMinBufferSize reports no device
    return Status::ok();
}

Status HostAudioFlinger::getRenderPosition(int32_t output, media::RenderPosition* _aidl_return) {
    if (output != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    const uint64_t frames = mRenderedFrames.load(std::memory_order_relaxed);
    _aidl_return->halFrames = static_cast<int32_t>(frames);
    _aidl_return->dspFrames = static_cast<int32_t>(frames);
    return Status::ok();
}

Status HostAudioFlinger::newAudioUniqueId(media::AudioUniqueIdUse use, int32_t* _aidl_return) {
    const audio_unique_id_use_t legacy = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioUniqueIdUse_audio_unique_id_use_t(use));
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_unique_id_t_int32_t(mOutput->ids.next(legacy)));
    return Status::ok();
}

Status HostAudioFlinger::acquireAudioSessionId(int32_t audioSession, int32_t pid, int32_t /*uid*/) {
    const pid_t caller = IPCThreadState::self()->getCallingPid();
    std::lock_guard<std::mutex> lock(mLock);
    mAudioSessionRefs[pid > 0 ? pid : caller][static_cast<audio_session_t>(audioSession)]++;
    return Status::ok();
}

Status HostAudioFlinger::releaseAudioSessionId(int32_t audioSession, int32_t pid) {
    const pid_t caller = IPCThreadState::self()->getCallingPid();
    std::lock_guard<std::mutex> lock(mLock);
    auto refs = mAudioSessionRefs.find(pid > 0 ? pid : caller);
    if (refs == mAudioSessionRefs.end()) return Status::ok();
    auto ref = refs->second.find(static_cast<audio_session_t>(audioSession));
    if (ref != refs->second.end() && --ref->second <= 0) refs->second.erase(ref);
    return Status::ok();
}

Status HostAudioFlinger::queryNumberEffects(int32_t* _aidl_return) {
    *_aidl_return = 0;
    return Status::ok();
}

Status HostAudioFlinger::queryEffect(int32_t /*index*/, media::EffectDescriptor* /*_aidl_return*/) {
    return binderStatusFromStatusT(BAD_VALUE);
}

Status HostAudioFlinger::getEffectDescriptor(const media::audio::common::AudioUuid& /*effectUUID*/,
                                             const media::audio::common::AudioUuid& /*typeUUID*/,
                                             int32_t /*preferredTypeFlag*/, media::EffectDescriptor* /*_aidl_return*/) {
    return binderStatusFromStatusT(NAME_NOT_FOUND);
}

Status HostAudioFlinger::getPrimaryOutputSamplingRate(int32_t* _aidl_return) {
    *_aidl_return = kOutputSampleRate;
    return Status::ok();
}

Status HostAudioFlinger::getPrimaryOutputFrameCount(int64_t* _aidl_return) {
    *_aidl_return = kOutputFrameCount;
    return Status::ok();
}

Status HostAudioFlinger::setLowRamDevice(bool /*isLowRamDevice*/, int64_t /*totalMemory*/) {
    return Status::ok();
}

Status HostAudioFlinger::getAudioPort(const media::AudioPortFw& port, media::AudioPortFw* _aidl_return) {
    if (port.hal.id == mOutput->devicePortId) {
        *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_port_v7_AudioPortFw(mOutput->devicePort()));
        return Status::ok();
    }
    if (port.hal.id == mOutput->mixPortId) {
        *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_port_v7_AudioPortFw(mOutput->mixPort()));
        return Status::ok();
    }
    return binderStatusFromStatusT(BAD_VALUE);
}

Status HostAudioFlinger::listAudioPatches(int32_t maxCount, std::vector<media::AudioPatchFw>* _aidl_return) {
    _aidl_return->clear();
    if (maxCount > 0) {
        _aidl_return->push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_patch_AudioPatchFw(mOutput->patch())));
    }
    return Status::ok();
}

Status HostAudioFlinger::systemReady() {
    return Status::ok();
}

Status HostAudioFlinger::audioPolicyReady() {
    return Status::ok();
}

Status HostAudioFlinger::getMicrophones(std::vector<media::MicrophoneInfoFw>* _aidl_return) {
    _aidl_return->clear();
    return Status::ok();
}

Status HostAudioFlinger::setAudioHalPids(const std::vector<int32_t>& /*pids*/) {
    return Status::ok();
}

Status HostAudioFlinger::setVibratorInfos(const std::vector<media::AudioVibratorInfo>& /*vibratorInfos*/) {
    return Status::ok();
}

Status HostAudioFlinger::getMmapPolicyInfos(media::audio::common::AudioMMapPolicyType /*policyType*/,
                                            std::vector<media::audio::common::AudioMMapPolicyInfo>* _aidl_return) {
    _aidl_return->clear();  // no MMAP capable HAL: AAudio stays on the legacy path
    return Status::ok();
}

Status HostAudioFlinger::setRequestedLatencyMode(int32_t output, media::audio::common::AudioLatencyMode latencyMode) {
    if (output != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    if (latencyMode != media::audio::common::AudioLatencyMode::FREE) return binderStatusFromStatusT(BAD_VALUE);
    return Status::ok();
}

Status HostAudioFlinger::getSupportedLatencyModes(int32_t output, std::vector<media::audio::common::AudioLatencyMode>* _aidl_return) {
    if (output != mOutput->ioHandle) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = {media::audio::common::AudioLatencyMode::FREE};
    return Status::ok();
}

Status HostAudioFlinger::supportsBluetoothVariableLatency(bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioFlinger::isBluetoothVariableLatencyEnabled(bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioFlinger::getSoundDoseInterface(const sp<media::ISoundDoseCallback>& /*callback*/,
                                               sp<media::ISoundDose>* _aidl_return) {
    *_aidl_return = nullptr;  // no sound dose computation without a HAL
    return Status::ok();
}

Status HostAudioFlinger::invalidateTracks(const std::vector<int32_t>& portIds) {
    std::vector<HostAudioTrack*> tracks;
    {
        std::lock_guard<std::mutex> lock(mLock);
        for (HostAudioTrack* track : mTracks) {
            if (std::find(portIds.begin(), portIds.end(), track->portId()) != portIds.end()) tracks.push_back(track);
        }
    }
    for (HostAudioTrack* track : tracks) track->invalidate();
    return Status::ok();
}

}  // namespace artzig::audio
