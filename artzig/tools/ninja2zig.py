#!/usr/bin/env python3
"""Generate build/gen/<name>.zig from Soong's ninja graph.

    tools/ninja2zig.py --name art --ninja out/combined-silvermont-build-art-host.ninja \
        out/soong/.intermediates/art/runtime/libart/linux_glibc_x86_64_shared/libart.so ...

For each requested output, `ninja -t commands` gives the exact command closure.
We translate:
  clang/clang++ -c ...      -> per-file C/C++/asm compile units (flags kept verbatim
                               except toolchain/sysroot/libc++ bits that Zig provides)
  llvm-ar crs out.a @rsp    -> static library
  clang++ @rsp -o out.so    -> shared library (or executable)
Anything else (genrules, aconfig, prebuilt copies) is *not* translated: their
outputs are read from the Soong out/ tree ("hybrid" mode). They are listed in the
generated file's header so they can be ported by hand.

The emitted Zig exposes `pub fn build(b, opts) Artifacts` where opts carries the
vendor/, extra/ and out/ roots as LazyPaths.
"""
import argparse, json, os, re, shlex, subprocess, sys
from collections import OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR = os.path.join(ROOT, "vendor")
OUT = os.path.join(ROOT, "out")

# Flags Zig's driver supplies itself or that make no sense outside Soong's toolchain.
DROP_FLAGS_EXACT = {
    "-m64", "-no-canonical-prefixes", "-fcolor-diagnostics", "-nostdinc++", "-nostdlib++",
    "-faddrsig", "-gsimple-template-names", "-gz=zstd", "-Wa,--noexecstack", "-fuse-ld=lld",
    "-Wl,--compress-debug-sections=zstd", "-Wl,--icf=safe", "-Wl,--no-demangle",
    "-Wl,--no-undefined-version", "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now",
    "-Wl,--no-undefined", "-Werror", "-fdebug-default-version=5",
    "-Wl,--exclude-libs=libclang_rt.builtins-x86_64.a", "-Wl,--exclude-libs=libziparchive.a",
    "-Wl,--exclude-libs=libunwind.a", "-Wl,--exclude-libs=libgcc.a", "-Wl,--exclude-libs=libgcc_stripped.a",
    "-Wl,--hash-style=gnu", "-Wl,--gc-sections", "-Wl,--build-id=md5", "-Wl,--no-rosegment",
    "-Wl,-z,separate-code", "-Wl,--pack-dyn-relocs=relr", "-Wl,--icf=none", "-Wl,--no-gc-sections",
    "-Wunguarded-availability", "-Wno-cast-function-type-mismatch", "-fstack-protector-strong",
    "-fstack-protector", "-fvisibility-inlines-hidden",
}
DROP_FLAGS_PREFIX = (
    "-fsanitize",  # zig supplies its own (ubsan) runtime; Soong's minimal-runtime variant is unavailable
    "--gcc-toolchain=", "--sysroot", "-B", "-target", "-fdebug-prefix-map=", "-MD", "-MF",
    "-Wl,-rpath,", "-Wl,-soname,", "-Wl,--version-script", "-Lprebuilts/", "-Wl,--exclude-libs=",
    "-Werror=",  # keep builds going on newer clang; warnings only
)
DROP_INCLUDE_PREFIX = ("prebuilts/clang/", "prebuilts/gcc/")
# libs Soong links explicitly that Zig provides via link_libc/link_libcpp
DROP_LIBS = {"-ldl", "-lpthread", "-lm", "-lrt", "-lc", "-lgcc", "-lgcc_s"}


def run_ninja(ninja, targets):
    cmd = ["ninja", "-f", ninja, "-t", "commands"] + targets
    out = subprocess.run(cmd, cwd=VENDOR, capture_output=True, text=True, check=True).stdout
    seen, cmds = set(), []
    for line in out.splitlines():
        if line not in seen:
            seen.add(line); cmds.append(line)
    return cmds


def classify(c):
    if re.search(r"\bclang(\+\+)? -c ", c):
        return "compile"
    if "llvm-ar " in c and "@" in c:
        return "archive"
    if re.search(r"\bclang\+\+ +@\S+\.rsp +-o ", c) or re.search(r"\bclang\+\+ .* -o \S+\.so ", c):
        return "link"
    return "other"


def norm(path):
    """Normalize a path from a ninja command to (root, rel) where root in {vendor,out,abs}."""
    if path.startswith(OUT + "/"):
        return ("out", path[len(OUT) + 1:])
    if path.startswith(VENDOR + "/"):
        return ("vendor", path[len(VENDOR) + 1:])
    if path.startswith("/"):
        return ("abs", path)
    return ("vendor", path)


def parse_compile(c):
    # strip env prefix like PWD=/proc/self/cwd
    argv = shlex.split(c)
    while "=" in argv[0] and not argv[0].startswith("-"):
        argv.pop(0)
    argv.pop(0)  # compiler
    src = argv[-1]
    obj = None
    flags, includes, i = [], [], 0
    while i < len(argv) - 1:
        a = argv[i]
        if a == "-o":
            obj = argv[i + 1]; i += 2; continue
        if a in ("-MF", "-MD", "-MMD", "-MT", "-MQ"):
            i += 2 if a in ("-MF", "-MT", "-MQ") else 1; continue
        if a == "-c":
            i += 1; continue
        if a in ("--sysroot", "-target", "-isystem", "-include"):
            if a in ("-isystem", "-include"):
                p = argv[i + 1]
                if not p.startswith(DROP_INCLUDE_PREFIX):
                    includes.append((a, p))
            i += 2; continue
        if a.startswith("-I"):
            p = a[2:] if len(a) > 2 else argv[i + 1]
            if len(a) == 2: i += 1
            if not p.startswith(DROP_INCLUDE_PREFIX):
                includes.append(("-I", p))
            i += 1; continue
        if a in DROP_FLAGS_EXACT or a.startswith(DROP_FLAGS_PREFIX):
            i += 1; continue
        flags.append(a); i += 1
    return {"src": src, "obj": obj, "flags": flags, "includes": includes}


def read_rsp(path):
    with open(path) as f:
        return shlex.split(f.read())


def parse_archive(c):
    m = re.search(r"llvm-ar \S+ (?:--format=\w+ )?(\S+\.a) @(\S+)", c)
    out, rsp = m.group(1), m.group(2)
    return {"out": out, "objs": read_rsp(rsp)}


def parse_link(c):
    argv = shlex.split(c)
    argv.pop(0)
    out = None; inputs = []; flags = []; libs = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "-o":
            out = argv[i + 1]; i += 2; continue
        if a.startswith("@"):
            inputs.extend(read_rsp(a[1:])); i += 1; continue
        if a in ("--sysroot", "-target"):
            i += 2; continue
        if a.startswith("-l"):
            if a not in DROP_LIBS: libs.append(a)
            i += 1; continue
        if a.startswith("-Wl,--version-script,"):
            flags.append(a); i += 1; continue
        if a in DROP_FLAGS_EXACT or a.startswith(DROP_FLAGS_PREFIX) or a in ("-m64", "-shared", "-static-libgcc"):
            i += 1; continue
        if a.startswith("-") :
            flags.append(a); i += 1; continue
        inputs.append(a); i += 1
    objs = [x for x in inputs if x.endswith(".o")]
    static = []; whole = False
    shared = []
    for x in inputs:
        if x == "-Wl,--whole-archive": whole = True; continue
        if x == "-Wl,--no-whole-archive": whole = False; continue
        if x.endswith(".a"): static.append((x, whole))
        elif x.endswith(".so"): shared.append(x)
    return {"out": out, "objs": objs, "static": static, "shared": shared, "flags": flags, "libs": libs,
            "kind": "shared" if out.endswith(".so") else "exe"}


def zig_str(s):
    return json.dumps(s)


def lazy(path):
    root, rel = norm(path)
    if root == "out":
        return f"o.out.path(b, {zig_str(rel)})"
    if root == "vendor":
        return f"o.vendor.path(b, {zig_str(rel)})"
    return f".{{ .cwd_relative = {zig_str(rel)} }}"


def gen(name, cmds, targets):
    compiles = OrderedDict(); archives = []; links = []; other = []
    for c in cmds:
        k = classify(c)
        try:
            if k == "compile":
                cu = parse_compile(c); compiles[cu["obj"]] = cu
            elif k == "archive":
                archives.append(parse_archive(c))
            elif k == "link":
                links.append(parse_link(c))
            else:
                other.append(c)
        except Exception as e:  # keep going; report
            other.append(f"# PARSE-FAILED ({e}): {c[:200]}")

    # module name from intermediates path: .../<dir>/<module>/<variant>/<file>
    def modname(path):
        parts = path.split("/")
        if ".intermediates" in parts:
            i = parts.index(".intermediates")
            return parts[-3], parts[-2]  # module, variant
        return os.path.basename(path), ""

    ids = {}
    def ident(path):
        mod, var = modname(path)
        base = re.sub(r"[^A-Za-z0-9_]", "_", mod)
        if var.endswith("_static") and not path.endswith(".so"): base += "_static"
        if base in ids and ids[base] != path:
            base = re.sub(r"[^A-Za-z0-9_]", "_", mod + "_" + var)
        ids[base] = path
        return base

    L = []
    w = L.append

    # module name from intermediates path: .../<bp dir>/<module>/<variant>/<file>
    def modname(path):
        parts = path.split("/")
        if ".intermediates" in parts:
            return parts[-3], parts[-2]
        return os.path.basename(path), ""

    ids = {}
    def ident(path):
        mod, var = modname(path)
        base = re.sub(r"[^A-Za-z0-9_]", "_", mod)
        if var.endswith("_static") and not path.endswith(".so"): base += "_static"
        if base in ids and ids[base] != path:
            base = re.sub(r"[^A-Za-z0-9_]", "_", mod + "_" + var)
        ids[base] = path
        return base

    # an object may be in several archives (Soong merges whole_static_libs' objects)
    obj_owner = {}
    for a in archives:
        for ob in a["objs"]: obj_owner.setdefault(ob, set()).add(a["out"])
    for l in links:
        for ob in l["objs"]: obj_owner.setdefault(ob, set()).add(l["out"])

    all_artifacts = OrderedDict()
    for a in archives: all_artifacts[a["out"]] = ("static", a)
    for l in links: all_artifacts[l["out"]] = (l["kind"], l)
    want = [t for t in targets if t in all_artifacts]
    reach = OrderedDict()
    stack = list(want)
    while stack:
        t = stack.pop()
        if t in reach or t not in all_artifacts: continue
        reach[t] = all_artifacts[t]
        kind, info = all_artifacts[t]
        if kind != "static":
            stack.extend(d for d, _ in info["static"]); stack.extend(info["shared"])
    def has_objs(t, v):
        if v[0] == "static": return True
        return bool(v[1]["objs"]) or bool(v[1]["static"])  # prebuilt copies have neither
    artifacts = OrderedDict()
    for t, v in reversed(list(reach.items())):
        if v[0] == "static": artifacts[t] = v
    for t, v in reversed(list(reach.items())):
        if v[0] != "static" and has_objs(t, v): artifacts[t] = v
    names = {path: ident(path) for path in artifacts}

    # --- units per artifact, base flags = intersection over every unit we emit
    units_of = {}
    for path in artifacts:
        units_of[path] = [cu for ob, cu in compiles.items() if path in obj_owner.get(ob, ())]
    all_units = [cu for us in units_of.values() for cu in us]
    base_set = None
    for cu in all_units:
        fs = set(cu["flags"])
        base_set = fs if base_set is None else base_set & fs
    base_set = base_set or set()
    base_flags = [f for f in all_units[0]["flags"] if f in base_set] if all_units else []
    seen_b = set(); base_flags = [f for f in base_flags if not (f in seen_b or seen_b.add(f))]

    def split_gen(rel):
        """out-relative path -> (key up to and including the 'gen' dir, remainder) or None"""
        parts = rel.split("/")
        if "gen" in parts:
            i = parts.index("gen")
            return "/".join(parts[:i + 1]), "/".join(parts[i + 1:])
        return None

    # --- include sets, deduplicated and named
    inc_sets = OrderedDict()   # tuple(incs) -> name
    def inc_name(incs):
        key = tuple(incs)
        if key not in inc_sets: inc_sets[key] = f"inc_{len(inc_sets)}"
        return inc_sets[key]
    delta_sets = OrderedDict()  # tuple(delta flags) -> name
    def delta_name(delta):
        if delta not in delta_sets: delta_sets[delta] = f"cf_{len(delta_sets)}"
        return delta_sets[delta]
    groups_of = {}
    for path, us in units_of.items():
        groups = OrderedDict()
        for cu in us:
            delta = tuple(f for f in cu["flags"] if f not in base_set)
            groups.setdefault((delta_name(delta), inc_name(cu["includes"])), []).append(cu["src"])
        groups_of[path] = groups

    asm_cu = next((cu for cu in compiles.values() if cu["src"].endswith("cpp-define-generator/asm_defines.cc")), None)
    if asm_cu: inc_name(asm_cu["includes"])

    # --- generated inputs report
    w("// GENERATED by tools/ninja2zig.py — do not edit. Regenerate with tools/regen.sh after a Soong build.")
    w(f"// Target set: {name}")
    w("//")
    w("// Inputs taken from Soong's out/ tree (generated sources/headers, prebuilts) — port these genrules by hand:")
    gen_inputs = set()
    for cu in all_units:
        if norm(cu["src"])[0] == "out": gen_inputs.add(norm(cu["src"])[1])
        for _, p_ in cu["includes"]:
            if norm(p_)[0] == "out": gen_inputs.add(norm(p_)[1] + "/")
    for path, (kind, info) in artifacts.items():
        if kind == "static": continue
        for dep, _ in info["static"]:
            if not dep.startswith(OUT): gen_inputs.add("(prebuilt) " + dep)
    for g in sorted(gen_inputs): w("//   " + g)
    w('const std = @import("std");')
    w("")
    w("pub const Options = struct {")
    w("    target: std.Build.ResolvedTarget,")
    w("    optimize: std.builtin.OptimizeMode,")
    w("    vendor: std.Build.LazyPath,")
    w("    out: std.Build.LazyPath,")
    w("    /// absolute paths of the same two roots (include paths are passed per compile unit as -I)")
    w("    vendor_dir: []const u8,")
    w("    out_dir: []const u8,")
    w("    /// absolute path of the artzig package root (for build/gen/prelude)")
    w("    root_dir: []const u8,")
    w("    /// extra flags applied to every compile unit (e.g. -isystem for headers Soong's sysroot had: libcap)")
    w("    extra_cflags: []const []const u8 = &.{},")
    w("    /// generated-source roots (see build/genrules.zig); keys are out/-relative Soong gen dirs.")
    w("    /// Anything not provided falls back to reading Soong's out/ tree.")
    w("    gen_dirs: []const GenDir = &.{},")
    w("};")
    w("pub const GenDir = struct { key: []const u8, dir: std.Build.LazyPath };")
    w("fn genDir(b: *std.Build, o: Options, key: []const u8) std.Build.LazyPath {")
    w("    for (o.gen_dirs) |g| if (std.mem.eql(u8, g.key, key)) return g.dir;")
    w("    return o.out.path(b, key);")
    w("}")
    w("")
    w("const Root = enum { vendor, out };")
    w("const Inc = struct { root: Root, rel: []const u8, kind: []const u8 = \"-I\" };")
    w("")
    w("/// Flags every compile unit in this target set shares (intersection over all units).")
    w("pub const base_cflags = [_][]const u8{")
    for i in range(0, len(base_flags), 6):
        w("    " + ", ".join(zig_str(f) for f in base_flags[i:i+6]) + ",")
    w("};")
    w("")
    w("fn rootDir(o: Options, r: Root) []const u8 {")
    w("    return switch (r) { .vendor => o.vendor_dir, .out => o.out_dir };")
    w("}")
    w("fn incSet(b: *std.Build, o: Options, list: []const Inc) []const []const u8 {")
    w("    var out: std.ArrayList([]const u8) = .empty;")
    w("    for (list) |i| {")
    w("        if (std.mem.startsWith(u8, i.kind, \"gen:\")) continue; // generated dirs: added per module, see addGenIncludes")
    w("        if (i.kind.len == 2) { // -I<path>")
    w("            out.append(b.allocator, b.fmt(\"{s}{s}/{s}\", .{ i.kind, rootDir(o, i.root), i.rel })) catch @panic(\"OOM\");")
    w("        } else { // -isystem / -include <path>")
    w("            out.append(b.allocator, i.kind) catch @panic(\"OOM\");")
    w("            out.append(b.allocator, if (i.rel.len > 0 and i.rel[0] == '/') i.rel else b.fmt(\"{s}/{s}\", .{ rootDir(o, i.root), i.rel })) catch @panic(\"OOM\");")
    w("        }")
    w("    }")
    w("    return out.items;")
    w("}")
    w("/// Generated include dirs cannot be absolute strings at configure time; add them as module include paths.")
    w("/// They only contain uniquely named generated headers, so module-level ordering is harmless.")
    w("fn addGenIncludes(b: *std.Build, o: Options, m: *std.Build.Module, list: []const Inc) void {")
    w("    for (list) |i| if (std.mem.startsWith(u8, i.kind, \"gen:\")) {")
    w("        const d = genDir(b, o, i.kind[4..]);")
    w("        m.addIncludePath(if (i.rel.len == 0) d else d.path(b, i.rel));")
    w("    };")
    w("}")
    w("fn flags(b: *std.Build, o: Options, delta: []const []const u8, incs: []const []const u8) []const []const u8 {")
    w("    return std.mem.concat(b.allocator, []const u8, &.{ &base_cflags, delta, incs, o.extra_cflags }) catch @panic(\"OOM\");")
    w("}")
    w("// Same relocatable rpath set Soong gives host binaries (bin/ next to lib64/).")
    w("fn setRPaths(c: *std.Build.Step.Compile) void {")
    w("    for ([_][]const u8{ \"$ORIGIN/\", \"$ORIGIN/lib64\", \"$ORIGIN/../lib64\", \"$ORIGIN/../../lib64\" }) |r| c.root_module.addRPathSpecial(r);")
    w("}")
    w("// sanitize_c off: zig's ubsan runtime adds a 256K static-TLS block per .so, which then cannot be dlopen()ed")
    w("fn newLib(b: *std.Build, o: Options, name: []const u8, linkage: std.builtin.LinkMode) *std.Build.Step.Compile {")
    w("    const c = b.addLibrary(.{ .name = name, .linkage = linkage, .root_module = b.createModule(.{")
    w("        .target = o.target, .optimize = o.optimize, .link_libc = true, .link_libcpp = true, .sanitize_c = .off }) });")
    w("    if (linkage == .dynamic) setRPaths(c);")
    w("    return c;")
    w("}")
    w("fn newExe(b: *std.Build, o: Options, name: []const u8) *std.Build.Step.Compile {")
    w("    const c = b.addExecutable(.{ .name = name, .root_module = b.createModule(.{")
    w("        .target = o.target, .optimize = o.optimize, .link_libc = true, .link_libcpp = true, .sanitize_c = .off }) });")
    w("    setRPaths(c);")
    w("    return c;")
    w("}")
    w("")

    # include set constants (as Inc lists; resolved in build())
    def inc_entries(incs):
        out = []
        for kind, p_ in incs:
            r, rel = norm(p_)
            if kind == "-include" and "/" not in p_ and not os.path.exists(os.path.join(VENDOR, p_)):
                pre_dir = os.path.join(ROOT, "build", "gen", "prelude"); os.makedirs(pre_dir, exist_ok=True)
                pre = os.path.join(pre_dir, "include_" + p_.replace("/", "_"))
                with open(pre, "w") as f: f.write(f"#include <{p_}>\n")
                out.append(f'.{{ .root = .vendor, .rel = "{os.path.relpath(pre, VENDOR)}", .kind = "-include" }}')
            elif kind == "-I" and r == "out" and split_gen(rel):
                key, rest = split_gen(rel)
                out.append(f'.{{ .root = .out, .rel = {zig_str(rest)}, .kind = "gen:" ++ {zig_str(key)} }}')
            elif kind == "-I":
                out.append(f'.{{ .root = .{"out" if r == "out" else "vendor"}, .rel = {zig_str(rel)} }}')
            else:
                out.append(f'.{{ .root = .{"out" if r == "out" else "vendor"}, .rel = {zig_str(rel)}, .kind = {zig_str(kind)} }}')
        return out
    w("// Per-module flags on top of base_cflags (deduplicated).")
    for delta, nm in delta_sets.items():
        w(f"const {nm} = [_][]const u8{{")
        for i in range(0, len(delta), 5):
            w("    " + ", ".join(zig_str(f) for f in delta[i:i+5]) + ",")
        w("};")
    w("")
    for incs, nm in inc_sets.items():
        ents = inc_entries(list(incs))
        w(f"const {nm} = [_]Inc{{")
        for e in ents: w("    " + e + ",")
        w("};")
    w("")

    # per-artifact source functions (reused by whole-archive consumers)
    w("/// Resolved include sets, filled in by build().")
    w("const Incs = struct {")
    for nm in inc_sets.values(): w(f"    {nm}: []const []const u8,")
    w("};")
    w("")
    for path in artifacts:
        var = names[path]
        w(f"fn srcs_{var}(b: *std.Build, o: Options, I: Incs, m: *std.Build.Module) void {{")
        n = 0
        gen_inc_sets = set()
        for (delta, incn), srcs in groups_of[path].items():
            gen_inc_sets.add(incn)
            by_root = OrderedDict()
            for s_ in srcs:
                r, rel = norm(s_)
                if r == "out" and split_gen(rel):
                    key, rest = split_gen(rel); by_root.setdefault(("gen", key), []).append(rest)
                else:
                    by_root.setdefault(r, []).append(rel)
            for r, rels in by_root.items():
                n += len(rels)
                w("    m.addCSourceFiles(.{")
                if isinstance(r, tuple):
                    w(f"        .root = genDir(b, o, {zig_str(r[1])}),")
                else:
                    w(f"        .root = o.{'out' if r == 'out' else 'vendor'},")
                w("        .files = &.{")
                for i in range(0, len(rels), 4):
                    w("            " + ", ".join(zig_str(x) for x in rels[i:i+4]) + ",")
                w("        },")
                w(f"        .flags = flags(b, o, &{delta}, I.{incn}),")
                w("    });")
        for incn in sorted(gen_inc_sets):
            w(f"    addGenIncludes(b, o, m, &{incn});")
        if n == 0: w("    _ = .{ b, o, I, m };")
        w("}")
    w("")

    # --- genrule data: operator_out (key -> local dir + headers), asm_defines cflags
    op = OrderedDict()
    for cu in all_units:
        r, rel = norm(cu["src"])
        if r == "out" and rel.endswith(".operator_out.cc") and split_gen(rel):
            key, rest = split_gen(rel)
            hdr = rest[len("gensrcs/"):-len(".operator_out.cc")] + ".h"
            local = "/".join(key.split("/")[2:4])  # soong/.intermediates/<dir a>/<dir b>/...
            op.setdefault((key, local), []).append(hdr)
    w("pub const OperatorOut = struct { key: []const u8, local: []const u8, headers: []const []const u8 };")
    w("/// generate_operator_out.py invocations Soong performs for this target set")
    w("pub const operator_out = [_]OperatorOut{")
    for (key, local), hdrs in op.items():
        w(f"    .{{ .key = {zig_str(key)}, .local = {zig_str(local)}, .headers = &.{{")
        for h in hdrs: w(f"        {zig_str(h)},")
        w("    } },")
    w("};")
    if asm_cu:
        delta = tuple(f for f in asm_cu["flags"] if f not in base_set)
        w("/// flags Soong compiles art/tools/cpp-define-generator/asm_defines.cc with (for build/genrules.zig)")
        w("pub fn asmDefinesFlags(b: *std.Build, o: Options) []const []const u8 {")
        w(f"    return flags(b, o, &.{{ {', '.join(zig_str(x) for x in delta)} }}, incSet(b, o, &{inc_name(asm_cu['includes'])}));")
        w("}")
    w("")
    w("pub const Artifacts = struct {")
    for path in artifacts: w(f"    {names[path]}: *std.Build.Step.Compile,")
    w("};")
    w("")
    w("pub fn build(b: *std.Build, o: Options) Artifacts {")
    w("    const I: Incs = .{")
    for nm in inc_sets.values(): w(f"        .{nm} = incSet(b, o, &{nm}),")
    w("    };")
    for path, (kind, info) in artifacts.items():
        var = names[path]
        base = os.path.basename(path)
        libname = re.sub(r"^lib|\.(a|so)$", "", base) if kind != "exe" else base
        ctor = {"static": f'newLib(b, o, {zig_str(libname)}, .static)', "shared": f'newLib(b, o, {zig_str(libname)}, .dynamic)'}.get(kind, f'newExe(b, o, {zig_str(libname)})')
        w(f"    const {var} = {ctor};")
        w(f"    srcs_{var}(b, o, I, {var}.root_module);")
    for path, (kind, info) in artifacts.items():
        if kind == "static": continue
        var = names[path]
        for dep, whole in info["static"]:
            b_ = os.path.basename(dep)
            if dep in names and whole:
                w(f"    srcs_{names[dep]}(b, o, I, {var}.root_module); // --whole-archive")
            elif dep in names:
                w(f"    {var}.root_module.linkLibrary({names[dep]});")
            elif b_.startswith("libclang_rt.") or b_ in ("libc++.a", "libc++_static.a", "libc++abi.a", "libunwind.a"):
                pass
            elif b_ == "libcap.a":
                w(f"    {var}.root_module.linkSystemLibrary(\"cap\", .{{}}); // prebuilt libcap.a -> system")
            elif b_ == "librustlibs.a":
                # generated_rust_staticlib: only C-ABI consumer is rustc_demangle(); stubbed.
                w(f"    {var}.root_module.addCSourceFiles(.{{ .root = .{{ .cwd_relative = o.root_dir }}, .files = &.{{\"src/gen/rustlibs_stub.c\"}}, .flags = &.{{\"-fPIC\"}} }});")
            else:
                w(f"    {var}.root_module.addObjectFile({lazy(dep)}); // prebuilt static" + (" (whole-archive)" if whole else ""))
        for dep in info["shared"]:
            b_ = os.path.basename(dep)
            if dep in names:
                w(f"    {var}.root_module.linkLibrary({names[dep]});")
            elif "libc++" in b_:
                pass
            elif "/prebuilts/runtime/mainline/i18n/sdk/libicu/" in dep:
                # Soong copies this from the vendored mainline i18n SDK; use the original.
                w(f"    {var}.root_module.addObjectFile(o.vendor.path(b, \"prebuilts/runtime/mainline/i18n/sdk/linux_glibc/x86_64/lib/libicu.so\")); // NDK-stable ICU (unversioned symbols; system ICU is versioned)")
            elif b_ in ("libicuuc-host.so", "libicui18n-host.so"):
                w(f"    {var}.root_module.linkSystemLibrary({zig_str(b_[3:-8])}, .{{}}); // prebuilt ICU -> system")
            elif b_ == "libz-host.so":
                w(f"    {var}.root_module.linkSystemLibrary(\"z\", .{{}});")
            else:
                w(f"    {var}.root_module.addObjectFile({lazy(dep)}); // prebuilt shared")
        for f in info["flags"]:
            if f.startswith("-Wl,--version-script,"):
                w(f"    {var}.setVersionScript({lazy(f.split(',', 2)[2])});")
            else:
                w(f"    // ldflag: {f}")
        for l in info["libs"]:
            w(f"    {var}.root_module.linkSystemLibrary({zig_str(l[2:])}, .{{}});")
    w("    return .{")
    for path in artifacts: w(f"        .{names[path]} = {names[path]},")
    w("    };")
    w("}")
    w("")
    w("/// Install every shared library and executable into zig-out/<dest>.")
    w("pub fn installAll(b: *std.Build, a: Artifacts, step: *std.Build.Step, dest: []const u8) void {")
    for path, (kind, info) in artifacts.items():
        if kind == "static": continue
        w(f"    step.dependOn(&b.addInstallArtifact(a.{names[path]}, .{{ .dest_dir = .{{ .override = .{{ .custom = dest }} }} }}).step);")
    w("}")
    return "\n".join(L) + "\n", {"compiles": len(compiles), "archives": len(archives), "links": len(links), "other": len(other)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--ninja", required=True)
    ap.add_argument("--out-dir", default=os.path.join(ROOT, "build", "gen"))
    ap.add_argument("targets", nargs="+")
    a = ap.parse_args()
    cmds = run_ninja(os.path.abspath(a.ninja), a.targets)
    text, stats = gen(a.name, cmds, [os.path.abspath(t) for t in a.targets])
    os.makedirs(a.out_dir, exist_ok=True)
    path = os.path.join(a.out_dir, a.name + ".zig")
    with open(path, "w") as f:
        f.write(text)
    print(f"{path}: {stats}")


if __name__ == "__main__":
    main()
