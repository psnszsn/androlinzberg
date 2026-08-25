#!/usr/bin/env python3
"""Compute the minimal set of vendor/ + extra/ paths the Zig build actually uses.

Sources:
  build/gen/art.zig        addCSourceFiles .root/.files (source files) and every
                           Inc .rel (include directories, copied whole — headers
                           pull other headers) and prebuilt addObjectFile paths
  build/gen/java/*.rsp/.res per-module .java and resource lists
  build/*.zig              hand-written o.vendor.path(...) / b.path("vendor|extra/...")
                           / bare "prebuilts/..." / "external/..." string literals

Writes build/gen/vendor-manifest.txt (one path per line, vendor/ or extra/
relative, files and directories). tools/make-dist-tarball.sh copies exactly
these into a pruned source tree a consumer can `zig build dist` against without
the 36 GB checkout (no prebuilts/clang, no .git, no unused externals).
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = set()   # exact files (vendor/extra-relative, with the vendor/ or extra/ prefix)
DIRS = set()    # whole directories to copy


def add_file(root, rel):
    FILES.add(os.path.normpath(os.path.join(root, rel)))


def add_dir(root, rel):
    DIRS.add(os.path.normpath(os.path.join(root, rel)))


def scan_generated_art():
    p = os.path.join(ROOT, "build", "gen", "art.zig")
    s = open(p).read()
    # addCSourceFiles blocks: .root = o.vendor|o.out|genDir(...) , .files = &.{ "a", "b" }
    for m in re.finditer(r"\.root = (o\.vendor|o\.out|genDir\([^)]*\)),\s*\.files = &\.\{([^}]*)\}", s):
        root_expr, files = m.group(1), m.group(2)
        if root_expr != "o.vendor":
            continue  # generated sources come from genrules, not the source tarball
        for f in re.findall(r'"([^"]+)"', files):
            add_file("vendor", f)
    # Inc records: .root = .vendor, .rel = "path"
    for m in re.finditer(r"\.root = \.vendor, \.rel = \"([^\"]+)\"", s):
        add_dir("vendor", m.group(1))
    # prebuilt object/shared files: o.vendor.path(b, "prebuilts/...")
    for m in re.finditer(r'o\.vendor\.path\(b, "([^"]+)"\)', s):
        add_file("vendor", m.group(1))


def scan_java():
    d = os.path.join(ROOT, "build", "gen", "java")
    if not os.path.isdir(d):
        return
    for name in os.listdir(d):
        path = os.path.join(d, name)
        if name.endswith(".rsp"):
            for line in open(path):
                line = line.strip()
                if line:
                    add_file("vendor", line)
        elif name.endswith(".res"):
            toks = open(path).read().split("\n")
            # alternating base-dir / file
            it = iter([t for t in toks if t])
            for base in it:
                f = next(it, None)
                if f:
                    add_file("vendor", f)


def scan_handwritten():
    for name in ("androidfw.zig", "bionic.zig", "javalib.zig", "dist.zig", "genrules.zig"):
        p = os.path.join(ROOT, "build", name)
        if not os.path.exists(p):
            continue
        s = open(p).read()
        # o.vendor.path(b, "X") / o.src.path(b, "X") / .cwd_relative with vendor
        for m in re.finditer(r'(?:o\.vendor|o\.src|b\.path\(")?(?:vendor|extra)?/?"?[^"]*', ""):
            pass
        for m in re.finditer(r'o\.vendor\.path\(b, "([^"]+)"\)', s):
            add_file("vendor", m.group(1))
        for m in re.finditer(r'b\.path\("(vendor|extra)/([^"]+)"\)', s):
            add_file(m.group(1), m.group(2))
        # bionic src root is extra/bionic_translation; its addCSourceFiles use o.src
        for m in re.finditer(r'o\.src\.path\(b, "([^"]+)"\)', s):
            add_dir("extra/bionic_translation", m.group(1))
        for m in re.finditer(r'\.files = &\(?(?:wrapper_srcs \+\+ )?\[_\]\[\]const u8\{([^}]*)\}', s):
            for f in re.findall(r'"([^"]+\.(?:c|cc|cpp))"', m.group(1)):
                add_file("extra/bionic_translation", f)


import glob
def add_source_siblings():
    # compiled TUs #include sibling .inl/.inc/_impl.c files not listed anywhere
    for f in list(FILES):
        if not f.endswith((".c", ".cc", ".cpp")): continue
        d = os.path.join(ROOT, os.path.dirname(f))
        for pat in ("*.inl", "*.inc", "*_impl.c", "*-impl.c"):
            for g in glob.glob(os.path.join(d, pat)):
                FILES.add(os.path.relpath(g, ROOT))

def main():
    scan_generated_art()
    scan_java()
    scan_handwritten()
    add_source_siblings()
    # bionic needs its whole source tree (headers); androidfw/frameworks handled by dirs below
    for d in ("extra/bionic_translation", "extra/frameworks-base/libs/androidfw",
              "extra/frameworks-native/include", "extra/incremental_delivery/incfs/util", "extra/libpng",
              # genrule scripts + inputs (build/genrules.zig): .py, mterp .S, asm_defines .def
              "vendor/art/tools/cpp-define-generator", "vendor/art/runtime/interpreter/mterp"):
        if os.path.isdir(os.path.join(ROOT, d)):
            DIRS.add(d)
    for f in ("vendor/art/tools/generate_operator_out.py",):
        if os.path.exists(os.path.join(ROOT, f)):
            FILES.add(f)
    # always-needed roots that scripts reference at runtime
    # System JDK is a build-time requirement (not shipped). Prebuilts are x86_64-only.
    always = [
        "vendor/prebuilts/r8/r8.jar",
        "vendor/build/soong/scripts/jars-to-module-info-java.sh",
        "vendor/prebuilts/runtime/mainline/i18n/sdk/java/core-icu4j.jar",
        "vendor/prebuilts/runtime/mainline/i18n/sdk/linux_glibc/x86_64",
        "vendor/prebuilts/runtime/mainline/i18n/test-exports/linux_glibc/x86_64",
        "vendor/prebuilts/runtime/mainline/i18n/apex/com.android.i18n-x86_64.apex",
        "vendor/prebuilts/runtime/mainline/tzdata/apex/com.android.tzdata-x86_64.apex",
        "vendor/prebuilts/clang/host/linux-x86/clang-r547379/lib/x86_64-unknown-linux-gnu/libc++.so",
    ]
    for f in always:
        ap = os.path.join(ROOT, f)
        if os.path.isdir(ap):
            DIRS.add(f)
        elif os.path.exists(ap):
            FILES.add(f)

    for coll in (FILES, DIRS):
        for x in list(coll):
            if not (x.startswith("vendor/") or x.startswith("extra/")):
                coll.discard(x)
    HDR_EXT = (".h", ".hpp", ".hh", ".inc", ".inl", ".def", ".S", ".td", ".modulemap", ".c", ".cc", ".cpp")
    # extra/* and vendor/prebuilts/* are copied whole (sources / prebuilt libs);
    # other vendor/ dirs are include roots -> headers only.
    GENRULE_DIRS = ("vendor/art/tools/cpp-define-generator", "vendor/art/runtime/interpreter/mterp")
    def full(d): return d.startswith("extra/") or d.startswith("vendor/prebuilts/") or d in GENRULE_DIRS
    full_dirs = sorted(d for d in DIRS if full(d))
    inc_dirs = sorted(d for d in DIRS if not full(d))
    dirs = sorted(DIRS)
    kept_files = sorted(f for f in FILES if not (
        any(f == d or f.startswith(d + "/") for d in full_dirs) or
        (f.endswith(HDR_EXT) and any(f.startswith(d + "/") for d in inc_dirs))))
    out = os.path.join(ROOT, "build", "gen", "vendor-manifest.txt")
    with open(out, "w") as fh:
        fh.write("# format: 'F <path>' exact file, 'D <dir>' whole dir, 'H <dir>' headers only\n")
        for x in full_dirs: fh.write("D " + x + "\n")
        for x in inc_dirs: fh.write("H " + x + "\n")
        for x in kept_files: fh.write("F " + x + "\n")

    # size report
    def dirsize(ap, hdr_only):
        t = 0
        for r, _, ns in os.walk(ap):
            if ".git" in r.split(os.sep): continue
            for n in ns:
                if hdr_only and not n.endswith(HDR_EXT): continue
                try: t += os.path.getsize(os.path.join(r, n))
                except OSError: pass
        return t
    total = 0
    for x in full_dirs: total += dirsize(os.path.join(ROOT, x), False)
    for x in inc_dirs: total += dirsize(os.path.join(ROOT, x), True)
    for x in kept_files:
        ap = os.path.join(ROOT, x)
        if os.path.exists(ap): total += os.path.getsize(ap)
    print(f"{len(full_dirs)} full dirs + {len(inc_dirs)} header dirs + {len(kept_files)} files -> {total/1e6:.0f} MB (vs 36 GB full tree)")
    print(f"wrote {os.path.relpath(out, ROOT)}")


if __name__ == "__main__":
    main()
