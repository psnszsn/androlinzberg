#!/usr/bin/env python3
"""Generate pinned libinput/Binder C bindings using the active Zig header ABI."""

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
    parser.add_argument("--kind", choices=("binder", "input"), required=True)
    parser.add_argument("--wrapper", type=Path, required=True)
    parser.add_argument("--flags", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
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
    includes = ["-nostdinc++", "-include", str(config)]
    for directory in (args.zig_lib / "libcxx/include", args.zig_lib / "libcxxabi/include"):
        includes += ["-isystem", str(directory)]
    for directory in search.splitlines():
        if directory.strip():
            includes += ["-isystem", directory.strip()]

    if args.kind == "binder":
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
    subprocess.run(["bindgen", str(args.wrapper), *bindgen_flags,
                    "--no-include-path-detection", "--formatter=none", "--no-layout-tests",
                    "--output", str(args.output / "bindings.rs"), "--", *includes,
                    *clang_flags, "-DANDROID_PLATFORM"], check=True)


if __name__ == "__main__":
    main()
