//! System font configuration from fontconfig. Android's text stack (minikin,
//! through android.graphics.fonts.SystemFonts) loads a static
//! font_fallback.xml naming families and per-script fallback fonts; a desktop
//! has fontconfig instead. At launcher start we ask fontconfig for the generic
//! families (sans-serif/serif/monospace at every weight/style), the emoji
//! font, and its sorted coverage-trimmed fallback chain, and write an
//! Android-style font_fallback.xml that minikin then loads unchanged.
//! Expose selected files in a dedicated font directory for stock native discovery.
const std = @import("std");

const fc = struct {
    const Config = opaque {};
    const Pattern = opaque {};
    const CharSet = opaque {};
    const FontSet = extern struct { nfont: c_int, sfont: c_int, fonts: [*]?*Pattern };
    const MatchPattern: c_int = 0;
    const ResultMatch: c_int = 0;
    extern fn FcInit() c_int;
    extern fn FcNameParse(name: [*:0]const u8) ?*Pattern;
    extern fn FcPatternAddInteger(p: *Pattern, object: [*:0]const u8, i: c_int) c_int;
    extern fn FcConfigSubstitute(config: ?*Config, p: *Pattern, kind: c_int) c_int;
    extern fn FcDefaultSubstitute(p: *Pattern) void;
    extern fn FcFontMatch(config: ?*Config, p: *Pattern, result: *c_int) ?*Pattern;
    extern fn FcFontSort(config: ?*Config, p: *Pattern, trim: c_int, csp: ?*?*CharSet, result: *c_int) ?*FontSet;
    extern fn FcPatternGetString(p: *Pattern, object: [*:0]const u8, n: c_int, s: *[*:0]u8) c_int;
    extern fn FcPatternGetInteger(p: *Pattern, object: [*:0]const u8, n: c_int, i: *c_int) c_int;
    extern fn FcPatternDestroy(p: *Pattern) void;
    extern fn FcFontSetDestroy(s: *FontSet) void;
    extern fn FcWeightToOpenType(fc_weight: c_int) c_int;
    extern fn FcWeightFromOpenType(ot_weight: c_int) c_int;
};

const Face = struct { file: []const u8, index: i32, weight: i32, italic: bool };

fn getString(p: *fc.Pattern, obj: [*:0]const u8) ?[]const u8 {
    var s: [*:0]u8 = undefined;
    if (fc.FcPatternGetString(p, obj, 0, &s) != fc.ResultMatch) return null;
    return std.mem.span(s);
}
fn getInt(p: *fc.Pattern, obj: [*:0]const u8) ?i32 {
    var i: c_int = 0;
    if (fc.FcPatternGetInteger(p, obj, 0, &i) != fc.ResultMatch) return null;
    return i;
}

fn faceOf(gpa: std.mem.Allocator, p: *fc.Pattern) ?Face {
    const file = getString(p, "file") orelse return null;
    // SystemFonts strips whitespace from file names; skip such paths
    if (std.mem.indexOfAny(u8, file, " \t\n") != null) return null;
    if (!std.mem.startsWith(u8, file, "/")) return null;
    const idx = getInt(p, "index") orelse 0;
    const w = fc.FcWeightToOpenType(getInt(p, "weight") orelse 80);
    const slant = getInt(p, "slant") orelse 0;
    return .{
        .file = gpa.dupe(u8, file) catch return null,
        .index = idx & 0xffff, // fontconfig encodes named instances in the high bits
        .weight = @intCast(@max(100, @min(900, w))),
        .italic = slant != 0,
    };
}

fn match(gpa: std.mem.Allocator, spec: [*:0]const u8) ?Face {
    const p = fc.FcNameParse(spec) orelse return null;
    defer fc.FcPatternDestroy(p);
    _ = fc.FcConfigSubstitute(null, p, fc.MatchPattern);
    fc.FcDefaultSubstitute(p);
    var res: c_int = 0;
    const m = fc.FcFontMatch(null, p, &res) orelse return null;
    defer fc.FcPatternDestroy(m);
    return faceOf(gpa, m);
}

fn sameFace(a: Face, b: Face) bool {
    return a.index == b.index and std.mem.eql(u8, a.file, b.file);
}

fn fontName(file: []const u8) [68]u8 {
    var digest: [32]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(file, &digest, .{});
    var name: [68]u8 = undefined;
    name[0..64].* = std.fmt.bytesToHex(digest, .lower);
    // FreeType reads the file format; PDFium's directory scanner selects these extensions.
    name[64..].* = ".ttf".*;
    return name;
}

pub const Configuration = struct {
    xml: [:0]const u8,
    directory: [:0]const u8,

    pub fn deinit(self: Configuration, allocator: std.mem.Allocator) void {
        allocator.free(self.xml);
        allocator.free(self.directory);
    }
};

fn writeFont(out: *std.ArrayList(u8), gpa: std.mem.Allocator, f: Face) !void {
    var buf: [64]u8 = undefined;
    try out.appendSlice(gpa, "        <font weight=\"");
    try out.appendSlice(gpa, try std.fmt.bufPrint(&buf, "{d}", .{f.weight}));
    try out.appendSlice(gpa, "\" style=\"");
    try out.appendSlice(gpa, if (f.italic) "italic" else "normal");
    if (f.index != 0) {
        try out.appendSlice(gpa, "\" index=\"");
        try out.appendSlice(gpa, try std.fmt.bufPrint(&buf, "{d}", .{f.index}));
    }
    try out.appendSlice(gpa, "\">");
    const name = fontName(f.file);
    try out.appendSlice(gpa, &name);
    try out.appendSlice(gpa, "</font>\n");
}

/// Query fontconfig for a named generic family at the standard weights/styles.
fn family(out: *std.ArrayList(u8), gpa: std.mem.Allocator, name: []const u8, fcname: []const u8, seen: *std.ArrayList(Face)) !void {
    var faces: std.ArrayList(Face) = .empty;
    const weights = [_]i32{ 100, 200, 300, 400, 500, 600, 700, 800, 900 };
    for ([_]bool{ false, true }) |italic| {
        for (weights) |w| {
            var spec: [128]u8 = undefined;
            const n = (try std.fmt.bufPrint(spec[0 .. spec.len - 1], "{s}:weight={d}:slant={d}", .{ fcname, fc.FcWeightFromOpenType(w), @as(i32, if (italic) 100 else 0) })).len;
            spec[n] = 0;
            const f = match(gpa, spec[0..n :0]) orelse continue;
            if (f.italic != italic) continue; // no italic face: don't fake one
            var dup = false;
            for (faces.items) |e| if (sameFace(e, f)) {
                dup = true;
                break;
            };
            if (!dup) try faces.append(gpa, f);
        }
    }
    if (faces.items.len == 0) return;
    try out.appendSlice(gpa, "    <family name=\"");
    try out.appendSlice(gpa, name);
    try out.appendSlice(gpa, "\">\n");
    for (faces.items) |f| {
        try writeFont(out, gpa, f);
        try seen.append(gpa, f);
    }
    try out.appendSlice(gpa, "    </family>\n");
}

/// Generate the XML and immutable font directory; the caller owns both paths.
pub fn generate(gpa: std.mem.Allocator, io: std.Io, environ: anytype) ?Configuration {
    if (fc.FcInit() == 0) return null;
    var arena = std.heap.ArenaAllocator.init(gpa);
    defer arena.deinit();
    const a = arena.allocator();
    var out: std.ArrayList(u8) = .empty;
    var seen: std.ArrayList(Face) = .empty;
    out.appendSlice(a, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- generated by artzig from fontconfig at launch -->\n<familyset version=\"23\">\n") catch return null;
    family(&out, a, "sans-serif", "sans-serif", &seen) catch return null;
    family(&out, a, "serif", "serif", &seen) catch return null;
    family(&out, a, "monospace", "monospace", &seen) catch return null;
    family(&out, a, "sans-serif-condensed", "sans-serif:width=condensed", &seen) catch return null;
    // Preserve the legacy family aliases in pinned AOSP data/fonts/fonts.xml.
    // WebView uses Arial/Sans as its last-resort family for native form controls;
    // omitting Arial can leave Blink without any font and abort the renderer.
    out.appendSlice(a,
        \\    <alias name="sans-serif-thin" to="sans-serif" weight="100" />
        \\    <alias name="sans-serif-light" to="sans-serif" weight="300" />
        \\    <alias name="sans-serif-medium" to="sans-serif" weight="500" />
        \\    <alias name="sans-serif-black" to="sans-serif" weight="900" />
        \\    <alias name="arial" to="sans-serif" />
        \\    <alias name="helvetica" to="sans-serif" />
        \\    <alias name="tahoma" to="sans-serif" />
        \\    <alias name="verdana" to="sans-serif" />
        \\    <alias name="sans-serif-condensed-light" to="sans-serif-condensed" weight="300" />
        \\    <alias name="sans-serif-condensed-medium" to="sans-serif-condensed" weight="500" />
        \\    <alias name="serif-bold" to="serif" weight="700" />
        \\    <alias name="times" to="serif" />
        \\    <alias name="times new roman" to="serif" />
        \\    <alias name="palatino" to="serif" />
        \\    <alias name="georgia" to="serif" />
        \\    <alias name="baskerville" to="serif" />
        \\    <alias name="goudy" to="serif" />
        \\    <alias name="ITC Stone Serif" to="serif" />
        \\    <alias name="serif-monospace" to="monospace" />
        \\    <alias name="sans-serif-monospace" to="monospace" />
        \\    <alias name="monaco" to="monospace" />
        \\    <alias name="courier" to="serif-monospace" />
        \\    <alias name="courier new" to="serif-monospace" />
        \\    <alias name="casual" to="sans-serif" />
        \\    <alias name="cursive" to="serif" />
        \\    <alias name="fantasy" to="serif" />
        \\
    ) catch return null;

    // emoji: fontconfig's generic "emoji" family (Noto Color Emoji etc.)
    if (match(a, "emoji")) |f| {
        const fname = std.fs.path.basename(f.file);
        var lower: [256]u8 = undefined;
        const lname = std.ascii.lowerString(lower[0..@min(fname.len, lower.len)], fname[0..@min(fname.len, lower.len)]);
        if (std.mem.indexOf(u8, lname, "emoji") != null) {
            out.appendSlice(a, "    <family lang=\"und-Zsye\">\n") catch return null;
            writeFont(&out, a, f) catch return null;
            out.appendSlice(a, "    </family>\n") catch return null;
            seen.append(a, f) catch return null;
        }
    }

    // fallback chain: fontconfig's coverage-trimmed sort for sans-serif
    var n_fallback: usize = 0;
    if (fc.FcNameParse("sans-serif")) |p| {
        defer fc.FcPatternDestroy(p);
        _ = fc.FcConfigSubstitute(null, p, fc.MatchPattern);
        fc.FcDefaultSubstitute(p);
        var res: c_int = 0;
        if (fc.FcFontSort(null, p, 1, null, &res)) |set| {
            defer fc.FcFontSetDestroy(set);
            var i: usize = 0;
            while (i < @as(usize, @intCast(set.nfont)) and n_fallback < 80) : (i += 1) {
                const pat = set.fonts[i] orelse continue;
                const f = faceOf(a, pat) orelse continue;
                if (f.italic or f.weight != 400) continue;
                var dup = false;
                for (seen.items) |e| if (sameFace(e, f)) {
                    dup = true;
                    break;
                };
                if (dup) continue;
                out.appendSlice(a, "    <family>\n") catch return null;
                writeFont(&out, a, f) catch return null;
                out.appendSlice(a, "    </family>\n") catch return null;
                seen.append(a, f) catch return null;
                n_fallback += 1;
            }
        }
    }
    out.appendSlice(a, "</familyset>\n") catch return null;

    // A content-addressed font view gives concurrent launchers the same complete
    // selection without exposing the host filesystem to recursive font scanners.
    var digest: [32]u8 = undefined;
    std.crypto.hash.sha2.Sha256.hash(out.items, &digest, .{});
    const runtime_dir = environ.getPosix("XDG_RUNTIME_DIR") orelse "/tmp";
    const root = std.fmt.allocPrint(a, "{s}/artzig/font-configs/{s}", .{ runtime_dir, std.fmt.bytesToHex(digest, .lower) }) catch return null;
    const directory = std.fmt.allocPrint(a, "{s}/fonts", .{root}) catch return null;
    const cwd = std.Io.Dir.cwd();
    _ = cwd.createDirPathStatus(io, directory, .fromMode(0o700)) catch return null;
    for (seen.items) |face| {
        const name = fontName(face.file);
        const link = std.fs.path.join(a, &.{ directory, &name }) catch return null;
        cwd.symLinkAtomic(io, face.file, link, .{}) catch return null;
    }
    const path = std.fmt.allocPrint(a, "{s}/font_fallback.xml", .{root}) catch return null;
    var atomic = cwd.createFileAtomic(io, path, .{ .permissions = .fromMode(0o600), .replace = true }) catch return null;
    defer atomic.deinit(io);
    var buffer: [4096]u8 = undefined;
    var writer = atomic.file.writer(io, &buffer);
    writer.interface.writeAll(out.items) catch return null;
    writer.flush() catch return null;
    atomic.replace(io) catch return null;
    const xml = gpa.dupeSentinel(u8, path, 0) catch return null;
    const font_directory = gpa.dupeSentinel(u8, directory, 0) catch {
        gpa.free(xml);
        return null;
    };
    std.debug.print("azk: fonts: {d} faces from fontconfig ({d} fallback families) -> {s}\n", .{ seen.items.len, n_fallback, path });
    return .{ .xml = xml, .directory = font_directory };
}
