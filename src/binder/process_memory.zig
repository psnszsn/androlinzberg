//! Hosted Linux memory observations for android.os.Debug.
//! Source: pinned libmeminfo/androidprocheaps.cpp and android_os_Debug.cpp.
//! The Android heap classification, BSS adjacency, sub-heap duplication, swap-PSS
//! flag and aggregate ordering are retained. Linux file ownership stays in std.Io.
const std = @import("std");
const Allocator = std.mem.Allocator;
const Io = std.Io;

pub const Heap = enum(usize) {
    unknown,
    dalvik,
    native,
    dalvik_other,
    stack,
    cursor,
    ashmem,
    gl_dev,
    unknown_dev,
    so,
    jar,
    apk,
    ttf,
    dex,
    oat,
    art,
    unknown_map,
    graphics,
    gl,
    other_memtrack,
    dalvik_normal,
    dalvik_large,
    dalvik_zygote,
    dalvik_non_moving,
    dalvik_other_linearalloc,
    dalvik_other_accounting,
    dalvik_other_zygote_code_cache,
    dalvik_other_app_code_cache,
    dalvik_other_compiler_metadata,
    dalvik_other_indirect_reference_table,
    dex_boot_vdex,
    dex_app_dex,
    dex_app_vdex,
    art_app,
    art_boot,
};
pub const heap_count = @typeInfo(Heap).@"enum".field_names.len;
pub const core_heap_count = 3;
pub const exclusive_heap_count = 20;
pub const Stats = struct {
    pss: u64 = 0,
    swappable_pss: u64 = 0,
    rss: u64 = 0,
    private_dirty: u64 = 0,
    shared_dirty: u64 = 0,
    private_clean: u64 = 0,
    shared_clean: u64 = 0,
    swapped_out: u64 = 0,
    swapped_out_pss: u64 = 0,

    pub fn add(out: *Stats, value: Stats) void {
        inline for (@typeInfo(Stats).@"struct".field_names) |field| {
            @field(out, field) +%= @field(value, field);
        }
    }
    pub fn uss(s: Stats) u64 {
        return s.private_clean +% s.private_dirty;
    }
};
pub const MemoryInfo = struct {
    heaps: [heap_count]Stats = @splat(.{}),
    has_swap_pss: bool = false,

    pub fn total(self: MemoryInfo) Stats {
        var result: Stats = .{};
        for (self.heaps[0..core_heap_count]) |heap| result.add(heap);
        return result;
    }
};

pub const Mapping = struct {
    start: u64,
    end: u64,
    inode: u64,
    name: []const u8,
};
pub fn parseMapping(line: []const u8) ?Mapping {
    var words = std.mem.tokenizeAny(u8, line, " \t");
    const range = words.next() orelse return null;
    const dash = std.mem.indexOfScalar(u8, range, '-') orelse return null;
    const start = std.fmt.parseInt(u64, range[0..dash], 16) catch return null;
    const end = std.fmt.parseInt(u64, range[dash + 1 ..], 16) catch return null;
    if (end < start) return null;
    const permissions = words.next() orelse return null;
    if (permissions.len != 4) return null;
    _ = words.next() orelse return null; // offset
    _ = words.next() orelse return null; // device
    const inode = std.fmt.parseInt(u64, words.next() orelse return null, 10) catch return null;
    return .{ .start = start, .end = end, .inode = inode, .name = std.mem.trim(u8, words.rest(), " \t\r") };
}

const Classification = struct { heap: Heap = .unknown, sub: ?Heap = null, swappable: bool = false };
const starts = std.mem.startsWith;
const ends = std.mem.endsWith;
fn contains(s: []const u8, part: []const u8) bool {
    return std.mem.indexOf(u8, s, part) != null;
}
fn bootName(name: []const u8) bool {
    return contains(name, "@boot") or contains(name, "/boot") or contains(name, "/apex");
}
fn classify(mapping: Mapping, previous_end: u64, previous_heap: Heap) Classification {
    var name = mapping.name;
    if (ends(u8, name, " (deleted)")) name = name[0 .. name.len - " (deleted)".len];
    if (starts(u8, name, "[heap]") or starts(u8, name, "[anon:libc_malloc]") or
        starts(u8, name, "[anon:scudo:") or starts(u8, name, "[anon:GWP-ASan")) return .{ .heap = .native };
    if (starts(u8, name, "[stack") or starts(u8, name, "[anon:stack_and_tls:")) return .{ .heap = .stack };
    inline for (.{ .{ ".so", Heap.so }, .{ ".jar", Heap.jar }, .{ ".apk", Heap.apk }, .{ ".ttf", Heap.ttf } }) |entry| {
        if (ends(u8, name, entry[0])) return .{ .heap = entry[1], .swappable = true };
    }
    if (ends(u8, name, ".odex") or (name.len > 4 and contains(name, ".dex"))) return .{ .heap = .dex, .sub = .dex_app_dex, .swappable = true };
    if (ends(u8, name, ".vdex")) return .{ .heap = .dex, .sub = if (bootName(name)) .dex_boot_vdex else .dex_app_vdex, .swappable = true };
    if (ends(u8, name, ".oat")) return .{ .heap = .oat, .swappable = true };
    if (ends(u8, name, ".art") or ends(u8, name, ".art]")) return .{ .heap = .art, .sub = if (bootName(name)) .art_boot else .art_app, .swappable = true };
    if (contains(name, "kgsl-3d0")) return .{ .heap = .gl_dev };
    if (starts(u8, name, "/dev/")) {
        if (starts(u8, name, "/dev/ashmem/CursorWindow")) return .{ .heap = .cursor };
        if (starts(u8, name, "/dev/ashmem/jit-zygote-cache")) return .{ .heap = .dalvik_other, .sub = .dalvik_other_zygote_code_cache };
        if (starts(u8, name, "/dev/ashmem")) return .{ .heap = .ashmem };
        return .{ .heap = .unknown_dev };
    }
    if (starts(u8, name, "/memfd:jit-cache")) return .{ .heap = .dalvik_other, .sub = .dalvik_other_app_code_cache };
    if (starts(u8, name, "/memfd:jit-zygote-cache")) return .{ .heap = .dalvik_other, .sub = .dalvik_other_zygote_code_cache };
    if (starts(u8, name, "[anon:")) {
        if (!starts(u8, name, "[anon:dalvik-")) return .{};
        inline for (.{
            .{ "LinearAlloc", Heap.dalvik_other, Heap.dalvik_other_linearalloc },
            .{ "alloc space", Heap.dalvik, Heap.dalvik_normal },
            .{ "main space", Heap.dalvik, Heap.dalvik_normal },
            .{ "large object space", Heap.dalvik, Heap.dalvik_large },
            .{ "free list large object space", Heap.dalvik, Heap.dalvik_large },
            .{ "non moving space", Heap.dalvik, Heap.dalvik_non_moving },
            .{ "zygote space", Heap.dalvik, Heap.dalvik_zygote },
            .{ "indirect ref", Heap.dalvik_other, Heap.dalvik_other_indirect_reference_table },
            .{ "jit-code-cache", Heap.dalvik_other, Heap.dalvik_other_app_code_cache },
            .{ "data-code-cache", Heap.dalvik_other, Heap.dalvik_other_app_code_cache },
            .{ "CompilerMetadata", Heap.dalvik_other, Heap.dalvik_other_compiler_metadata },
        }) |entry| {
            if (starts(u8, name, "[anon:dalvik-" ++ entry[0])) return .{ .heap = entry[1], .sub = entry[2] };
        }
        return .{ .heap = .dalvik_other, .sub = .dalvik_other_accounting };
    }
    if (name.len != 0) return .{ .heap = .unknown_map };
    if (mapping.start == previous_end and previous_heap == .so) return .{ .heap = .so };
    return .{};
}

fn account(result: *MemoryInfo, classification: Classification, original: Stats) void {
    var stats = original;
    if (stats.swapped_out_pss > 0) result.has_swap_pss = true;
    if (classification.swappable and stats.pss > 0) {
        // Pinned libmeminfo divides the uint64 operands before converting to float.
        const shared = stats.shared_clean +% stats.shared_dirty;
        const proportion: f32 = if (shared == 0) 0 else @floatFromInt((stats.pss -% stats.uss()) / shared);
        stats.swappable_pss = @intFromFloat(proportion * @as(f32, @floatFromInt(stats.shared_clean)) + @as(f32, @floatFromInt(stats.private_clean)));
    }
    result.heaps[@backingInt(classification.heap)].add(stats);
    if (classification.sub) |sub| result.heaps[@backingInt(sub)].add(stats);
}

const stat_tags = .{
    .{ "Pss", "pss" },                   .{ "Rss", "rss" },                     .{ "Private_Dirty", "private_dirty" },
    .{ "Shared_Dirty", "shared_dirty" }, .{ "Private_Clean", "private_clean" }, .{ "Shared_Clean", "shared_clean" },
    .{ "Swap", "swapped_out" },          .{ "SwapPss", "swapped_out_pss" },
};
pub fn parseSmaps(contents: []const u8) !MemoryInfo {
    var result: MemoryInfo = .{};
    var previous_end: u64 = 0;
    var previous_heap: Heap = .unknown;
    var classification: ?Classification = null;
    var stats: Stats = .{};
    var lines = std.mem.splitScalar(u8, contents, '\n');
    while (lines.next()) |line| {
        if (parseMapping(line)) |mapping| {
            if (classification) |current| account(&result, current, stats);
            classification = classify(mapping, previous_end, previous_heap);
            previous_end = mapping.end;
            previous_heap = classification.?.heap;
            stats = .{};
            continue;
        }
        if (classification == null) continue;
        const colon = std.mem.indexOfScalar(u8, line, ':') orelse continue;
        inline for (stat_tags) |tag| {
            if (std.mem.eql(u8, line[0..colon], tag[0])) {
                @field(stats, tag[1]) = try firstNumber(line[colon + 1 ..]);
            }
        }
    }
    if (classification) |current| account(&result, current, stats) else return error.InvalidSmaps;
    for (result.heaps[core_heap_count..exclusive_heap_count]) |heap| result.heaps[0].add(heap);
    return result;
}

pub fn firstNumber(text: []const u8) !u64 {
    var words = std.mem.tokenizeAny(u8, text, " \t\r\n");
    return std.fmt.parseInt(u64, words.next() orelse return error.InvalidNumber, 10);
}
pub fn readText(io: Io, allocator: Allocator, path: []const u8) ![]u8 {
    const file = try Io.Dir.cwd().openFile(io, path, .{});
    defer file.close(io);
    // Procfs reports st_size=0; use streaming reads instead of the regular-file
    // reader's known-size optimization.
    var buffer: [4096]u8 = undefined;
    var reader = file.readerStreaming(io, &buffer);
    return reader.interface.allocRemaining(allocator, .unlimited);
}
pub fn readMemoryInfo(io: Io, allocator: Allocator, pid: i32) !MemoryInfo {
    var path_buffer: [64]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buffer, "/proc/{d}/smaps", .{pid});
    const contents = try readText(io, allocator, path);
    defer allocator.free(contents);
    return parseSmaps(contents);
}
pub fn readPss(io: Io, allocator: Allocator, pid: i32) !Stats {
    var path_buffer: [64]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buffer, "/proc/{d}/smaps_rollup", .{pid});
    const contents = readText(io, allocator, path) catch return (try readMemoryInfo(io, allocator, pid)).total();
    defer allocator.free(contents);
    return (try parseSmaps(contents)).total();
}
pub fn parseTaggedNumber(contents: []const u8, tag: []const u8) !u64 {
    var lines = std.mem.splitScalar(u8, contents, '\n');
    while (lines.next()) |line| {
        if (starts(u8, line, tag) and line.len > tag.len and line[tag.len] == ':') return firstNumber(line[tag.len + 1 ..]);
    }
    return error.MissingTag;
}
pub fn readRss(io: Io, allocator: Allocator, pid: i32) !u64 {
    var path_buffer: [64]u8 = undefined;
    const path = try std.fmt.bufPrint(&path_buffer, "/proc/{d}/status", .{pid});
    const contents = try readText(io, allocator, path);
    defer allocator.free(contents);
    return parseTaggedNumber(contents, "VmRSS");
}

pub const meminfo_tags = [_][]const u8{
    "MemTotal",  "MemFree",  "Buffers",     "Cached",       "Shmem",        "Slab",           "SReclaimable", "SUnreclaim",
    "SwapTotal", "SwapFree", "Zram",        "Mapped",       "VmallocUsed",  "PageTables",     "KernelStack",  "KReclaimable",
    "Active",    "Inactive", "Unevictable", "MemAvailable", "Active(anon)", "Inactive(anon)", "Active(file)", "Inactive(file)",
    "CmaTotal",  "CmaFree",
};
pub fn parseMemInfo(contents: []const u8) ![meminfo_tags.len]u64 {
    var values: [meminfo_tags.len]u64 = @splat(0);
    for (meminfo_tags, 0..) |tag, i| values[i] = parseTaggedNumber(contents, tag) catch |err| switch (err) {
        error.MissingTag => 0,
        else => return err,
    };
    return values;
}
pub fn readScalar(io: Io, allocator: Allocator, path: []const u8) !u64 {
    const contents = try readText(io, allocator, path);
    defer allocator.free(contents);
    return firstNumber(contents);
}
fn zramBytes(io: Io, allocator: Allocator) u64 {
    var total: u64 = 0;
    for (0..256) |i| {
        var path_buffer: [96]u8 = undefined;
        const path = std.fmt.bufPrint(&path_buffer, "/sys/block/zram{d}", .{i}) catch unreachable;
        var dir = Io.Dir.cwd().openDir(io, path, .{}) catch break;
        defer dir.close(io);
        const mm_stat = dir.readFileAlloc(io, "mm_stat", allocator, .limited(4096)) catch {
            const old = dir.readFileAlloc(io, "mem_used_total", allocator, .limited(4096)) catch return 0;
            defer allocator.free(old);
            total +%= firstNumber(old) catch return 0;
            continue;
        };
        defer allocator.free(mm_stat);
        var words = std.mem.tokenizeAny(u8, mm_stat, " \t\r\n");
        _ = words.next() orelse return 0;
        _ = words.next() orelse return 0;
        total +%= firstNumber(words.next() orelse return 0) catch return 0;
    }
    return total;
}
pub fn readMemInfo(io: Io, allocator: Allocator) ![meminfo_tags.len]u64 {
    const contents = try readText(io, allocator, "/proc/meminfo");
    defer allocator.free(contents);
    var result = try parseMemInfo(contents);
    result[10] = zramBytes(io, allocator) / 1024;
    if (result[12] == 0) {
        const vmalloc = readText(io, allocator, "/proc/vmallocinfo") catch return result;
        defer allocator.free(vmalloc);
        var lines = std.mem.splitScalar(u8, vmalloc, '\n');
        while (lines.next()) |line| {
            const offset = std.mem.indexOf(u8, line, "pages=") orelse continue;
            result[12] +%= (firstNumber(line[offset + 6 ..]) catch continue) *% (std.heap.pageSize() / 1024);
        }
    }
    return result;
}

test "smaps preserves Android heap, sub-heap, BSS and swap accounting" {
    const info = try parseSmaps(
        "1000-2000 rw-p 00000000 00:00 0 [anon:dalvik-main space]\nPss: 10 kB\nRss: 12 kB\nPrivate_Dirty: 10 kB\nSwapPss: 2 kB\n" ++
            "3000-4000 r--p 00000000 08:01 1 /lib/libfoo.so (deleted)\nPss: 10 kB\nPrivate_Clean: 3 kB\nPrivate_Dirty: 3 kB\nShared_Clean: 20 kB\n" ++
            "4000-5000 rw-p 00000000 00:00 0\nPss: 4 kB\nPrivate_Dirty: 4 kB\n" ++
            "6000-7000 rw-p 00000000 00:00 0 [heap]\nPss: 7 kB\nPrivate_Dirty: 7 kB\n",
    );
    try std.testing.expectEqual(@as(u64, 10), info.heaps[@backingInt(Heap.dalvik_normal)].pss);
    try std.testing.expectEqual(@as(u64, 14), info.heaps[@backingInt(Heap.so)].pss);
    try std.testing.expectEqual(@as(u64, 3), info.heaps[@backingInt(Heap.so)].swappable_pss);
    try std.testing.expectEqual(@as(u64, 7), info.heaps[@backingInt(Heap.native)].pss);
    try std.testing.expectEqual(@as(u64, 31), info.total().pss);
    try std.testing.expectEqual(@as(u64, 2), info.total().swapped_out_pss);
    try std.testing.expect(info.has_swap_pss);
    try std.testing.expect(!(try parseSmaps("1000-2000 rw-p 00000000 00:00 0\nSwapPss: 0 kB\n")).has_swap_pss);
    try std.testing.expectError(error.InvalidSmaps, parseSmaps("not smaps\n"));
}

test "system memory tags keep kernel units, optional fields, and reject corrupt values" {
    const values = try parseMemInfo("MemTotal: 1234 kB\nMemFree: 31 kB\nCached: 8 kB\nActive(anon): 6 kB\nCmaFree: 9 kB\n");
    try std.testing.expectEqual(@as(u64, 1234), values[0]);
    try std.testing.expectEqual(@as(u64, 0), values[10]);
    try std.testing.expectEqual(@as(u64, 6), values[20]);
    try std.testing.expectEqual(@as(u64, 9), values[25]);
    try std.testing.expectError(error.InvalidCharacter, parseMemInfo("MemTotal: broken\n"));
}

test "live procfs observations use the active Io" {
    const info = try readMemoryInfo(std.testing.io, std.testing.allocator, std.os.linux.getpid());
    try std.testing.expect(info.total().pss > 0);
    const pss = try readPss(std.testing.io, std.testing.allocator, std.os.linux.getpid());
    try std.testing.expect(pss.rss >= pss.uss());
    try std.testing.expect(try readRss(std.testing.io, std.testing.allocator, std.os.linux.getpid()) > 0);
    const system = try readMemInfo(std.testing.io, std.testing.allocator);
    try std.testing.expect(system[0] > system[1]);
}

/// Pinned libmeminfo ReadKernelCmaUsageKb: successful allocations minus releases.
pub fn readCmaUsage(io: Io, allocator: Allocator) !u64 {
    var dir = try Io.Dir.cwd().openDir(io, "/sys/kernel/mm/cma", .{ .iterate = true });
    defer dir.close(io);
    var iterator = dir.iterate();
    var pages: u64 = 0;
    while (try iterator.next(io)) |entry| {
        const allocated_path = try std.fmt.allocPrint(allocator, "/sys/kernel/mm/cma/{s}/alloc_pages_success", .{entry.name});
        defer allocator.free(allocated_path);
        const released_path = try std.fmt.allocPrint(allocator, "/sys/kernel/mm/cma/{s}/release_pages_success", .{entry.name});
        defer allocator.free(released_path);
        pages +%= (try readScalar(io, allocator, allocated_path)) -% (try readScalar(io, allocator, released_path));
    }
    return pages *% (std.heap.pageSize() / 1024);
}

/// Pinned libdmabufinfo GetDmabufTotalExportedKb, optionally filtered by DMA heaps.
pub fn readDmaBufExported(io: Io, allocator: Allocator, heaps_only: bool) !u64 {
    var heaps: std.StringHashMapUnmanaged(void) = .empty;
    defer {
        var keys = heaps.keyIterator();
        while (keys.next()) |key| allocator.free(key.*);
        heaps.deinit(allocator);
    }
    if (heaps_only) {
        var heap_dir = Io.Dir.cwd().openDir(io, "/dev/dma_heap", .{ .iterate = true }) catch
            return readScalar(io, allocator, "/sys/kernel/ion/total_heaps_kb");
        defer heap_dir.close(io);
        var iterator = heap_dir.iterate();
        while (try iterator.next(io)) |entry| {
            const name = try allocator.dupe(u8, entry.name);
            errdefer allocator.free(name);
            try heaps.put(allocator, name, {});
        }
        if (heaps.count() == 0) return error.NoDmaHeaps;
    }
    var dir = try Io.Dir.cwd().openDir(io, "/sys/kernel/dmabuf/buffers", .{ .iterate = true });
    defer dir.close(io);
    var iterator = dir.iterate();
    var bytes: u64 = 0;
    while (try iterator.next(io)) |entry| {
        const path = try std.fmt.allocPrint(allocator, "/sys/kernel/dmabuf/buffers/{s}/size", .{entry.name});
        defer allocator.free(path);
        const size = try readScalar(io, allocator, path);
        if (heaps_only) {
            const exporter_path = try std.fmt.allocPrint(allocator, "/sys/kernel/dmabuf/buffers/{s}/exporter_name", .{entry.name});
            defer allocator.free(exporter_path);
            const exporter = try readText(io, allocator, exporter_path);
            defer allocator.free(exporter);
            if (!heaps.contains(std.mem.trim(u8, exporter, " \t\r\n"))) continue;
        }
        bytes +%= size;
    }
    return bytes / 1024;
}

/// Pinned ReadDmaBufMapRefs: unique inodes, sysfs size preferred to mapped range.
pub fn readDmaBufMapped(io: Io, allocator: Allocator) !u64 {
    var dir = try Io.Dir.cwd().openDir(io, "/proc", .{ .iterate = true });
    defer dir.close(io);
    var iterator = dir.iterate();
    var buffers: std.AutoHashMapUnmanaged(u64, u64) = .empty;
    defer buffers.deinit(allocator);
    while (try iterator.next(io)) |entry| {
        if (entry.kind != .directory) continue;
        const pid = std.fmt.parseInt(u32, entry.name, 10) catch continue;
        if (pid == 0) continue;
        var path_buffer: [96]u8 = undefined;
        const path = try std.fmt.bufPrint(&path_buffer, "/proc/{d}/maps", .{pid});
        const maps = readText(io, allocator, path) catch continue;
        defer allocator.free(maps);
        var lines = std.mem.splitScalar(u8, maps, '\n');
        while (lines.next()) |line| {
            const mapping = parseMapping(line) orelse continue;
            if (!starts(u8, mapping.name, "/dmabuf") or buffers.contains(mapping.inode)) continue;
            const size_path = try std.fmt.bufPrint(&path_buffer, "/sys/kernel/dmabuf/buffers/{d}/size", .{mapping.inode});
            const size = readScalar(io, allocator, size_path) catch (mapping.end - mapping.start);
            try buffers.put(allocator, mapping.inode, size);
        }
    }
    var total: u64 = 0;
    var sizes = buffers.valueIterator();
    while (sizes.next()) |size| total +%= size.* / 1024;
    return total;
}
