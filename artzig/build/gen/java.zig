// GENERATED from the pinned AOSP Soong graph — do not edit manually.
pub const Module = struct {
    name: []const u8,
    source: []const u8,
    classpath: []const []const u8,
    dex: bool,
    has_gen_src: bool,
    has_res: bool,
};
pub const modules = [_]Module{
    .{ .name = "core-all", .source = "21", .classpath = &.{}, .dex = false, .has_gen_src = true, .has_res = false },
    .{ .name = "conscrypt", .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = true, .has_res = false },
    .{ .name = "core-oj", .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = false, .has_res = true },
    .{ .name = "core-libart", .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = true, .has_res = false },
    .{ .name = "apache-xml", .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = false, .has_res = true },
    .{ .name = "bouncycastle", .source = "21", .classpath = &.{}, .dex = true, .has_gen_src = false, .has_res = false },
    .{ .name = "okhttp", .source = "1.8", .classpath = &.{"conscrypt"}, .dex = true, .has_gen_src = false, .has_res = false },
};
