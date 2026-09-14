// AudioPolicyService / AudioPolicyManager (frameworks/av/services/audiopolicy)
// for one primary output on one speaker device: getOutputForAttr, output
// client lifecycle, port listing, activity queries. The stock manager cannot be
// compiled: it is driven by a HAL module configuration and a policy engine
// with volume curves and routing that this host does not have.
#include "HostAudio.h"

#include <binder/IPCThreadState.h>
#include <media/AidlConversion.h>
#include <media/AudioPolicy.h>
#include <media/AudioProductStrategy.h>
#include <media/AudioSystem.h>
#include <media/TypeConverter.h>
#include <media/PolicyAidlConversion.h>
#include <utils/Log.h>
#include <utils/Timers.h>

#include <algorithm>

#undef LOG_TAG
#define LOG_TAG "HostAudioPolicy"

namespace android {
// Declared with an in-class initializer in AudioPolicy.h; the unoptimized island
// build ODR-uses it, and no AOSP translation unit provides the definition.
const uint32_t AudioMix::kCbFlagNotifyActivity;
}  // namespace android

namespace artzig::audio {
using namespace android;
using android::binder::Status;

namespace {
// EngineConfig.h: tag marking the reserved internal rerouting/patch strategies.
constexpr const char* kApmReservedInternalTag = "reserved_internal_strategy";

audio_attributes_t attributes(audio_content_type_t content, audio_usage_t usage, audio_flags_mask_t flags = AUDIO_FLAG_NONE,
                              const char* tags = "") {
    audio_attributes_t attr = AUDIO_ATTRIBUTES_INITIALIZER;
    attr.content_type = content;
    attr.usage = usage;
    attr.flags = flags;
    strlcpy(attr.tags, tags, sizeof(attr.tags));
    return attr;
}
}  // namespace

// gOrderedStrategies + gOrderedSystemStrategies; ids and volume groups follow the
// engine's sequential handle order.
const std::vector<EngineStrategy>& engineStrategies() {
    static const std::vector<EngineStrategy> strategies = [] {
        std::vector<EngineStrategy> list;
        product_strategy_t nextStrategy = static_cast<product_strategy_t>(1);
        volume_group_t nextGroup = static_cast<volume_group_t>(1);
        auto add = [&](const char* name, std::vector<std::pair<audio_stream_type_t, std::vector<audio_attributes_t>>> groups) {
            EngineStrategy strategy{nextStrategy, name, {}};
            nextStrategy = static_cast<product_strategy_t>(nextStrategy + 1);
            for (auto& [stream, attrs] : groups) {
                strategy.groups.push_back(EngineAttributesGroup{stream, nextGroup, std::move(attrs)});
                nextGroup = static_cast<volume_group_t>(nextGroup + 1);
            }
            list.push_back(std::move(strategy));
        };
        add("STRATEGY_PHONE", {
            {AUDIO_STREAM_VOICE_CALL, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_VOICE_COMMUNICATION)}},
            {AUDIO_STREAM_BLUETOOTH_SCO, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_UNKNOWN, AUDIO_FLAG_SCO)}},
        });
        add("STRATEGY_SONIFICATION", {
            {AUDIO_STREAM_RING, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE)}},
            {AUDIO_STREAM_ALARM, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_ALARM)}},
        });
        add("STRATEGY_ENFORCED_AUDIBLE", {
            {AUDIO_STREAM_ENFORCED_AUDIBLE, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_UNKNOWN, AUDIO_FLAG_AUDIBILITY_ENFORCED)}},
        });
        add("STRATEGY_ACCESSIBILITY", {
            {AUDIO_STREAM_ACCESSIBILITY, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY)}},
        });
        add("STRATEGY_SONIFICATION_RESPECTFUL", {
            {AUDIO_STREAM_NOTIFICATION, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_NOTIFICATION),
                                         attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_NOTIFICATION_EVENT)}},
        });
        add("STRATEGY_MEDIA", {
            {AUDIO_STREAM_ASSISTANT, {attributes(AUDIO_CONTENT_TYPE_SPEECH, AUDIO_USAGE_ASSISTANT)}},
            {AUDIO_STREAM_MUSIC, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_MEDIA),
                                  attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_GAME),
                                  attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_ASSISTANT),
                                  attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE),
                                  attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_UNKNOWN)}},
            {AUDIO_STREAM_SYSTEM, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_ASSISTANCE_SONIFICATION)}},
        });
        add("STRATEGY_DTMF", {
            {AUDIO_STREAM_DTMF, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING)}},
        });
        add("STRATEGY_CALL_ASSISTANT", {
            {AUDIO_STREAM_CALL_ASSISTANT, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_CALL_ASSISTANT)}},
        });
        add("STRATEGY_TRANSMITTED_THROUGH_SPEAKER", {
            {AUDIO_STREAM_TTS, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_UNKNOWN, AUDIO_FLAG_BEACON),
                                attributes(AUDIO_CONTENT_TYPE_ULTRASOUND, AUDIO_USAGE_UNKNOWN),
                                attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_SPEAKER_CLEANUP)}},
        });
        add("STRATEGY_REROUTING", {
            {AUDIO_STREAM_REROUTING, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_VIRTUAL_SOURCE, AUDIO_FLAG_NONE,
                                                 kApmReservedInternalTag)}},
        });
        add("STRATEGY_PATCH", {
            {AUDIO_STREAM_PATCH, {attributes(AUDIO_CONTENT_TYPE_UNKNOWN, AUDIO_USAGE_UNKNOWN, AUDIO_FLAG_NONE,
                                             kApmReservedInternalTag)}},
        });
        return list;
    }();
    return strategies;
}

const EngineAttributesGroup* engineGroupForAttributes(const audio_attributes_t& attr, bool fallbackOnDefault,
                                                      const EngineStrategy** strategy) {
    const EngineAttributesGroup* best = nullptr;
    const EngineStrategy* bestStrategy = nullptr;
    int bestScore = 0;
    for (const EngineStrategy& candidate : engineStrategies()) {
        for (const EngineAttributesGroup& group : candidate.groups) {
            for (const audio_attributes_t& reference : group.attributes) {
                const int score = AudioProductStrategy::attributesMatchesScore(reference, attr);
                if (score > bestScore) {
                    bestScore = score;
                    best = &group;
                    bestStrategy = &candidate;
                }
            }
        }
    }
    if (best == nullptr && fallbackOnDefault) {
        // The default strategy holds the initializer attributes by convention.
        for (const EngineStrategy& candidate : engineStrategies()) {
            for (const EngineAttributesGroup& group : candidate.groups) {
                for (const audio_attributes_t& reference : group.attributes) {
                    if (reference.usage == AUDIO_USAGE_UNKNOWN && reference.content_type == AUDIO_CONTENT_TYPE_UNKNOWN &&
                        reference.flags == AUDIO_FLAG_NONE && reference.tags[0] == '\0') {
                        best = &group;
                        bestStrategy = &candidate;
                    }
                }
            }
        }
    }
    if (strategy != nullptr) *strategy = bestStrategy;
    return best;
}

audio_stream_type_t streamTypeForAttributes(const audio_attributes_t& attr) {
    const EngineAttributesGroup* group = engineGroupForAttributes(attr, true, nullptr);
    return group != nullptr ? group->stream : AUDIO_STREAM_MUSIC;
}

HostAudioPolicyService::HostAudioPolicyService(std::shared_ptr<HostOutput> output) : mOutput(std::move(output)) {}

HostAudioPolicyService::~HostAudioPolicyService() = default;

// AudioPolicyManager::getOutputForAttr for the single mixed output.
status_t HostAudioPolicyService::openClient(audio_attributes_t* attr, audio_io_handle_t* output,
                                                  audio_session_t session, audio_stream_type_t* stream, uid_t uid,
                                                  audio_config_t* config, audio_output_flags_t* flags,
                                                  DeviceIdVector* selectedDeviceIds, audio_port_handle_t* portId,
                                                  float* volume, bool* muted) {
    *output = AUDIO_IO_HANDLE_NONE;
    *portId = AUDIO_PORT_HANDLE_NONE;
    if (attr->usage == AUDIO_USAGE_UNKNOWN && *stream != AUDIO_STREAM_DEFAULT && uint32_t(*stream) < AUDIO_STREAM_PUBLIC_CNT) {
        *attr = AudioSystem::streamTypeToAttributes(*stream);
    }
    if ((*flags & AUDIO_OUTPUT_FLAG_MMAP_NOIRQ) != 0) {
        ALOGW("getOutputForAttr() no MMAP output for attributes usage %d", attr->usage);
        return INVALID_OPERATION;
    }
    if (!audio_is_linear_pcm(config->format)) {
        ALOGW("getOutputForAttr() no direct output for format %#x", config->format);
        return INVALID_OPERATION;
    }
    for (audio_port_handle_t deviceId : *selectedDeviceIds) {
        if (deviceId != AUDIO_PORT_HANDLE_NONE && deviceId != mOutput->devicePortId) {
            ALOGW("getOutputForAttr() unknown selected device %d", deviceId);
            return BAD_VALUE;
        }
    }
    // Only the mixed primary output exists: direct, offload and A/V sync requests fall back to it.
    *flags = static_cast<audio_output_flags_t>(
            *flags & ~(AUDIO_OUTPUT_FLAG_DIRECT | AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD | AUDIO_OUTPUT_FLAG_HW_AV_SYNC |
                       AUDIO_OUTPUT_FLAG_DIRECT_PCM));
    *stream = streamTypeForAttributes(*attr);
    *output = mOutput->ioHandle;
    *portId = static_cast<audio_port_handle_t>(mOutput->ids.next(AUDIO_UNIQUE_ID_USE_UNSPECIFIED));
    selectedDeviceIds->clear();
    selectedDeviceIds->push_back(mOutput->devicePortId);
    *volume = 1.0f;
    *muted = false;
    std::lock_guard<std::mutex> lock(mLock);
    mClients[*portId] = Client{*attr, session, *stream, uid};
    return NO_ERROR;
}

status_t HostAudioPolicyService::startClient(audio_port_handle_t portId) {
    std::lock_guard<std::mutex> lock(mLock);
    auto client = mClients.find(portId);
    if (client == mClients.end()) {
        ALOGW("startOutput() no output for client %d", portId);
        return BAD_VALUE;
    }
    client->second.active = true;
    return NO_ERROR;
}

status_t HostAudioPolicyService::stopClient(audio_port_handle_t portId) {
    std::lock_guard<std::mutex> lock(mLock);
    auto client = mClients.find(portId);
    if (client == mClients.end()) {
        ALOGW("stopOutput() no output for client %d", portId);
        return BAD_VALUE;
    }
    if (!client->second.active) return INVALID_OPERATION;
    client->second.active = false;
    client->second.stoppedNs = systemTime(SYSTEM_TIME_MONOTONIC);
    return NO_ERROR;
}

void HostAudioPolicyService::releaseClient(audio_port_handle_t portId) {
    std::lock_guard<std::mutex> lock(mLock);
    if (mClients.erase(portId) == 0) ALOGW("releaseOutput() no output for client %d", portId);
}

std::vector<audio_port_v7> HostAudioPolicyService::ports_l(audio_port_role_t role, audio_port_type_t type) const {
    std::vector<audio_port_v7> ports;
    for (const audio_port_v7& port : {mOutput->devicePort(), mOutput->mixPort()}) {
        if ((role == AUDIO_PORT_ROLE_NONE || role == port.role) && (type == AUDIO_PORT_TYPE_NONE || type == port.type)) {
            ports.push_back(port);
        }
    }
    return ports;
}

// ---------------------------------------------------------------------------
// IAudioPolicyService

Status HostAudioPolicyService::getDeviceConnectionState(const media::audio::common::AudioDevice& device,
                                                        media::AudioPolicyDeviceState* _aidl_return) {
    audio_devices_t type;
    std::string address;
    RETURN_BINDER_STATUS_IF_ERROR(aidl2legacy_AudioDevice_audio_device(device, &type, &address));
    const audio_policy_dev_state_t state =
            type == AUDIO_DEVICE_OUT_SPEAKER ? AUDIO_POLICY_DEVICE_STATE_AVAILABLE : AUDIO_POLICY_DEVICE_STATE_UNAVAILABLE;
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_policy_dev_state_t_AudioPolicyDeviceState(state));
    return Status::ok();
}

Status HostAudioPolicyService::setPhoneState(media::audio::common::AudioMode state, int32_t /*uid*/) {
    const audio_mode_t mode = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioMode_audio_mode_t(state));
    if (uint32_t(mode) >= AUDIO_MODE_CNT) return binderStatusFromStatusT(BAD_VALUE);
    std::lock_guard<std::mutex> lock(mLock);
    mPhoneState = mode;
    return Status::ok();
}

Status HostAudioPolicyService::getPhoneState(media::audio::common::AudioMode* _aidl_return) {
    std::lock_guard<std::mutex> lock(mLock);
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_mode_t_AudioMode(mPhoneState));
    return Status::ok();
}

Status HostAudioPolicyService::setForceUse(media::AudioPolicyForceUse usage, media::AudioPolicyForcedConfig config) {
    const audio_policy_force_use_t legacyUsage = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioPolicyForceUse_audio_policy_force_use_t(usage));
    const audio_policy_forced_cfg_t legacyConfig =
            VALUE_OR_RETURN_BINDER(aidl2legacy_AudioPolicyForcedConfig_audio_policy_forced_cfg_t(config));
    std::lock_guard<std::mutex> lock(mLock);
    mForceUse[legacyUsage] = legacyConfig;
    return Status::ok();
}

Status HostAudioPolicyService::getForceUse(media::AudioPolicyForceUse usage, media::AudioPolicyForcedConfig* _aidl_return) {
    const audio_policy_force_use_t legacyUsage = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioPolicyForceUse_audio_policy_force_use_t(usage));
    audio_policy_forced_cfg_t config = AUDIO_POLICY_FORCE_NONE;
    {
        std::lock_guard<std::mutex> lock(mLock);
        auto it = mForceUse.find(legacyUsage);
        if (it != mForceUse.end()) config = it->second;
    }
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_policy_forced_cfg_t_AudioPolicyForcedConfig(config));
    return Status::ok();
}

Status HostAudioPolicyService::getOutputForAttr(const media::audio::common::AudioAttributes& attrAidl, int32_t sessionAidl,
                                                const content::AttributionSourceState& attributionSource,
                                                const media::audio::common::AudioConfig& configAidl, int32_t flagsAidl,
                                                const std::vector<int32_t>& selectedDeviceIdsAidl,
                                                media::GetOutputForAttrResponse* _aidl_return) {
    audio_attributes_t attr = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioAttributes_audio_attributes_t(attrAidl));
    const audio_session_t session = VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_audio_session_t(sessionAidl));
    audio_stream_type_t stream = AUDIO_STREAM_DEFAULT;
    audio_config_t config = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioConfig_audio_config_t(configAidl, false /*isInput*/));
    audio_output_flags_t flags = VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_audio_output_flags_t_mask(flagsAidl));
    DeviceIdVector selectedDeviceIds = VALUE_OR_RETURN_BINDER(
            convertContainer<DeviceIdVector>(selectedDeviceIdsAidl, aidl2legacy_int32_t_audio_port_handle_t));
    const uid_t uid = VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_uid_t(attributionSource.uid));
    audio_io_handle_t output;
    audio_port_handle_t portId;
    float volume;
    bool muted;
    RETURN_BINDER_STATUS_IF_ERROR(openClient(&attr, &output, session, &stream, uid, &config, &flags,
                                                   &selectedDeviceIds, &portId, &volume, &muted));
    _aidl_return->output = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_io_handle_t_int32_t(output));
    _aidl_return->stream = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_stream_type_t_AudioStreamType(stream));
    _aidl_return->selectedDeviceIds = VALUE_OR_RETURN_BINDER(
            convertContainer<std::vector<int32_t>>(selectedDeviceIds, legacy2aidl_audio_port_handle_t_int32_t));
    _aidl_return->portId = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_port_handle_t_int32_t(portId));
    _aidl_return->secondaryOutputs.clear();
    _aidl_return->isSpatialized = false;
    const audio_config_base_t configBase = {config.sample_rate, config.channel_mask, config.format};
    _aidl_return->configBase = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_config_base_t_AudioConfigBase(configBase, false));
    _aidl_return->isBitPerfect = false;
    _aidl_return->attr = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_attributes_t_AudioAttributes(attr));
    _aidl_return->volume = volume;
    _aidl_return->muted = muted;
    return Status::ok();
}

// AudioPolicyManager::getOutput: every stream's strategy routes to the primary output.
Status HostAudioPolicyService::getOutput(media::audio::common::AudioStreamType streamAidl, int32_t* _aidl_return) {
    const audio_stream_type_t stream = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioStreamType_audio_stream_type_t(streamAidl));
    if (uint32_t(stream) >= AUDIO_STREAM_PUBLIC_CNT) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_io_handle_t_int32_t(mOutput->ioHandle));
    return Status::ok();
}

// The patch created when the primary output opened on the speaker device.
Status HostAudioPolicyService::listAudioPatches(media::audio::common::Int* count, std::vector<media::AudioPatchFw>* patches,
                                                int32_t* _aidl_return) {
    if (count->value < 0) return binderStatusFromStatusT(BAD_VALUE);
    patches->clear();
    if (count->value > 0) patches->push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_patch_AudioPatchFw(mOutput->patch())));
    count->value = 1;
    *_aidl_return = 1;
    return Status::ok();
}

Status HostAudioPolicyService::getStrategyForStream(media::audio::common::AudioStreamType streamAidl, int32_t* _aidl_return) {
    const audio_stream_type_t stream = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioStreamType_audio_stream_type_t(streamAidl));
    for (const EngineStrategy& strategy : engineStrategies()) {
        for (const EngineAttributesGroup& group : strategy.groups) {
            if (group.stream == stream) {
                *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_product_strategy_t_int32_t(strategy.id));
                return Status::ok();
            }
        }
    }
    return binderStatusFromStatusT(BAD_VALUE);
}

Status HostAudioPolicyService::listAudioProductStrategies(std::vector<media::AudioProductStrategy>* _aidl_return) {
    _aidl_return->clear();
    for (const EngineStrategy& strategy : engineStrategies()) {
        media::AudioProductStrategy aidl;
        aidl.id = VALUE_OR_RETURN_BINDER(legacy2aidl_product_strategy_t_int32_t(strategy.id));
        aidl.name = strategy.name;
        for (const EngineAttributesGroup& group : strategy.groups) {
            for (const audio_attributes_t& attr : group.attributes) {
                media::AudioAttributesEx ex;
                ex.attributes = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_attributes_t_AudioAttributes(attr));
                ex.streamType = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_stream_type_t_AudioStreamType(group.stream));
                ex.groupId = VALUE_OR_RETURN_BINDER(legacy2aidl_volume_group_t_int32_t(group.volumeGroup));
                aidl.audioAttributes.push_back(std::move(ex));
            }
        }
        _aidl_return->push_back(std::move(aidl));
    }
    return Status::ok();
}

Status HostAudioPolicyService::getProductStrategyFromAudioAttributes(const media::audio::common::AudioAttributes& aa,
                                                                     bool fallbackOnDefault, int32_t* _aidl_return) {
    const audio_attributes_t attr = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioAttributes_audio_attributes_t(aa));
    const EngineStrategy* strategy = nullptr;
    if (engineGroupForAttributes(attr, fallbackOnDefault, &strategy) == nullptr) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_product_strategy_t_int32_t(strategy->id));
    return Status::ok();
}

Status HostAudioPolicyService::listAudioVolumeGroups(std::vector<media::AudioVolumeGroup>* _aidl_return) {
    _aidl_return->clear();
    for (const EngineStrategy& strategy : engineStrategies()) {
        for (const EngineAttributesGroup& group : strategy.groups) {
            media::AudioVolumeGroup aidl;
            aidl.groupId = VALUE_OR_RETURN_BINDER(legacy2aidl_volume_group_t_int32_t(group.volumeGroup));
            aidl.name = audio_stream_type_to_string(group.stream);
            for (const audio_attributes_t& attr : group.attributes) {
                aidl.audioAttributes.push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_attributes_t_AudioAttributes(attr)));
            }
            aidl.streams.push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_stream_type_t_AudioStreamType(group.stream)));
            _aidl_return->push_back(std::move(aidl));
        }
    }
    return Status::ok();
}

Status HostAudioPolicyService::getVolumeGroupFromAudioAttributes(const media::audio::common::AudioAttributes& aa,
                                                                 bool fallbackOnDefault, int32_t* _aidl_return) {
    const audio_attributes_t attr = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioAttributes_audio_attributes_t(aa));
    const EngineAttributesGroup* group = engineGroupForAttributes(attr, fallbackOnDefault, nullptr);
    if (group == nullptr) return binderStatusFromStatusT(BAD_VALUE);
    *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_volume_group_t_int32_t(group->volumeGroup));
    return Status::ok();
}

Status HostAudioPolicyService::getDevicesForRoleAndStrategy(int32_t /*strategy*/, media::DeviceRole /*role*/,
                                                            std::vector<media::audio::common::AudioDevice>* /*_aidl_return*/) {
    return binderStatusFromStatusT(NAME_NOT_FOUND);  // Engine: no device roles are configured
}

Status HostAudioPolicyService::startOutput(int32_t portId) {
    return binderStatusFromStatusT(startClient(VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_audio_port_handle_t(portId))));
}

Status HostAudioPolicyService::stopOutput(int32_t portId) {
    return binderStatusFromStatusT(stopClient(VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_audio_port_handle_t(portId))));
}

Status HostAudioPolicyService::releaseOutput(int32_t portId) {
    releaseClient(VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_audio_port_handle_t(portId)));
    return Status::ok();
}

Status HostAudioPolicyService::getDevicesForAttributes(const media::audio::common::AudioAttributes& /*attr*/, bool /*forVolume*/,
                                                       std::vector<media::audio::common::AudioDevice>* _aidl_return) {
    _aidl_return->clear();
    _aidl_return->push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_device_AudioDevice(AUDIO_DEVICE_OUT_SPEAKER, "")));
    return Status::ok();
}

Status HostAudioPolicyService::isStreamActive(media::audio::common::AudioStreamType streamAidl, int32_t inPastMs, bool* _aidl_return) {
    const audio_stream_type_t stream = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioStreamType_audio_stream_type_t(streamAidl));
    const int64_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    std::lock_guard<std::mutex> lock(mLock);
    *_aidl_return = std::any_of(mClients.begin(), mClients.end(), [&](const auto& entry) {
        const Client& client = entry.second;
        if (client.stream != stream) return false;
        return client.active || (client.stoppedNs != 0 && now - client.stoppedNs <= int64_t(inPastMs) * 1000000);
    });
    return Status::ok();
}

Status HostAudioPolicyService::isStreamActiveRemotely(media::audio::common::AudioStreamType /*stream*/, int32_t /*inPastMs*/,
                                                      bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioPolicyService::isSourceActive(media::audio::common::AudioSource /*source*/, bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioPolicyService::setSupportedSystemUsages(const std::vector<media::audio::common::AudioUsage>& /*systemUsages*/) {
    return Status::ok();
}

Status HostAudioPolicyService::getOffloadSupport(const media::audio::common::AudioOffloadInfo& /*info*/,
                                                 media::AudioOffloadMode* _aidl_return) {
    *_aidl_return = media::AudioOffloadMode::NOT_SUPPORTED;
    return Status::ok();
}

Status HostAudioPolicyService::isDirectOutputSupported(const media::audio::common::AudioConfigBase& /*config*/,
                                                       const media::audio::common::AudioAttributes& /*attributes*/,
                                                       bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

// AudioPolicyManager::listAudioPorts: `count` carries the caller's capacity in and the total out.
Status HostAudioPolicyService::listAudioPorts(media::AudioPortRole roleAidl, media::AudioPortType typeAidl,
                                              media::audio::common::Int* count, std::vector<media::AudioPortFw>* ports,
                                              int32_t* _aidl_return) {
    const audio_port_role_t role = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioPortRole_audio_port_role_t(roleAidl));
    const audio_port_type_t type = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioPortType_audio_port_type_t(typeAidl));
    if (count->value < 0) return binderStatusFromStatusT(BAD_VALUE);
    std::vector<audio_port_v7> matching;
    {
        std::lock_guard<std::mutex> lock(mLock);
        matching = ports_l(role, type);
    }
    const size_t capacity = static_cast<size_t>(count->value);
    ports->clear();
    for (size_t i = 0; i < std::min(capacity, matching.size()); i++) {
        ports->push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_port_v7_AudioPortFw(matching[i])));
    }
    count->value = static_cast<int32_t>(matching.size());
    *_aidl_return = 1;  // port generation: the host topology never changes
    return Status::ok();
}

Status HostAudioPolicyService::listDeclaredDevicePorts(media::AudioPortRole roleAidl, std::vector<media::AudioPortFw>* _aidl_return) {
    const audio_port_role_t role = VALUE_OR_RETURN_BINDER(aidl2legacy_AudioPortRole_audio_port_role_t(roleAidl));
    _aidl_return->clear();
    if (role == AUDIO_PORT_ROLE_NONE || role == AUDIO_PORT_ROLE_SINK) {
        _aidl_return->push_back(VALUE_OR_RETURN_BINDER(legacy2aidl_audio_port_v7_AudioPortFw(mOutput->devicePort())));
    }
    return Status::ok();
}

Status HostAudioPolicyService::getAudioPort(int32_t portId, media::AudioPortFw* _aidl_return) {
    const audio_port_handle_t id = VALUE_OR_RETURN_BINDER(aidl2legacy_int32_t_audio_port_handle_t(portId));
    for (const audio_port_v7& port : ports_l(AUDIO_PORT_ROLE_NONE, AUDIO_PORT_TYPE_NONE)) {
        if (port.id == id) {
            *_aidl_return = VALUE_OR_RETURN_BINDER(legacy2aidl_audio_port_v7_AudioPortFw(port));
            return Status::ok();
        }
    }
    return binderStatusFromStatusT(BAD_VALUE);
}

Status HostAudioPolicyService::registerClient(const sp<media::IAudioPolicyServiceClient>& client) {
    if (client == nullptr) return Status::ok();
    std::lock_guard<std::mutex> lock(mLock);
    mNotificationClients.push_back(client);
    return Status::ok();
}

Status HostAudioPolicyService::setAudioPortCallbacksEnabled(bool /*enabled*/) {
    return Status::ok();
}

Status HostAudioPolicyService::setAudioVolumeGroupCallbacksEnabled(bool /*enabled*/) {
    return Status::ok();
}

Status HostAudioPolicyService::getMasterMono(bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioPolicyService::isHapticPlaybackSupported(bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioPolicyService::isUltrasoundSupported(bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioPolicyService::canBeSpatialized(const std::optional<media::audio::common::AudioAttributes>& /*attr*/,
                                                const std::optional<media::audio::common::AudioConfig>& /*config*/,
                                                const std::vector<media::audio::common::AudioDevice>& /*devices*/,
                                                bool* _aidl_return) {
    *_aidl_return = false;
    return Status::ok();
}

Status HostAudioPolicyService::getDirectPlaybackSupport(const media::audio::common::AudioAttributes& /*attr*/,
                                                        const media::audio::common::AudioConfig& /*config*/,
                                                        media::AudioDirectMode* _aidl_return) {
    *_aidl_return = media::AudioDirectMode::NONE;
    return Status::ok();
}

}  // namespace artzig::audio
