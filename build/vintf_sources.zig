//! Pinned libvintf Android runtime implementation and original parser support.
const Group = struct { root: []const u8, files: []const []const u8 };
pub const groups = [_]Group{
    .{ .root = "system/libvintf", .files = &.{ "parse_string.cpp", "parse_xml.cpp", "Apex.cpp", "CompatibilityMatrix.cpp", "FileSystem.cpp", "FQName.cpp", "FqInstance.cpp", "HalManifest.cpp", "HalInterface.cpp", "KernelConfigTypedValue.cpp", "KernelInfo.cpp", "RuntimeInfo.cpp", "ManifestHal.cpp", "ManifestInstance.cpp", "MatrixHal.cpp", "MatrixInstance.cpp", "MatrixKernel.cpp", "PropertyFetcher.cpp", "Regex.cpp", "SystemSdk.cpp", "TransportArch.cpp", "VintfObject.cpp", "XmlFile.cpp", "utils.cpp", "RuntimeInfo-target.cpp", "KernelConfigs.cpp", "KernelConfigParser.cpp" } },
    .{ .root = "packages/modules/Gki/libkver", .files = &.{"kernel_release.cpp"} },
    .{ .root = "external/tinyxml2", .files = &.{"tinyxml2.cpp"} },
};
pub const includes = [_][]const u8{
    "system/libvintf",                      "system/libvintf/include",            "system/libvintf/include/vintf",
    "packages/modules/Gki/libkver/include", "system/tools/aidl/metadata/include", "system/tools/hidl/metadata/include",
    "external/tinyxml2",
};
