//! Exact pinned Soong source groups used by the native codec client closure.
const Group = struct { root: []const u8, files: []const []const u8, flags: []const []const u8 = &.{} };
pub const groups = [_]Group{
    .{ .root = "frameworks/av/media/codec2/components/base", .files = &.{ "SimpleC2Component.cpp", "SimpleC2Interface.cpp" } },
    // Native codec capabilities, media-service clients and rendering contracts.
    .{ .root = "frameworks/av/camera", .files = &.{ "ICamera.cpp", "ICameraClient.cpp", "ICameraRecordingProxy.cpp" } },
    .{ .root = "frameworks/av/media/libmedia", .files = &.{ "MediaResource.cpp", "MediaResourcePolicy.cpp", "AudioCapabilities.cpp", "CodecCapabilities.cpp", "CodecCapabilitiesUtils.cpp", "EncoderCapabilities.cpp", "VideoCapabilities.cpp", "IMediaPlayerService.cpp", "IMediaPlayer.cpp", "IMediaPlayerClient.cpp", "IMediaRecorder.cpp", "IMediaRecorderClient.cpp", "IRemoteDisplay.cpp", "IRemoteDisplayClient.cpp", "IStreamSource.cpp", "IMediaMetadataRetriever.cpp", "IMediaDeathNotifier.cpp", "BufferingSettings.cpp", "mediaplayer.cpp", "mediametadataretriever.cpp", "MediaProfiles.cpp", "Metadata.cpp", "omx/1.0/WOmx.cpp" } },
    .{ .root = "frameworks/av/media/libstagefright", .files = &.{ "VideoRenderQualityTracker.cpp", "colorconversion/SoftwareRenderer.cpp", "colorconversion/ColorConverter.cpp" } },
    .{ .root = "external/libyuv", .files = &.{ "source/compare.cc", "source/compare_common.cc", "source/compare_gcc.cc", "source/compare_msa.cc", "source/compare_neon.cc", "source/compare_neon64.cc", "source/convert.cc", "source/convert_argb.cc", "source/convert_from.cc", "source/convert_from_argb.cc", "source/convert_jpeg.cc", "source/convert_to_argb.cc", "source/convert_to_i420.cc", "source/cpu_id.cc", "source/mjpeg_decoder.cc", "source/mjpeg_validate.cc", "source/planar_functions.cc", "source/rotate.cc", "source/rotate_any.cc", "source/rotate_argb.cc", "source/rotate_common.cc", "source/rotate_gcc.cc", "source/rotate_msa.cc", "source/rotate_neon.cc", "source/rotate_neon64.cc", "source/row_any.cc", "source/row_common.cc", "source/row_gcc.cc", "source/row_msa.cc", "source/row_neon.cc", "source/row_neon64.cc", "source/row_rvv.cc", "source/scale.cc", "source/scale_any.cc", "source/scale_argb.cc", "source/scale_common.cc", "source/scale_gcc.cc", "source/scale_msa.cc", "source/scale_neon.cc", "source/scale_neon64.cc", "source/scale_rgb.cc", "source/scale_rvv.cc", "source/scale_uv.cc", "source/video_common.cc" }, .flags = &.{ "-DHAVE_JPEG", "-DLIBYUV_UNLIMITED_DATA", "-fexceptions" } },
    .{ .root = "frameworks/av/media/module/foundation", .files = &.{ "ColorUtils.cpp", "ColorUtils_fill.cpp" } },
    .{ .root = "frameworks/av/media/module/foundation", .files = &@import("gen/island_sources.zig").media_format_foundation_srcs },
    .{ .root = "frameworks/av/media/libstagefright", .files = &.{ "MediaCodec.cpp", "MediaCodecList.cpp", "MediaCodecListOverrides.cpp", "ACodec.cpp", "ACodecBufferChannel.cpp", "AHierarchicalStateMachine.cpp", "BufferImpl.cpp", "CodecErrorLog.cpp", "CryptoAsync.cpp", "OMXClient.cpp", "OmxInfoBuilder.cpp" } },
    .{ .root = "frameworks/av/media/ndk", .files = &.{ "NdkMediaCodec.cpp", "NdkMediaFormat.cpp" }, .flags = &.{"-DEXPORT=__attribute__((visibility(\"default\")))"} },
    // Pinned platform builds omit vendor-only filters.
    .{ .root = "frameworks/av/media/codec2/hal/plugin", .files = &.{ "DefaultFilterPlugin.cpp", "FilterWrapperStub.cpp" } },
    // libcodec2
    .{ .root = "frameworks/av/media/codec2/core", .files = &.{
        "C2.cpp",
    } },
    // libcodec2_vndk
    .{ .root = "frameworks/av/media/codec2/vndk", .files = &.{
        "C2AllocatorBlob.cpp",
        "C2AllocatorIon.cpp",
        "C2AllocatorGralloc.cpp",
        "C2Buffer.cpp",
        "C2Config.cpp",
        "C2DmaBufAllocator.cpp",
        "C2Fence.cpp",
        "C2PlatformStorePluginLoader.cpp",
        "C2Store.cpp",
        "platform/C2BqBuffer.cpp",
        "platform/C2BqPoolInvalidator.cpp",
        "platform/C2SurfaceSyncObj.cpp",
        "platform/C2IgbaBuffer.cpp",
        "types.cpp",
        "util/C2Debug.cpp",
        "util/C2InterfaceHelper.cpp",
        "util/C2InterfaceUtils.cpp",
        "util/C2ParamUtils.cpp",
    } },
    // libcodec2_hal_common
    .{ .root = "frameworks/av/media/codec2/hal/common", .files = &.{
        "BufferTypes.cpp",
        "MultiAccessUnitHelper.cpp",
    } },
    // libcodec2_hal_selection_static
    .{ .root = "frameworks/av/media/codec2/hal/common", .files = &.{
        "HalSelection.cpp",
    } },
    // libcodec2_hidl_client@1.0
    .{ .root = "frameworks/av/media/codec2/hal/hidl/1.0/utils", .files = &.{
        "types.cpp",
    } },
    // libcodec2_hidl_client@1.1
    .{ .root = "frameworks/av/media/codec2/hal/hidl/1.1/utils", .files = &.{
        "types.cpp",
    } },
    // libcodec2_hidl_client@1.2
    .{ .root = "frameworks/av/media/codec2/hal/hidl/1.2/utils", .files = &.{
        "types.cpp",
    } },
    // libcodec2_aidl_noisurface
    .{ .root = "frameworks/av/media/codec2/hal/aidl", .files = &.{
        "BufferTypes.cpp",
        "Component.cpp",
        "ComponentInterface.cpp",
        "ComponentStore.cpp",
        "Configurable.cpp",
        "InputBufferManager.cpp",
        "ParamTypes.cpp",
    } },
    // libcodec2_client
    .{ .root = "frameworks/av/media/codec2/hal/client", .files = &.{
        "ApexCodecsLazy.cpp",
        "GraphicBufferAllocator.cpp",
        "GraphicsTracker.cpp",
        "client.cpp",
        "output.cpp",
    } },
    // libsfplugin_ccodec
    .{ .root = "frameworks/av/media/codec2/sfplugin", .files = &.{
        "C2AidlNode.cpp",
        "C2OMXNode.cpp",
        "C2NodeImpl.cpp",
        "CCodec.cpp",
        "CCodecBufferChannel.cpp",
        "CCodecBuffers.cpp",
        "CCodecConfig.cpp",
        "CCodecResources.cpp",
        "Codec2Buffer.cpp",
        "Codec2InfoBuilder.cpp",
        "FrameReassembler.cpp",
        "PipelineWatcher.cpp",
        "ReflectedParamUpdater.cpp",
    } },
    // libsfplugin_ccodec_utils
    .{ .root = "frameworks/av/media/codec2/sfplugin/utils", .files = &.{
        "Codec2BufferUtils.cpp",
        "Codec2CommonUtils.cpp",
        "Codec2Mapper.cpp",
    } },
    // libstagefright_codecbase
    .{ .root = "frameworks/av/media/libstagefright", .files = &.{
        "CodecBase.cpp",
        "DataConverter.cpp",
        "FrameRenderTracker.cpp",
        "MediaCodecListWriter.cpp",
        "SkipCutBuffer.cpp",
    } },
    // libstagefright_bufferpool@2.0-default
    .{ .root = "frameworks/av/media/module/bufferpool/2.0", .files = &.{
        "Accessor.cpp",
        "AccessorImpl.cpp",
        "BufferPoolClient.cpp",
        "BufferStatus.cpp",
        "ClientManager.cpp",
        "Connection.cpp",
        "Observer.cpp",
    } },
    // libstagefright_bufferqueue_helper
    .{ .root = "frameworks/av/media/module/bqhelper", .files = &.{
        "FrameDropper.cpp",
        "GraphicBufferSource.cpp",
    } },
    // libstagefright_omx
    .{ .root = "frameworks/av/media/libstagefright/omx", .files = &.{
        "OMXStore.cpp",
        "OMXNodeInstance.cpp",
        "OMXUtils.cpp",
        "OmxGraphicBufferSource.cpp",
        "1.0/Omx.cpp",
        "1.0/OmxStore.cpp",
        "1.0/WGraphicBufferSource.cpp",
        "1.0/WOmxNode.cpp",
        "1.0/WOmxObserver.cpp",
        "1.0/WOmxBufferSource.cpp",
    } },
    // libmedia_omx
    .{ .root = "frameworks/av/media/libmedia", .files = &.{
        "IOMX.cpp",
        "MediaCodecBuffer.cpp",
        "OMXBuffer.cpp",
        "omx/1.0/WOmxBufferSource.cpp",
        "omx/1.0/WOmxNode.cpp",
        "omx/1.0/WOmxObserver.cpp",
    } },
    // libmedia_codeclist
    .{ .root = "frameworks/av/media/libmedia", .files = &.{
        "IMediaCodecList.cpp",
        "MediaCodecInfo.cpp",
    } },
    // libstagefright_surface_utils
    .{ .root = "frameworks/av/media/libstagefright", .files = &.{
        "SurfaceUtils.cpp",
    } },
    // libstagefright_xmlparser
    .{ .root = "frameworks/av/media/libstagefright/xmlparser", .files = &.{
        "MediaCodecsXmlParser.cpp",
    } },
    // libstagefright_graphicbuffersource_aidl
    .{ .root = "frameworks/av/media/module/aidlpersistentsurface", .files = &.{
        "AidlGraphicBufferSource.cpp",
        "wrapper/WAidlGraphicBufferSource.cpp",
    } },
    // libmediaformatshaper
    .{ .root = "frameworks/av/media/module/libmediaformatshaper", .files = &.{
        "CodecProperties.cpp",
        "CodecSeeding.cpp",
        "FormatShaper.cpp",
        "ManageShapingCodecs.cpp",
        "VideoShaper.cpp",
        "VQApply.cpp",
    } },
    // codec2_soft_common
    .{ .root = "frameworks/av/media/codec2/components/base", .files = &.{
        "SimpleC2Component.cpp",
    } },
    // libdmabufheap
    .{ .root = "system/memory/libdmabufheap", .files = &.{
        "BufferAllocator.cpp",
        "BufferAllocatorWrapper.cpp",
    } },
    // gralloctypes
    .{ .root = "frameworks/native/libs/gralloc/types", .files = &.{
        "Gralloc4.cpp",
    } },
};
pub const resource_groups = [_]Group{
    .{ .root = "frameworks/av/services/mediaresourcemanager", .files = &.{ "UidObserver.cpp", "ResourceManagerServiceNew.cpp", "IMediaResourceMonitor.cpp", "ResourceObserverService.cpp", "ClientImportanceReclaimPolicy.cpp", "DefaultResourceModel.cpp", "ResourceManagerMetrics.cpp", "ProcessPriorityReclaimPolicy.cpp", "ServiceLog.cpp", "ResourceTracker.cpp", "ResourceManagerServiceUtils.cpp", "ResourceManagerService.cpp" } },
    .{ .root = "frameworks/av/media/utils", .files = &.{ "ProcessInfo.cpp", "BatteryNotifier.cpp", "SchedulingPolicyService.cpp", "ISchedulingPolicyService.cpp" } },
    .{ .root = "frameworks/native/libs/binder", .files = &.{ "ActivityManager.cpp", "IActivityManager.cpp", "IUidObserver.cpp", "IProcessInfoService.cpp", "IBatteryStats.cpp" } },
};

pub const includes = [_][]const u8{
    "frameworks/av/media/utils/include",
    "system/core/libprocessgroup/include",
    "frameworks/native/libs/binder/include_activitymanager",
    "frameworks/native/libs/binder/include_processinfo",
    "frameworks/native/libs/binder/include_batterystats",
    "frameworks/av/services/mediaresourcemanager",
    "frameworks/av/media/codec2/components/base/include",
    "packages/modules/StatsD/lib/libstatspull/include",
    "packages/modules/StatsD/lib/libstatssocket/include",

    "frameworks/av/camera/include",
    "frameworks/av/camera/include/camera",
    "system/media/camera/include",
    "frameworks/av/media/audioaidlconversion/include",
    "frameworks/av/media/liberror/include",
    "frameworks/av/media/module/foundation/include/media/stagefright/foundation",
    "frameworks/av/media/ndk",
    "frameworks/av/media/libmediahelper/include",
    "frameworks/av/media/ndk/include_platform",
    "frameworks/av/media/libaudiofoundation/include",
    "frameworks/base/media/java/android/media/quality/include",
    "art/libnativeloader/include",
    "frameworks/av/media/ndk/include",
    "frameworks/av/media/ndk/include/media",
    "frameworks/av/media/codec2/hal/plugin/include",
    "frameworks/av/media/codec2/hal/plugin/internal",
    "system/server_configurable_flags/libflags/include",
    "system/libhwbinder/include",
    "external/libyuv/include",
    "external/expat/expat/lib",
    "frameworks/native/opengl/include",
    "system/core/libvndksupport/include",
    "system/media/audio_utils/include",
    "external/libdrm/include",
    "frameworks/av/drm/libmediadrm/include",
    "frameworks/av/drm/libmediadrm/interface",
    "frameworks/av/include",
    "frameworks/av/media/codec2/components/base",
    "frameworks/av/media/codec2/components/base/include",
    "frameworks/av/media/codec2/core",
    "frameworks/av/media/codec2/hal/aidl",
    "frameworks/av/media/codec2/hal/aidl/include",
    "frameworks/av/media/codec2/hal/client",
    "frameworks/av/media/codec2/hal/client/include",
    "frameworks/av/media/codec2/hal/common",
    "frameworks/av/media/codec2/hal/common/include/",
    "frameworks/av/media/codec2/hal/hidl/1.0/utils",
    "frameworks/av/media/codec2/hal/hidl/1.0/utils/include",
    "frameworks/av/media/codec2/hal/hidl/1.1/utils",
    "frameworks/av/media/codec2/hal/hidl/1.1/utils/include",
    "frameworks/av/media/codec2/hal/hidl/1.2/utils",
    "frameworks/av/media/codec2/hal/hidl/1.2/utils/include",
    "frameworks/av/media/codec2/sfplugin",
    "frameworks/av/media/codec2/sfplugin/include",
    "frameworks/av/media/codec2/sfplugin/utils",
    "frameworks/av/media/codec2/sfplugin/utils/.",
    "frameworks/av/media/codec2/vndk",
    "frameworks/av/media/codec2/vndk/include",
    "frameworks/av/media/codec2/vndk/internal",
    "frameworks/av/media/libaudioclient/include",
    "frameworks/av/media/libmedia",
    "frameworks/av/media/libmedia/aidl",
    "frameworks/av/media/libmedia/include",
    "frameworks/av/media/libmediametrics/include",
    "frameworks/av/media/libstagefright",
    "frameworks/av/media/libstagefright/include",
    "frameworks/av/media/libstagefright/omx",
    "frameworks/av/media/libstagefright/omx/include",
    "frameworks/av/media/libstagefright/xmlparser",
    "frameworks/av/media/libstagefright/xmlparser/include",
    "frameworks/av/media/module/aidlpersistentsurface",
    "frameworks/av/media/module/aidlpersistentsurface/include",
    "frameworks/av/media/module/bqhelper",
    "frameworks/av/media/module/bqhelper/include",
    "frameworks/av/media/module/bufferpool/2.0",
    "frameworks/av/media/module/bufferpool/2.0/include",
    "frameworks/av/media/module/foundation/include",
    "frameworks/av/media/module/libapexcodecs/include",
    "frameworks/av/media/module/libmediaformatshaper",
    "frameworks/av/media/module/libmediaformatshaper/include",
    "frameworks/av/media/ndk/include",
    "frameworks/native/headers/media_plugin",
    "frameworks/native/headers/media_plugin/media/openmax",
    "frameworks/native/libs/gralloc/types",
    "frameworks/native/libs/gralloc/types/include",
    "frameworks/native/libs/gui/bufferqueue/2.0",
    "frameworks/native/libs/gui/bufferqueue/2.0/include",
    "frameworks/native/libs/gui/include",
    "system/core/libsystem/include",
    "system/libhidl/transport/allocator/1.0/utils/include",
    "system/libhidl/transport/token/1.0/utils/include",
    "system/libhidl/libhidlmemory",
    "system/libhidl/libhidlmemory/include",

    "system/media/audio/include",
    "system/memory/libdmabufheap",
    "system/memory/libdmabufheap/include",
    "system/memory/libion/include",
    "system/memory/libion/kernel-headers",
};
