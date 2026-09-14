// Follows pinned C2SoftMp3Dec/C2SoftAacDec interface and work delivery, plus
// C2SoftOpusDec, C2SoftFlacDec and C2SoftVorbisDec configuration handling. Their
// decoder libraries are replaced at the component boundary by system FFmpeg;
// SimpleC2Component retains queue, listener, state and flush-generation handling.
#include "FfmpegComponent.h"
#include "../../codec/azk_codec.h"
#include <SimpleC2Component.h>
#include <SimpleC2Interface.h>
#include <media/stagefright/foundation/OpusHeader.h>
#include <algorithm>
#include <cstring>
#include <limits>

namespace azk::codec2 {
using namespace android;
namespace {
struct Format { const char *name, *decoder, *mime; uint32_t rate; };
constexpr Format formats[] = {
    {"c2.artzig.aac.decoder", "aac", "audio/mp4a-latm", 44100},
    {"c2.artzig.opus.decoder", "opus", "audio/opus", 48000},
    {"c2.artzig.mp3.decoder", "mp3", "audio/mpeg", 44100},
    {"c2.artzig.flac.decoder", "flac", "audio/flac", 44100},
    {"c2.artzig.vorbis.decoder", "vorbis", "audio/vorbis", 44100},
};

class Interface final : public SimpleInterface<void>::BaseParams {
public:
    Interface(const Format &format, const std::shared_ptr<C2ReflectorHelper> &reflector)
        : BaseParams(reflector, format.name, C2Component::KIND_DECODER,
                     C2Component::DOMAIN_AUDIO, format.mime) {
        noPrivateBuffers(); noInputReferences(); noOutputReferences();
        noInputLatency(); noPipelineLatency(); noTimeStretch();
        setDerivedInstance(this);
        addParameter(DefineParam(mAttrib, C2_PARAMKEY_COMPONENT_ATTRIBUTES)
            .withConstValue(new C2ComponentAttributesSetting(C2Component::ATTRIB_IS_TEMPORAL)).build());
        addParameter(DefineParam(mActualOutputDelay, C2_PARAMKEY_OUTPUT_DELAY)
            .withConstValue(new C2PortActualDelayTuning::output(1)).build());
        addParameter(DefineParam(rate, C2_PARAMKEY_SAMPLE_RATE)
            .withDefault(new C2StreamSampleRateInfo::output(0u, format.rate))
            .withFields({C2F(rate, value).inRange(8000, 192000)})
            .withSetter(Setter<decltype(*rate)>::StrictValueWithNoDeps).build());
        addParameter(DefineParam(channels, C2_PARAMKEY_CHANNEL_COUNT)
            .withDefault(new C2StreamChannelCountInfo::output(0u, 2))
            .withFields({C2F(channels, value).inRange(1, 8)})
            .withSetter(Setter<decltype(*channels)>::StrictValueWithNoDeps).build());
        addParameter(DefineParam(pcm, C2_PARAMKEY_PCM_ENCODING)
            .withConstValue(new C2StreamPcmEncodingInfo::output(0u, C2Config::PCM_16)).build());
        addParameter(DefineParam(maxInput, C2_PARAMKEY_INPUT_MAX_BUFFER_SIZE)
            .withConstValue(new C2StreamMaxBufferSizeInfo::input(0u, 4 * 1024 * 1024)).build());
    }
    std::shared_ptr<C2StreamSampleRateInfo::output> rate;
    std::shared_ptr<C2StreamChannelCountInfo::output> channels;
    std::pair<uint32_t, uint32_t> audioConfig() const {
        auto guard = lock();
        return {rate->value, channels->value};
    }
private:
    std::shared_ptr<C2StreamPcmEncodingInfo::output> pcm;
    std::shared_ptr<C2StreamMaxBufferSizeInfo::input> maxInput;
};

class Component final : public SimpleC2Component {
public:
    Component(const Format &format, c2_node_id_t id, std::shared_ptr<Interface> params)
        : SimpleC2Component(std::make_shared<SimpleInterface<Interface>>(format.name, id, params)),
          mFormat(format), mParams(std::move(params)) {}
    ~Component() override { onRelease(); }

private:
    c2_status_t onInit() override {
        mDecoder = azk_codec_open(mFormat.decoder);
        return mDecoder ? C2_OK : C2_NO_MEMORY;
    }
    c2_status_t onFlush_sm() override {
        if (mConfigured) azk_codec_flush(mDecoder);
        mDiscard = mPreRoll;
        mEos = false;
        mError = C2_OK;
        return C2_OK;
    }
    c2_status_t onStop() override { onReset(); return C2_OK; }
    void onReset() override {
        onRelease(); mCsd.clear(); mOpusHeaders = 0;
        mDelay = mPreRoll = mDiscard = 0;
    }
    void onRelease() override {
        if (mDecoder) azk_codec_close(mDecoder);
        mDecoder = nullptr;
        mConfigured = mEos = false;
        mError = C2_OK;
        mOutputUpdates.clear();
    }

    bool opus() const { return std::strcmp(mFormat.decoder, "opus") == 0; }

    static bool samples(const void *data, size_t size, uint64_t *value) {
        if (!data || size != 8) return false;
        uint64_t ns;
        std::memcpy(&ns, data, 8);
        // C2SoftOpusDec converts nanoseconds to samples at 48 kHz.
        if (ns > uint64_t(INT64_MAX) / 48000) return false;
        *value = ns * 48000 / 1000000000;
        return true;
    }

    c2_status_t configData(const std::vector<uint8_t> &input) {
        if (mConfigured) return C2_BAD_VALUE;
        if (opus()) {
            if (mOpusHeaders == 0) {
                void *header, *delay, *preRoll;
                size_t headerSize, delaySize, preRollSize;
                if (!GetOpusHeaderBuffers(input.data(), input.size(), &header, &headerSize,
                        &delay, &delaySize, &preRoll, &preRollSize)) return C2_BAD_VALUE;
                OpusHeader parsed;
                if (!ParseOpusHeader(static_cast<uint8_t *>(header), headerSize, &parsed)) return C2_BAD_VALUE;
                mCsd.assign(static_cast<uint8_t *>(header), static_cast<uint8_t *>(header) + headerSize);
                // Android carries delay separately. Disable FFmpeg's built-in
                // OpusHead skip so C2's initial delay and seek preroll apply once.
                mCsd[10] = mCsd[11] = 0;
                mOpusHeaders = 1;
                if (delay && samples(delay, delaySize, &mDelay)) ++mOpusHeaders;
                else if (delay) return C2_BAD_VALUE;
                if (preRoll && samples(preRoll, preRollSize, &mPreRoll)) ++mOpusHeaders;
                else if (preRoll) return C2_BAD_VALUE;
            } else if (mOpusHeaders < 3) {
                if (!samples(input.data(), input.size(), mOpusHeaders == 1 ? &mDelay : &mPreRoll)) return C2_BAD_VALUE;
                ++mOpusHeaders;
            } else return C2_BAD_VALUE;
            mDiscard = mDelay;
            return C2_OK;
        }
        if (input.size() > 4 * 1024 * 1024 - mCsd.size()) return C2_BAD_VALUE;
        mCsd.insert(mCsd.end(), input.begin(), input.end());
        return C2_OK;
    }

    c2_status_t openDecoder() {
        if (!mDecoder && onInit() != C2_OK) return C2_NO_MEMORY;
        std::vector<uint8_t> extra = mCsd;
        if (opus() && mOpusHeaders != 3) return C2_BAD_VALUE;
        if (std::strcmp(mFormat.decoder, "vorbis") == 0) {
            // C2SoftVorbisDec accepts identification and setup packets. FFmpeg
            // requires Xiph lacing and a comment packet, whose text is irrelevant.
            if (mCsd.size() < 37 || mCsd[0] != 1 || mCsd[30] != 5 ||
                    std::memcmp(mCsd.data() + 1, "vorbis", 6) ||
                    std::memcmp(mCsd.data() + 31, "vorbis", 6)) return C2_BAD_VALUE;
            constexpr uint8_t comment[] = {3,'v','o','r','b','i','s',0,0,0,0,0,0,0,0,1};
            extra = {2, 30, sizeof(comment)};
            extra.insert(extra.end(), mCsd.begin(), mCsd.begin() + 30);
            extra.insert(extra.end(), std::begin(comment), std::end(comment));
            extra.insert(extra.end(), mCsd.begin() + 30, mCsd.end());
        } else if (std::strcmp(mFormat.decoder, "flac") == 0 && extra.size() >= 4 &&
                   std::memcmp(extra.data(), "fLaC", 4) == 0) {
            // Android's FLAC CSD includes the stream marker and STREAMINFO
            // block header; libavcodec takes the 34 STREAMINFO bytes alone.
            if (extra.size() < 42 || (extra[4] & 0x7f) != 0 || extra[5] || extra[6] || extra[7] != 34) return C2_BAD_VALUE;
            extra = std::vector<uint8_t>(mCsd.begin() + 8, mCsd.begin() + 42);
        }
        const auto [rate, channels] = mParams->audioConfig();
        if (azk_codec_configure(mDecoder, extra.data(), extra.size(), rate, channels) < 0) return C2_CORRUPTED;
        mConfigured = true;
        return C2_OK;
    }

    static void complete(const std::unique_ptr<C2Work> &work, c2_status_t status) {
        work->result = status;
        work->workletsProcessed = 1;
        auto &output = work->worklets.front()->output;
        output.flags = C2FrameData::flags_t(work->input.flags & C2FrameData::FLAG_END_OF_STREAM);
        output.ordinal = work->input.ordinal;
        output.buffers.clear();
        output.configUpdate.clear();
    }

    // During drain there is no current work. An empty local carrier lets the
    // original cloneAndSend find the pending ordinal without inventing a PTS.
    void emit(uint64_t index, int64_t pts, std::shared_ptr<C2Buffer> buffer,
              std::vector<std::unique_ptr<C2Param>> updates,
              const std::unique_ptr<C2Work> &current) {
        std::unique_ptr<C2Work> carrier;
        if (!current) {
            carrier = std::make_unique<C2Work>();
            carrier->input.ordinal.frameIndex = index ^ 1;
        }
        cloneAndSend(index, current ? current : carrier, [&](const std::unique_ptr<C2Work> &work) {
            complete(work, C2_OK);
            auto &output = work->worklets.front()->output;
            output.flags = C2FrameData::FLAG_INCOMPLETE;
            output.ordinal.timestamp = pts;
            output.buffers.push_back(std::move(buffer));
            output.configUpdate = std::move(updates);
        });
    }

    c2_status_t outputs(const std::shared_ptr<C2BlockPool> &pool,
                        const std::unique_ptr<C2Work> &current) {
        for (;;) {
            int meta[3] = {};
            int64_t pts = 0;
            uint64_t index = 0;
            // The insufficient-capacity result leaves output and its work
            // reference queued, allowing allocation from the supplied C2 pool.
            uint8_t unused;
            int result = azk_codec_receive_audio_work(mDecoder, &unused, 0, meta, &pts, &index);
            if (result == 1 || result == 2) break;
            if (result != -2 || meta[0] <= 0) return C2_CORRUPTED;
            std::shared_ptr<C2LinearBlock> block;
            c2_status_t status = pool->fetchLinearBlock(meta[0],
                C2MemoryUsage(C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE), &block);
            if (status != C2_OK) return status;
            C2WriteView view = block->map().get();
            if (view.error() != C2_OK) return view.error();
            result = azk_codec_receive_audio_work(mDecoder, view.data(), meta[0], meta, &pts, &index);
            if (result != 0) return C2_CORRUPTED;
            std::vector<std::unique_ptr<C2Param>> updates;
            if (mParams->audioConfig() != std::make_pair(uint32_t(meta[1]), uint32_t(meta[2]))) {
                C2StreamSampleRateInfo::output rate(0u, meta[1]);
                C2StreamChannelCountInfo::output channels(0u, meta[2]);
                std::vector<std::unique_ptr<C2SettingResult>> failures;
                status = intf()->config_vb({&rate, &channels}, C2_MAY_BLOCK, &failures);
                if (status != C2_OK || !failures.empty()) return C2_CORRUPTED;
                updates.emplace_back(C2Param::Copy(rate));
                updates.emplace_back(C2Param::Copy(channels));
            }
            if (!updates.empty()) mOutputUpdates = std::move(updates);
            const size_t sampleBytes = size_t(meta[2]) * 2;
            const size_t skip = std::min<uint64_t>(mDiscard, size_t(meta[0]) / sampleBytes);
            mDiscard -= skip;
            const size_t offset = skip * sampleBytes;
            if (offset < size_t(meta[0])) {
                emit(index, pts, createLinearBuffer(block, offset, meta[0] - offset), std::move(mOutputUpdates), current);
            }
        }
        uint64_t index;
        while (azk_codec_poll_completed_work(mDecoder, &index) == 1) {
            if (current && current->input.ordinal.frameIndex == index) complete(current, C2_OK);
            else finish(index, [](const std::unique_ptr<C2Work> &work) { complete(work, C2_OK); });
        }
        return C2_OK;
    }

    void process(const std::unique_ptr<C2Work> &work,
                 const std::shared_ptr<C2BlockPool> &pool) override {
        work->result = C2_OK;
        work->workletsProcessed = 0;
        if (mError != C2_OK || mEos) { complete(work, mError != C2_OK ? mError : C2_BAD_VALUE); return; }
        std::vector<uint8_t> input;
        for (const auto &buffer : work->input.buffers) {
            if (!buffer) continue;
            const auto blocks = buffer->data().linearBlocks();
            if (blocks.empty()) { complete(work, C2_BAD_VALUE); return; }
            for (const auto &block : blocks) {
                const C2ReadView view = block.map().get();
                if (view.error() != C2_OK) { complete(work, view.error()); return; }
                if (view.capacity() > 4 * 1024 * 1024 - input.size()) { complete(work, C2_BAD_VALUE); return; }
                input.insert(input.end(), view.data(), view.data() + view.capacity());
            }
        }
        if (work->input.flags & C2FrameData::FLAG_CODEC_CONFIG) {
            mError = configData(input);
            complete(work, mError);
            return;
        }
        if (!mConfigured) {
            mError = openDecoder();
            if (mError != C2_OK) { complete(work, mError); return; }
        }
        if (!input.empty() && azk_codec_send_work(mDecoder, input.data(), input.size(),
                work->input.ordinal.timestamp.peekll(), work->input.ordinal.frameIndex.peeku()) < 0) {
            mError = C2_CORRUPTED; complete(work, mError); return;
        }
        if (work->input.flags & C2FrameData::FLAG_END_OF_STREAM) {
            if (azk_codec_send(mDecoder, nullptr, 0, 0, 1) < 0) {
                mError = C2_CORRUPTED; complete(work, mError); return;
            }
            mEos = true;
        }
        mError = outputs(pool, work);
        if (mError != C2_OK || input.empty()) complete(work, mError);
    }

    c2_status_t drain(uint32_t mode, const std::shared_ptr<C2BlockPool> &pool) override {
        if (mode == NO_DRAIN) return C2_OK;
        if (mode == DRAIN_CHAIN) return C2_OMITTED;
        if (!mConfigured) return C2_OK;
        if (azk_codec_send(mDecoder, nullptr, 0, 0, 1) < 0) return C2_CORRUPTED;
        c2_status_t status = outputs(pool, nullptr);
        if (status == C2_OK && mode == DRAIN_COMPONENT_NO_EOS) azk_codec_flush(mDecoder);
        else mEos = true;
        return status;
    }

    const Format &mFormat;
    std::shared_ptr<Interface> mParams;
    AzkCodecDecoder *mDecoder = nullptr;
    std::vector<uint8_t> mCsd;
    bool mConfigured = false, mEos = false;
    c2_status_t mError = C2_OK;
    unsigned mOpusHeaders = 0;
    uint64_t mDelay = 0, mPreRoll = 0, mDiscard = 0;
    std::vector<std::unique_ptr<C2Param>> mOutputUpdates;
};
} // namespace

c2_status_t createAudioComponent(const C2String &name, c2_node_id_t id,
        const std::shared_ptr<C2ReflectorHelper> &reflector,
        std::shared_ptr<C2Component> *component) {
    if (!component || !reflector) return C2_BAD_VALUE;
    component->reset();
    for (const auto &format : formats) if (name == format.name) {
        *component = std::make_shared<Component>(format, id, std::make_shared<Interface>(format, reflector));
        return C2_OK;
    }
    return C2_NOT_FOUND;
}

c2_status_t createAudioInterface(const C2String &name, c2_node_id_t id,
        const std::shared_ptr<C2ReflectorHelper> &reflector,
        std::shared_ptr<C2ComponentInterface> *interface) {
    if (!interface || !reflector) return C2_BAD_VALUE;
    interface->reset();
    for (const auto &format : formats) if (name == format.name) {
        *interface = std::make_shared<SimpleInterface<Interface>>(format.name, id,
                std::make_shared<Interface>(format, reflector));
        return C2_OK;
    }
    return C2_NOT_FOUND;
}

std::vector<std::shared_ptr<const C2Component::Traits>> audioTraits() {
    std::vector<std::shared_ptr<const C2Component::Traits>> result;
    for (const auto &format : formats) {
        auto traits = std::make_shared<C2Component::Traits>();
        traits->name = format.name;
        traits->kind = C2Component::KIND_DECODER;
        traits->domain = C2Component::DOMAIN_AUDIO;
        traits->mediaType = format.mime;
        traits->rank = 8;
        result.push_back(std::move(traits));
    }
    return result;
}
} // namespace azk::codec2
