//! Original extractor plugins and their Soong static dependencies. Plugin-private
//! symbols stay hidden when these libraries share the native platform ELF.
const Plugin = struct { name: []const u8, files: []const []const u8 };
pub const root = "frameworks/av/media/module/extractors";
pub const plugins = [_]Plugin{
    .{ .name = "wav", .files = &.{"WAVExtractor.cpp"} },
    .{ .name = "mp3", .files = &.{ "MP3Extractor.cpp", "VBRISeeker.cpp", "XINGSeeker.cpp" } },
    .{ .name = "aac", .files = &.{"AACExtractor.cpp"} },
    .{ .name = "mp4", .files = &.{ "AC4Parser.cpp", "HeifCleanAperture.cpp", "ItemTable.cpp", "MPEG4Extractor.cpp", "SampleIterator.cpp", "SampleTable.cpp" } },
    .{ .name = "mkv", .files = &.{"MatroskaExtractor.cpp"} },
    .{ .name = "ogg", .files = &.{"OggExtractor.cpp"} },
    .{ .name = "flac", .files = &.{"FLACExtractor.cpp"} },
    .{ .name = "amr", .files = &.{"AMRExtractor.cpp"} },
    .{ .name = "mpeg2", .files = &.{ "ExtractorBundle.cpp", "MPEG2PSExtractor.cpp", "MPEG2TSExtractor.cpp" } },
};
const Group = struct { root: []const u8, files: []const []const u8, flags: []const []const u8 = &.{} };
pub const support = [_]Group{
    .{ .root = "external/libwebm", .files = &.{"mkvparser/mkvparser.cc"} },
    .{ .root = "frameworks/av/media/module/codecs/flac/dec", .files = &.{"FLACDecoder.cpp"} },
    .{ .root = "external/tremolo/Tremolo", .files = &.{ "bitwise.c", "codebook.c", "dsp.c", "floor0.c", "floor1.c", "floor_lookup.c", "framing.c", "mapping0.c", "mdct.c", "misc.c", "res012.c", "treminfo.c", "vorbisfile.c" }, .flags = &.{ "-DONLY_C", "-funsigned-char" } },
    .{ .root = "external/flac/src/libFLAC", .files = &.{ "bitmath.c", "bitreader.c", "bitwriter.c", "cpu.c", "crc.c", "fixed.c", "float.c", "format.c", "lpc.c", "memory.c", "md5.c", "stream_decoder.c", "stream_encoder.c", "stream_encoder_framing.c", "window.c" }, .flags = &.{ "-DHAVE_CONFIG_H", "-DFLAC__NO_MD5", "-DFLAC__INTEGER_ONLY_LIBRARY", "-D_REENTRANT", "-DPIC" } },
};
// Shared by NuPlayer and MPEG2 extractors; one owner in libandroid_platform.
pub const mpeg2 = [_][]const u8{ "AnotherPacketSource.cpp", "ATSParser.cpp", "CasManager.cpp", "ESQueue.cpp", "HlsSampleDecryptor.cpp" };
pub const includes = [_][]const u8{
    "frameworks/av/media/module/codecs/flac/dec",
    "frameworks/av/media/module/mpeg2ts/include",
    "frameworks/av/media/module/mpeg2ts/include/mpeg2ts",
    "external/libwebm",
    "external/tremolo",
    "external/tremolo/Tremolo",
    "external/flac",
    "external/flac/include",
    "external/flac/src/libFLAC/include",
};
