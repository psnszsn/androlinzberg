#!/usr/bin/env python3
"""Audit the build-selected Binder JNI sources, headers and ELF symbol owners."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def symbols(path):
    result = subprocess.run(
        ["nm", "-D", "--defined-only", "--demangle", str(path)],
        check=True, capture_output=True, text=True,
    )
    return [line.split(maxsplit=2)[2] for line in result.stdout.splitlines()
            if len(line.split(maxsplit=2)) == 3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--source", type=Path, action="append", required=True)
    parser.add_argument("--include", type=Path, action="append", required=True)
    parser.add_argument("--platform", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    expected = {"android_util_Binder.cpp", "android_os_Parcel.cpp",
                "android_os_ServiceManager.cpp", "android_os_ServiceManagerNative.cpp"}
    errors = []
    hashes = {}
    if len(args.source) != 4 or {p.name for p in args.source} != expected:
        errors.append("production compile inputs must contain exactly the four original JNI units")
    for source in args.source:
        original = args.reference / "frameworks/base/core/jni" / source.name
        if source.read_bytes() != original.read_bytes():
            errors.append(f"JNI source differs from reference: {source}")
        hashes[source.name] = hashlib.sha256(source.read_bytes()).hexdigest()
    selected = {}
    for name in ("Binder", "IBinder", "BpBinder", "Parcel", "IPCThreadState", "ProcessState"):
        relative = Path("binder") / (name + ".h")
        actual = next((root / relative for root in args.include if (root / relative).is_file()), None)
        expected_header = args.repo / "src/native/binder/include" / relative
        if actual is None or actual.resolve() != expected_header.resolve():
            errors.append(f"incompatible facade header selection: {relative}: {actual}")
        selected[str(relative)] = str(actual)
    platform = symbols(args.platform)
    ndk = symbols(args.ndk)
    registrars = ["register_android_os_" + name for name in
                  ("Binder", "Parcel", "ServiceManager", "ServiceManagerNative")]
    for name in registrars:
        prefix = ("" if name == "register_android_os_Binder" else "android::") + name + "("
        if sum(symbol.startswith(prefix) for symbol in platform) != 1:
            errors.append(f"missing or ambiguous platform registrar: {name}")
        if any(symbol.startswith(prefix) for symbol in ndk):
            errors.append(f"duplicate NDK registrar: {name}")
    runtime = (args.repo / "src/binder/ndk.zig").read_text()
    for obsolete in ("JavaBBinderHolder", "BinderProxyNativeData", "javaForwardTransact", "mNativePtr"):
        if obsolete in runtime:
            errors.append(f"obsolete native layout/JNI path remains: {obsolete}")
    if (args.repo / "src/native/AospParcelAbi.cpp").exists():
        errors.append("obsolete AospParcelAbi.cpp remains")
    report = {"source_sha256": hashes, "selected_headers": selected,
              "platform_sha256": hashlib.sha256(args.platform.read_bytes()).hexdigest(),
              "ndk_sha256": hashlib.sha256(args.ndk.read_bytes()).hexdigest(),
              "errors": errors}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    if errors:
        raise SystemExit("\n".join(errors))
    print("Binder JNI: four unchanged production inputs, facade headers and registrar owners verified")


if __name__ == "__main__":
    main()
