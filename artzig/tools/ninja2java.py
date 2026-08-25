#!/usr/bin/env python3
"""Extract the boot-classpath Java build from Soong's ninja graph.

Writes:
  build/gen/java/<module>.rsp   vendor-relative .java source lists (from Soong's javac rsp)
  build/gen/java/<module>.res   resource list: alternating "dir" / "file" lines (vendor-relative)
  src/gen/java/<module>/**      vendored generated .java sources (aconfig flags, i18n API stubs)
  build/gen/java.zig            the module table build/javalib.zig consumes

The pipeline itself (javac → system-modules → javac per module → d8 → jar) is
hand-written in build/javalib.zig; this script only captures WHAT to compile.
Run from tools/regen.sh after a Soong build of the java targets.
"""
import json, os, re, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR = os.path.join(ROOT, "vendor")
OUT = os.path.join(ROOT, "out")
I = os.path.join(OUT, "soong", ".intermediates")
GEN_SRC = os.path.join(ROOT, "src", "gen", "java")
RSP_DIR = os.path.join(ROOT, "build", "gen", "java")

# module -> (rsp path, res rsp path or None, srcjars to vendor-extract, javac -source)
MODULES = {
    "core-all": {
        "rsp": f"{I}/libcore/core-all/android_common/turbine/core-all.jar.rsp",
        "res": None,
        "srcjars": [
            f"{I}/prebuilts/runtime/mainline/i18n/sdk/i18n.module.intra.core.api.stubs.source/android_common/i18n.module.intra.core.api.stubs.source-stubs.srcjar",
            f"{I}/libcore/art-aconfig-flags-java-lib-for-core-all-only/android_common/gen/art-aconfig-flags-java-lib-for-core-all-only.srcjar",
            f"{I}/libcore/libcore-aconfig-flags-lib-for-core-all-only/android_common/gen/libcore-aconfig-flags-lib-for-core-all-only.srcjar",
            f"{I}/external/conscrypt/conscrypt.module.intra.core.api.stubs.source/android_common/everything/conscrypt.module.intra.core.api.stubs.source-stubs.srcjar",
        ],
    },
    "core-oj": {
        "rsp": f"{I}/libcore/core-oj/android_common_apex31/javac/core-oj.jar.rsp",
        "res": f"{I}/libcore/core-oj/android_common_apex31/res/core-oj.jar.rsp",
        "srcjars": [],
    },
    "core-libart": {
        "rsp": f"{I}/libcore/core-libart/android_common_apex31/javac/core-libart.jar.rsp",
        "res": f"{I}/libcore/core-libart/android_common_apex31/res/core-libart.jar.rsp",
        "srcjars": [
            f"{I}/art/build/flags/art-aconfig-flags-java-lib/android_common/gen/art-aconfig-flags-java-lib.srcjar",
            f"{I}/libcore/libcore-aconfig-flags-lib/android_common_apex31/gen/libcore-aconfig-flags-lib.srcjar",
        ],
    },
    "conscrypt": {
        "rsp": f"{I}/external/conscrypt/conscrypt/android_common/javac/conscrypt.jar.rsp",
        "res": f"{I}/external/conscrypt/conscrypt/android_common/res/conscrypt.jar.rsp",
        "srcjars": [  # NativeConstants.java is generated at build time (see javalib.zig)
            f"{I}/external/conscrypt/conscrypt-aconfig-flags-lib/android_common/gen/conscrypt-aconfig-flags-lib.srcjar",
        ],
    },
    "apache-xml": {
        "rsp": f"{I}/external/apache-xml/apache-xml/android_common_apex31/javac/apache-xml.jar.rsp",
        "res": f"{I}/external/apache-xml/apache-xml/android_common_apex31/res/apache-xml.jar.rsp",
        "srcjars": [],
    },
    "bouncycastle": {
        "rsp": f"{I}/external/bouncycastle/bouncycastle/android_common_apex31/javac/bouncycastle.jar.rsp",
        "res": f"{I}/external/bouncycastle/bouncycastle/android_common_apex31/res/bouncycastle.jar.rsp",
        "srcjars": [],
    },
    "okhttp": {
        "rsp": f"{I}/external/okhttp/okhttp/android_common_apex31/javac/okhttp.jar.rsp",
        "res": f"{I}/external/okhttp/okhttp/android_common_apex31/res/okhttp.jar.rsp",
        "srcjars": [],
    },
}
# compile order + per-module javac/d8 knobs (see the extracted Soong commands)
TABLE = [
    # name, source/target, classpath (earlier modules), dex?
    ("core-all", "21", [], False),
    ("conscrypt", "21", [], True),
    ("core-oj", "21", [], True),
    ("core-libart", "21", [], True),
    ("apache-xml", "21", [], True),
    ("bouncycastle", "21", [], True),
    ("okhttp", "1.8", ["conscrypt"], True),
]


def vend_rel(p):
    if p.startswith(VENDOR + "/"):
        return p[len(VENDOR) + 1:]
    if not p.startswith("/"):
        return p
    return None


def write_rsp(mod, srcs):
    os.makedirs(RSP_DIR, exist_ok=True)
    keep, dropped = [], []
    for s in srcs:
        r = vend_rel(s)
        (keep if r else dropped).append(r or s)
    with open(os.path.join(RSP_DIR, mod + ".rsp"), "w") as f:
        f.write("\n".join(keep) + "\n")
    return dropped


def extract_srcjars(mod, jars):
    dest = os.path.join(GEN_SRC, mod)
    if jars and os.path.isdir(dest):
        shutil.rmtree(dest)
    n = 0
    for j in jars:
        with zipfile.ZipFile(j) as z:
            for name in z.namelist():
                if name.endswith(".java"):
                    z.extract(name, dest); n += 1
    return n


def write_res(mod, res_rsp):
    if not res_rsp or not os.path.exists(res_rsp):
        return 0
    toks = open(res_rsp).read().split()
    pairs, i = [], 0
    while i < len(toks):
        if toks[i] == "-C":
            cur = toks[i + 1]; i += 2
        elif toks[i] == "-f":
            pairs.append((cur, toks[i + 1])); i += 2
        else:
            i += 1
    with open(os.path.join(RSP_DIR, mod + ".res"), "w") as f:
        for d, p in pairs:
            rd, rp = vend_rel(d), vend_rel(p)
            if rd is None or rp is None:
                print(f"  warning: {mod}: dropping non-vendor resource {p}")
                continue
            f.write(rd + "\n" + rp + "\n")
    return len(pairs)


def main():
    for mod, cfg in MODULES.items():
        srcs = open(cfg["rsp"]).read().split()
        dropped = write_rsp(mod, [s for s in srcs if s.endswith(".java")])
        ng = extract_srcjars(mod, cfg["srcjars"])
        nr = write_res(mod, cfg["res"])
        print(f"{mod}: {len(srcs)} srcs ({len(dropped)} non-vendor dropped), {ng} vendored gen srcs, {nr} resources")
        for d in dropped:
            print(f"  dropped: {d}")

    with open(os.path.join(ROOT, "build", "gen", "java.zig"), "w") as f:
        f.write("// GENERATED by tools/ninja2java.py — do not edit.\n")
        f.write("pub const Module = struct {\n")
        f.write("    name: []const u8,\n")
        f.write("    source: []const u8,\n")
        f.write("    classpath: []const []const u8,\n")
        f.write("    dex: bool,\n")
        f.write("    has_gen_src: bool,\n")
        f.write("    has_res: bool,\n")
        f.write("};\n")
        f.write("pub const modules = [_]Module{\n")
        for name, src_ver, cp, dex in TABLE:
            gen = os.path.isdir(os.path.join(GEN_SRC, name))
            res = os.path.exists(os.path.join(RSP_DIR, name + ".res"))
            cps = ", ".join(json.dumps(c) for c in cp)
            f.write(f'    .{{ .name = "{name}", .source = "{src_ver}", .classpath = &.{{{cps}}}, '
                    f".dex = {str(dex).lower()}, .has_gen_src = {str(gen).lower()}, .has_res = {str(res).lower()} }},\n")
        f.write("};\n")
    print("wrote build/gen/java.zig")


if __name__ == "__main__":
    main()
