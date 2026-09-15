// Follows pinned C2SoftMp3Dec/C2SoftAacDec interface and work delivery, plus
// C2SoftOpusDec, C2SoftFlacDec and C2SoftVorbisDec configuration handling. Their
// decoder libraries are replaced at the component boundary by system FFmpeg;
// SimpleC2Component retains queue, listener, state and flush-generation handling.
// Video follows C2SoftAvcDec's graphic output contract, with system VAAPI
// converting directly into the supplied pool's RGBX buffer for display, or
// reading YUV planes for explicitly requested CPU output.
#define LOG_TAG "AzkCodec2"
#include <log/log.h>
#include "FfmpegComponent.h"
#include "../../codec/azk_codec.h"
#include <SimpleC2Component.h>
#include <SimpleC2Interface.h>
#include <C2AllocatorGralloc.h>
#include <android-C2Buffer.h>
#include <Codec2Mapper.h>
#include <azk/graphics_buffer.h>
#include <media/stagefright/foundation/OpusHeader.h>
#include <algorithm>
#include <cstring>
#include <limits>
#include <tuple>

namespace azk::codec2 {
using namespace android;
namespace {
struct Format {
    const char *name, *decoder, *mime; uint32_t rate;
    uint32_t profiles = 0;
    bool video() const { return rate == 0; }
};
constexpr Format formats[] = {
    {"c2.artzig.aac.decoder", "aac", "audio/mp4a-latm", 44100},
    {"c2.artzig.opus.decoder", "opus", "audio/opus", 48000},
    {"c2.artzig.mp3.decoder", "mp3", "audio/mpeg", 44100},
    {"c2.artzig.flac.decoder", "flac", "audio/flac", 44100},
    {"c2.artzig.vorbis.decoder", "vorbis", "audio/vorbis", 44100},
    {"c2.artzig.avc.decoder", "h264", "video/avc", 0},
    {"c2.artzig.vp9.decoder", "vp9", "video/x-vnd.on2.vp9", 0},
    {"c2.artzig.mpeg4.decoder", "mpeg4", "video/mp4v-es", 0},
};

const std::vector<Format> &availableFormats() {
    static const auto available = [] {
        std::vector<Format> result;
        for (auto format : formats) {
            if (format.video()) format.profiles = azk_codec_video_profiles(format.decoder);
            if (!format.video() || format.profiles) result.push_back(format);
        }
        return result;
    }();
    return available;
}

class Interface final : public SimpleInterface<void>::BaseParams {
public:
    Interface(const Format &format, const std::shared_ptr<C2ReflectorHelper> &reflector)
        : BaseParams(reflector, format.name, C2Component::KIND_DECODER,
                     format.video() ? C2Component::DOMAIN_VIDEO : C2Component::DOMAIN_AUDIO, format.mime) {
        noPrivateBuffers(); noInputReferences(); noOutputReferences();
        noInputLatency(); noTimeStretch();
        if (!format.video()) noPipelineLatency();
        setDerivedInstance(this);
        addParameter(DefineParam(mAttrib, C2_PARAMKEY_COMPONENT_ATTRIBUTES)
            .withConstValue(new C2ComponentAttributesSetting(C2Component::ATTRIB_IS_TEMPORAL)).build());
        addParameter(DefineParam(mActualOutputDelay, C2_PARAMKEY_OUTPUT_DELAY)
            .withConstValue(new C2PortActualDelayTuning::output(format.video() ? 34 : 1)).build());
        addParameter(DefineParam(maxInput, C2_PARAMKEY_INPUT_MAX_BUFFER_SIZE)
            .withConstValue(new C2StreamMaxBufferSizeInfo::input(0u, 4 * 1024 * 1024)).build());
        std::vector<uint32_t> profiles;
        std::vector<uint32_t> levels;
        if (std::strcmp(format.decoder, "h264") == 0) {
            if (format.profiles & 1) profiles.push_back(C2Config::PROFILE_AVC_BASELINE);
            if (format.profiles & 2) profiles.push_back(C2Config::PROFILE_AVC_MAIN);
            if (format.profiles & 4) profiles.push_back(C2Config::PROFILE_AVC_HIGH);
            levels = {C2Config::LEVEL_AVC_1, C2Config::LEVEL_AVC_1B, C2Config::LEVEL_AVC_1_1,
                C2Config::LEVEL_AVC_1_2, C2Config::LEVEL_AVC_1_3, C2Config::LEVEL_AVC_2,
                C2Config::LEVEL_AVC_2_1, C2Config::LEVEL_AVC_2_2, C2Config::LEVEL_AVC_3,
                C2Config::LEVEL_AVC_3_1, C2Config::LEVEL_AVC_3_2, C2Config::LEVEL_AVC_4,
                C2Config::LEVEL_AVC_4_1, C2Config::LEVEL_AVC_4_2, C2Config::LEVEL_AVC_5,
                C2Config::LEVEL_AVC_5_1, C2Config::LEVEL_AVC_5_2};
        } else if (std::strcmp(format.decoder, "vp9") == 0) {
            profiles = {C2Config::PROFILE_VP9_0};
            levels = {C2Config::LEVEL_VP9_1, C2Config::LEVEL_VP9_1_1, C2Config::LEVEL_VP9_2,
                C2Config::LEVEL_VP9_2_1, C2Config::LEVEL_VP9_3, C2Config::LEVEL_VP9_3_1,
                C2Config::LEVEL_VP9_4, C2Config::LEVEL_VP9_4_1, C2Config::LEVEL_VP9_5,
                C2Config::LEVEL_VP9_5_1, C2Config::LEVEL_VP9_5_2};
        } else if (std::strcmp(format.decoder, "mpeg4") == 0) {
            if (format.profiles & 1) profiles.push_back(C2Config::PROFILE_MP4V_SIMPLE);
            if (format.profiles & 2) profiles.push_back(C2Config::PROFILE_MP4V_ADVANCED_SIMPLE);
            levels = {C2Config::LEVEL_MP4V_0, C2Config::LEVEL_MP4V_0B, C2Config::LEVEL_MP4V_1,
                C2Config::LEVEL_MP4V_2, C2Config::LEVEL_MP4V_3, C2Config::LEVEL_MP4V_3B,
                C2Config::LEVEL_MP4V_4, C2Config::LEVEL_MP4V_4A, C2Config::LEVEL_MP4V_5};
        } else if (std::strcmp(format.decoder, "aac") == 0) {
            profiles = {C2Config::PROFILE_AAC_LC, C2Config::PROFILE_AAC_HE, C2Config::PROFILE_AAC_HE_PS};
            levels = {C2Config::LEVEL_UNUSED};
        }
        if (!profiles.empty()) {
            addParameter(DefineParam(profileLevel, C2_PARAMKEY_PROFILE_LEVEL)
                .withDefault(new C2StreamProfileLevelInfo::input(0u, static_cast<C2Config::profile_t>(profiles.front()), static_cast<C2Config::level_t>(levels.back())))
                .withFields({C2F(profileLevel, profile).oneOf(profiles), C2F(profileLevel, level).oneOf(levels)})
                .withSetter(ProfileLevelSetter).build());
        }
        if (format.video()) {
            addParameter(DefineParam(size, C2_PARAMKEY_PICTURE_SIZE)
                .withDefault(new C2StreamPictureSizeInfo::output(0u, 320, 240))
                .withFields({C2F(size, width).inRange(2, 4096), C2F(size, height).inRange(2, 4096)})
                .withSetter(SizeSetter).build());
            addParameter(DefineParam(outputUsage, C2_PARAMKEY_OUTPUT_STREAM_USAGE)
                .withDefault(new C2StreamUsageTuning::output(0u, 0))
                .withFields({C2F(outputUsage, value).any()})
                .withSetter(Setter<decltype(*outputUsage)>::StrictValueWithNoDeps).build());
            addParameter(DefineParam(pixelFormat, C2_PARAMKEY_PIXEL_FORMAT)
                .withDefault(new C2StreamPixelFormatInfo::output(0u, HAL_PIXEL_FORMAT_RGBX_8888))
                .withFields({C2F(pixelFormat, value).oneOf({HAL_PIXEL_FORMAT_RGBX_8888,
                    HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED, HAL_PIXEL_FORMAT_YCBCR_420_888,
                    HAL_PIXEL_FORMAT_YV12})})
                .withSetter(PixelFormatSetter, outputUsage).build());
            addParameter(DefineParam(dataspace, C2_PARAMKEY_DATA_SPACE)
                .withDefault(new C2StreamDataSpaceInfo::output(0u, 0))
                .withFields({C2F(dataspace, value).any()})
                .withSetter(Setter<decltype(*dataspace)>::StrictValueWithNoDeps).build());
            addParameter(DefineParam(crop, C2_PARAMKEY_CROP_RECT)
                .withDefault(new C2StreamCropRectInfo::output(0u, C2Rect(320, 240)))
                .withFields({C2F(crop, left).equalTo(0), C2F(crop, top).equalTo(0),
                             C2F(crop, width).inRange(2, 4096), C2F(crop, height).inRange(2, 4096)})
                .withSetter(CropSetter, size).build());
            addParameter(DefineParam(color, C2_PARAMKEY_COLOR_ASPECTS)
                .withDefault(new C2StreamColorAspectsInfo::output(0u))
                .withFields({C2F(color, range).any(), C2F(color, primaries).any(),
                             C2F(color, transfer).any(), C2F(color, matrix).any()})
                .withSetter(ColorSetter, dataspace).build());
            return;
        }
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
    }
    std::shared_ptr<C2StreamSampleRateInfo::output> rate;
    std::shared_ptr<C2StreamChannelCountInfo::output> channels;
    std::pair<uint32_t, uint32_t> audioConfig() const {
        auto guard = lock();
        if (!rate) return {0, 0};
        return {rate->value, channels->value};
    }
    std::tuple<uint32_t, uint32_t, uint32_t> videoConfig() const {
        auto guard = lock();
        return {size->width, size->height, dataspace->value};
    }
    bool cpuVideo() const {
        auto guard = lock();
        return pixelFormat->value == HAL_PIXEL_FORMAT_YCBCR_420_888 ||
               pixelFormat->value == HAL_PIXEL_FORMAT_YV12;
    }
private:
    static C2R PixelFormatSetter(bool, C2P<C2StreamPixelFormatInfo::output> &next,
                                 const C2P<C2StreamUsageTuning::output> &usage) {
        // CCodecBufferChannel sets CPU_READ for ByteBuffer consumers. Surface
        // consumers keep the direct RGB DMA-BUF path even if their input format
        // requests flexible YUV. Report the actual output format in either mode.
        next.set().value = (usage.v.value & C2MemoryUsage::CPU_READ)
            ? HAL_PIXEL_FORMAT_YCBCR_420_888 : HAL_PIXEL_FORMAT_RGBX_8888;
        return C2R::Ok();
    }
    static C2R ProfileLevelSetter(bool, const C2P<C2StreamProfileLevelInfo::input> &old,
                                  C2P<C2StreamProfileLevelInfo::input> &next) {
        C2R result = C2R::Ok();
        if (!next.F(next.v.profile).supportsAtAll(next.v.profile)) {
            result = result.plus(C2SettingResultBuilder::BadValue(next.F(next.v.profile)));
            next.set().profile = old.v.profile;
        }
        if (!next.F(next.v.level).supportsAtAll(next.v.level)) {
            result = result.plus(C2SettingResultBuilder::BadValue(next.F(next.v.level)));
            next.set().level = old.v.level;
        }
        return result;
    }
    static C2R CropSetter(bool, C2P<C2StreamCropRectInfo::output> &next,
                          const C2P<C2StreamPictureSizeInfo::output> &size) {
        next.set().left = next.set().top = 0;
        next.set().width = size.v.width; next.set().height = size.v.height;
        return C2R::Ok();
    }
    static C2R ColorSetter(bool, C2P<C2StreamColorAspectsInfo::output> &next,
                           const C2P<C2StreamDataSpaceInfo::output> &dataspace) {
        // The VAAPI seam reports SDR RGB/YUV with BT709/BT601 standards. Those
        // dataspace fields share the SDK values consumed by the pinned mapper.
        C2Mapper::map(int32_t((dataspace.v.value >> 27) & 7), &next.set().range);
        C2Mapper::map(int32_t((dataspace.v.value >> 16) & 63), &next.set().primaries, &next.set().matrix);
        C2Mapper::map(int32_t((dataspace.v.value >> 22) & 31), &next.set().transfer);
        return C2R::Ok();
    }
    // Pinned C2SoftAvcDec::IntfImpl::SizeSetter: reject invalid fields and
    // preserve each previous value independently.
    static C2R SizeSetter(bool, const C2P<C2StreamPictureSizeInfo::output> &old,
                         C2P<C2StreamPictureSizeInfo::output> &next) {
        C2R result = C2R::Ok();
        if (!next.F(next.v.width).supportsAtAll(next.v.width)) {
            result = result.plus(C2SettingResultBuilder::BadValue(next.F(next.v.width)));
            next.set().width = old.v.width;
        }
        if (!next.F(next.v.height).supportsAtAll(next.v.height)) {
            result = result.plus(C2SettingResultBuilder::BadValue(next.F(next.v.height)));
            next.set().height = old.v.height;
        }
        return result;
    }
    std::shared_ptr<C2StreamPictureSizeInfo::output> size;
    std::shared_ptr<C2StreamPixelFormatInfo::output> pixelFormat;
    std::shared_ptr<C2StreamUsageTuning::output> outputUsage;
    std::shared_ptr<C2StreamDataSpaceInfo::output> dataspace;
    std::shared_ptr<C2StreamCropRectInfo::output> crop;
    std::shared_ptr<C2StreamColorAspectsInfo::output> color;
    std::shared_ptr<C2StreamProfileLevelInfo::input> profileLevel;
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
        if (mDecoder && mFormat.video() && azk_codec_use_video_allocator(mDecoder) != 0)
            return C2_CORRUPTED;
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
        if (mFormat.video()) {
            // The next start supplies CPU_READ again for a raw consumer. Do not
            // carry it into a subsequent Surface configuration on this component.
            C2StreamUsageTuning::output usage(0u, 0);
            std::vector<std::unique_ptr<C2SettingResult>> failures;
            if (intf()->config_vb({&usage}, C2_MAY_BLOCK, &failures) != C2_OK ||
                !failures.empty()) mError = C2_CORRUPTED;
        }
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
        if (mConfigured) {
            ALOGE("%s received codec configuration after decoder initialization", mFormat.name);
            return C2_BAD_VALUE;
        }
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
        if (status != C2_OK)
            ALOGE("work %llu failed: status=%d flags=%x buffers=%zu",
                  (unsigned long long)work->input.ordinal.frameIndex.peeku(),
                  int(status), unsigned(work->input.flags), work->input.buffers.size());
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

    c2_status_t videoOutputs(const std::shared_ptr<C2BlockPool> &pool,
                            const std::unique_ptr<C2Work> &current) {
        for (;;) {
            int meta[4]; int64_t pts; uint64_t index;
            const bool cpu = mParams->cpuVideo();
            int result = azk_codec_peek_video_work(mDecoder, cpu, meta, &pts, &index);
            if (result == 1 || result == 2) return C2_OK;
            if (result != 0) return C2_CORRUPTED;
            std::vector<std::unique_ptr<C2Param>> updates;
            if (mParams->videoConfig() != std::make_tuple(uint32_t(meta[1]), uint32_t(meta[2]), uint32_t(meta[3]))) {
                C2StreamPictureSizeInfo::output size(0u, meta[1], meta[2]);
                C2StreamDataSpaceInfo::output dataspace(0u, meta[3]);
                std::vector<std::unique_ptr<C2SettingResult>> failures;
                const auto status = intf()->config_vb({&size, &dataspace}, C2_MAY_BLOCK, &failures);
                if (status != C2_OK || !failures.empty()) return C2_CORRUPTED;
                updates.emplace_back(C2Param::Copy(size));
                updates.emplace_back(C2Param::Copy(dataspace));
                // Include dependent metadata in the same output work. CCodec's
                // original mapper exposes the output color and crop.
                if (intf()->query_vb({}, {C2StreamCropRectInfo::output::PARAM_TYPE,
                                        C2StreamColorAspectsInfo::output::PARAM_TYPE,
                                        C2StreamPixelFormatInfo::output::PARAM_TYPE},
                                     C2_MAY_BLOCK, &updates) != C2_OK) return C2_CORRUPTED;
            }
            std::shared_ptr<C2GraphicBlock> block;
            if (cpu) {
                // Match C2SoftAvcDec's YV12 block contract for stock raw output.
                auto status = pool->fetchGraphicBlock((meta[1] + 1) & ~1, (meta[2] + 1) & ~1,
                        HAL_PIXEL_FORMAT_YV12, C2MemoryUsage(C2MemoryUsage::CPU_READ,
                        C2MemoryUsage::CPU_WRITE), &block);
                if (status != C2_OK) return status;
                {
                    C2GraphicView view = block->map().get();
                    if (view.error() != C2_OK) return view.error();
                    const auto layout = view.layout();
                    if (layout.type != C2PlanarLayout::TYPE_YUV ||
                        layout.planes[0].colInc != 1 || layout.planes[1].colInc != 1 ||
                        layout.planes[2].colInc != 1 ||
                        layout.planes[1].rowInc != layout.planes[2].rowInc) return C2_CORRUPTED;
                    result = azk_codec_read_video_work(mDecoder, view.data()[0],
                        layout.planes[0].rowInc, view.data()[1], view.data()[2],
                        layout.planes[1].rowInc);
                    if (result) return C2_CORRUPTED;
                }
                auto buffer = createGraphicBuffer(block, C2Rect(meta[1], meta[2]));
                buffer->setInfo(std::make_shared<C2StreamDataSpaceInfo::output>(0u, meta[3]));
                emit(index, pts, std::move(buffer), std::move(updates), current);
                continue;
            }
            // The original IGBA pool waits both allocation availability and the
            // acquire fence in this overload. Preserve its block and pool data.
            auto status = pool->fetchGraphicBlock(meta[1], meta[2], HAL_PIXEL_FORMAT_RGBX_8888,
                    C2MemoryUsage(C2AndroidMemoryUsage::HW_TEXTURE_READ, C2AndroidMemoryUsage::HW_TEXTURE_WRITE), &block);
            if (status != C2_OK) return status;
            auto *raw = UnwrapNativeCodec2GrallocHandle(block->handle());
            if (!raw) return C2_BAD_VALUE;
            native_handle_t *imported = nullptr;
            const auto importStatus = azk_graphics_import(raw, &imported);
            native_handle_delete(raw); // the unwrapped handle borrows its FDs
            if (importStatus) return C2_CORRUPTED;
            AzkGraphicsBufferInfo target{};
            result = azk_graphics_describe(imported, &target);
            if (!result) result = azk_codec_render_video_work(mDecoder, &target);
            azk_graphics_free(imported);
            if (result) return C2_CORRUPTED;
            auto buffer = createGraphicBuffer(block, C2Rect(meta[1], meta[2]));
            buffer->setInfo(std::make_shared<C2StreamDataSpaceInfo::output>(0u, meta[3]));
            emit(index, pts, std::move(buffer), std::move(updates), current);
        }
    }

    c2_status_t outputs(const std::shared_ptr<C2BlockPool> &pool,
                        const std::unique_ptr<C2Work> &current) {
        if (mFormat.video()) {
            auto status = videoOutputs(pool, current);
            if (status != C2_OK) return status;
        }
        while (!mFormat.video()) {
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
        // Pinned CCodec can queue empty work before its codec-specific data.
        // As in C2SoftAacDec::process, no compressed bytes means no decoder
        // initialization; otherwise that initial work would reject later CSD.
        if (input.empty() && !(work->input.flags & C2FrameData::FLAG_END_OF_STREAM)) {
            complete(work, C2_OK);
            return;
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

c2_status_t createFfmpegComponent(const C2String &name, c2_node_id_t id,
        const std::shared_ptr<C2ReflectorHelper> &reflector,
        std::shared_ptr<C2Component> *component) {
    if (!component || !reflector) return C2_BAD_VALUE;
    component->reset();
    for (const auto &format : availableFormats()) if (name == format.name) {
        *component = std::make_shared<Component>(format, id, std::make_shared<Interface>(format, reflector));
        return C2_OK;
    }
    return C2_NOT_FOUND;
}

c2_status_t createFfmpegInterface(const C2String &name, c2_node_id_t id,
        const std::shared_ptr<C2ReflectorHelper> &reflector,
        std::shared_ptr<C2ComponentInterface> *interface) {
    if (!interface || !reflector) return C2_BAD_VALUE;
    interface->reset();
    for (const auto &format : availableFormats()) if (name == format.name) {
        *interface = std::make_shared<SimpleInterface<Interface>>(format.name, id,
                std::make_shared<Interface>(format, reflector));
        return C2_OK;
    }
    return C2_NOT_FOUND;
}

std::vector<std::shared_ptr<const C2Component::Traits>> componentTraits() {
    std::vector<std::shared_ptr<const C2Component::Traits>> result;
    for (const auto &format : availableFormats()) {
        auto traits = std::make_shared<C2Component::Traits>();
        traits->name = format.name;
        traits->kind = C2Component::KIND_DECODER;
        traits->domain = format.video() ? C2Component::DOMAIN_VIDEO : C2Component::DOMAIN_AUDIO;
        traits->mediaType = format.mime;
        traits->rank = 8;
        result.push_back(std::move(traits));
    }
    return result;
}
} // namespace azk::codec2
