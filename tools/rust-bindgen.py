#!/usr/bin/env python3
"""Run bindgen for a pinned Soong rust_bindgen module using the active Zig header ABI.

Kinds: `binder` and `input` read their historical flag sources; `declared` reads
the module's `bindgen_flags` (and `handle_static_inline`) from its Android.bp.
"""

import argparse
from pathlib import Path
import re
import shlex
import subprocess


def output(argv):
    return subprocess.check_output(argv, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zig", required=True)
    parser.add_argument("--zig-lib", type=Path, required=True)
    parser.add_argument("--kind", choices=("binder", "input", "declared"), required=True)
    parser.add_argument("--module", help="rust_bindgen module name for --kind declared")
    parser.add_argument("--language", choices=("c", "c++"), default="c++")
    parser.add_argument("--wrapper", type=Path, required=True)
    parser.add_argument("--flags", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stem", default="bindings", help="Soong source_stem of the generated file")
    parser.add_argument("--bindgen-arg", action="append", default=[],
                        help="a custom_bindgen builder's options, spelled as bindgen flags")
    args, clang_flags = parser.parse_known_args()
    if clang_flags[:1] == ["--"]:
        clang_flags.pop(0)
    args.output.mkdir(parents=True, exist_ok=True)

    # bindgen's libclang does not run Zig's driver. Supply the driver's libc++
    # configuration and C search roots explicitly, so it cannot mix GCC's C++
    # headers with the libc++ headers used by our native consumers.
    definitions = output([args.zig, "c++", "-std=c++23", "-fno-exceptions",
                          "-fno-rtti", "-dM", "-E", "-include", "__config",
                          "-x", "c++", "/dev/null"])
    config = args.output / "libcpp-config.h"
    config.write_text("\n".join(line for line in definitions.splitlines()
                                if re.match(r"#define _LIBCPP_(ABI|HAS|HARDENING_MODE|ASSERTION_SEMANTIC_DEFAULT)", line)) + "\n")
    probe = subprocess.run([args.zig, "cc", "-E", "-v", "-x", "c", "/dev/null"],
                           capture_output=True, text=True, check=True)
    search = probe.stderr.split("#include <...> search starts here:", 1)[1].split("End of search list.", 1)[0]
    includes = []
    if args.language == "c++":
        includes = ["-nostdinc++", "-include", str(config)]
        for directory in (args.zig_lib / "libcxx/include", args.zig_lib / "libcxxabi/include"):
            includes += ["-isystem", str(directory)]
    for directory in search.splitlines():
        if directory.strip():
            includes += ["-isystem", directory.strip()]

    static_fns = []
    if args.kind == "declared":
        bindgen_flags, static_inline = declared_flags(args.flags.read_text(), args.module)
        if static_inline:
            # Soong's handle_static_inline: the wrappers become a C source that
            # the module's static_inline_library compiles.
            static_fns = ["--experimental", "--wrap-static-fns",
                          "--wrap-static-fns-path", str(args.output / "staticfns")]
    elif args.kind == "binder":
        bindgen_flags = shlex.split(args.flags.read_text())
        bindgen_flags += ["--blocklist-type", "sockaddr", "--raw-line", "use libc::sockaddr;"]
    else:
        # This is the actual pinned Soong bindgen declaration, not a second
        # list of Android constant values or a substitute InputWrapper.hpp.
        declaration = args.flags.read_text().split('name: "libinput_bindgen"', 1)[1]
        declaration = declaration.split("static_libs:", 1)[0]
        bindgen_flags = re.findall(r'"(--allowlist-var=[^"]+)"', declaration)
        if not bindgen_flags:
            raise RuntimeError("pinned libinput bindgen allowlist was not found")
    subprocess.run(["bindgen", str(args.wrapper), *bindgen_flags, *args.bindgen_arg, *static_fns,
                    "--no-include-path-detection", "--formatter=none", "--no-layout-tests",
                    "--output", str(args.output / f"{args.stem}.rs"), "--", *includes,
                    *(["-x", "c++"] if args.language == "c++" and args.wrapper.suffix == ".h" else []),
                    *clang_flags, "-DANDROID_PLATFORM"], check=True)


def blueprint_list(text, start):
    """Parse a Blueprint string list starting at `[`; returns (items, end)."""
    items, i = [], start + 1
    while True:
        while text[i] in " \t\r\n,":
            i += 1
        if text[i] == "]":
            return items, i + 1
        if text[i] != '"':
            raise RuntimeError(f"unsupported Blueprint list element near {text[i:i + 40]!r}")
        j, value = i + 1, ""
        while text[j] != '"':
            if text[j] == "\\":
                value += text[j + 1]
                j += 2
            else:
                value += text[j]
                j += 1
        items.append(value)
        i = j + 1


def blueprint_expression(text, start):
    """A list expression: literal lists and list variables joined with `+`."""
    items, i = [], start
    while True:
        while text[i] in " \t\r\n":
            i += 1
        if text[i] == "[":
            value, i = blueprint_list(text, i)
        else:
            name = re.match(r"[A-Za-z0-9_]+", text[i:]).group(0)
            i += len(name)
            definition = re.search(rf"(?m)^{name}\s*=\s*", text)
            value, _ = blueprint_expression(text, definition.end())
        items += value
        rest = re.match(r"\s*\+", text[i:])
        if not rest:
            return items, i
        i += rest.end()


def declared_flags(text, module):
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    start = text.index(f'name: "{module}"')
    end = text.find("\n}", start)
    declaration = text[start:end]
    flags = []
    match = re.search(r"bindgen_flags:\s*", declaration)
    if match:
        flags, _ = blueprint_expression(text, start + match.end())
    return flags, re.search(r"handle_static_inline:\s*true", declaration) is not None


if __name__ == "__main__":
    main()
