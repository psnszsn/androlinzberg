// GENERATED from the pinned AOSP Soong graph — do not edit manually.
const sources = @import("../java_sources.zig");

pub const Module = struct {
    name: []const u8,
    sources: []const []const u8,
    source: []const u8,
    classpath: []const []const u8,
    dex: bool,
    has_gen_src: bool,
    resources: []const Resource,
};
pub const Resource = struct {
    root: []const u8,
    file: []const u8,
};
const no_resources: []const Resource = &.{};
const core_oj_resources: []const Resource = &.{
    .{ .root = "libcore/luni/src/main/java", .file = "java/util/logging/logging.properties" },
    .{ .root = "libcore/luni/src/main/java", .file = "java/security/security.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "calendars.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "java/time/chrono/hijrah-config-Hijrah-umalqura_islamic-umalqura.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/nio/cs/standard-charsets" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_de.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_es.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_fr.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_it.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_ja.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_ko.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_pt_BR.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_sv.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_zh_CN.properties" },
    .{ .root = "libcore/ojluni/src/main/resources", .file = "sun/util/logging/resources/logging_zh_TW.properties" },
};
const apache_xml_resources: []const Resource = &.{
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xalan/res/XSLTInfo.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/Encodings.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/HTMLEntities.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/XMLEntities.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/output_html.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/output_text.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/output_unknown.properties" },
    .{ .root = "external/apache-xml/src/main/java", .file = "org/apache/xml/serializer/output_xml.properties" },
};
pub const modules = [_]Module{
    .{ .name = "core-all", .sources = &sources.core_all, .source = "21", .classpath = &.{}, .dex = false, .has_gen_src = true, .resources = no_resources },
    .{ .name = "conscrypt", .sources = &sources.conscrypt, .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = true, .resources = no_resources },
    .{ .name = "core-oj", .sources = &sources.core_oj, .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = false, .resources = core_oj_resources },
    .{ .name = "core-libart", .sources = &sources.core_libart, .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = true, .resources = no_resources },
    .{ .name = "apache-xml", .sources = &sources.apache_xml, .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = false, .resources = apache_xml_resources },
    .{ .name = "bouncycastle", .sources = &sources.bouncycastle, .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = false, .resources = no_resources },
    .{ .name = "okhttp", .sources = &sources.okhttp, .source = "1.8", .classpath = &.{"conscrypt"}, .dex = true, .has_gen_src = false, .resources = no_resources },
};
