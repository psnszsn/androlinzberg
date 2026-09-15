#!/usr/bin/env python3
"""Audit actual native-island compiler inputs and preprocessed client boundaries.

The command graph comes from Step.Run.argv, not a second source manifest. Linked
archives, ART and the hosted loader require separate provenance checks; this
report deliberately does not assign a stock-client percentage.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

CORE = "frameworks/base/core/jni/"
REQUIRED = {CORE + name for name in (
    "android_util_Binder.cpp", "android_os_Parcel.cpp", "android_os_ServiceManager.cpp",
    "android_os_ServiceManagerNative.cpp", "android_os_SystemProperties.cpp",
    "android_os_SystemClock.cpp", "android_os_SharedMemory.cpp", "android_os_MemoryFile.cpp",
    "android_util_MemoryIntArray.cpp", "com_android_internal_os_ApplicationSharedMemory.cpp",
    "android_os_VintfObject.cpp", "android_os_VintfRuntimeInfo.cpp",
    "android_os_PerformanceHintManager.cpp", "android_hardware_SensorManager.cpp",
    "android_hardware_Camera.cpp", "android_hardware_camera2_CameraMetadata.cpp",
    "android_hardware_camera2_utils_SurfaceUtils.cpp", "android_view_Surface.cpp",
    "android_media_ImageReader.cpp", "android_media_Utils.cpp",
)} | {"frameworks/base/native/android/" + name for name in ("sensor.cpp", "performance_hint.cpp")} | {
    "frameworks/base/media/jni/" + name for name in (
        "android_media_MediaCodec.cpp", "android_media_MediaCodecList.cpp",
        "android_media_MediaMetadataRetriever.cpp", "android_media_MediaCrypto.cpp", "android_media_MediaDrm.cpp",
    )
} | {"frameworks/native/libs/sensor/" + name for name in (
    "BitTube.cpp", "ISensorEventConnection.cpp", "ISensorServer.cpp", "Sensor.cpp",
    "SensorEventQueue.cpp", "SensorManager.cpp",
)} | {"external/sqlite/android/" + name for name in (
    "sqlite3_android.cpp", "PhoneNumberUtils.cpp", "OldPhoneNumberUtils.cpp",
)} | {"frameworks/base/libs/hwui/jni/" + name for name in (
    "Path.cpp", "PathIterator.cpp", "HardwareBufferHelpers.cpp",
)}
# These units previously lost Android registrations or allocated the wrong ABI
# when compiled as host variants. Inspect the compiler's effective macros and
# complete header dependency output, not just the presence of a -D argument.
ANDROID_BRANCHES = {CORE + name for name in (
    "android_hardware_SensorManager.cpp", "android_view_Surface.cpp",
    "android_media_ImageReader.cpp", "android_media_Utils.cpp",
)} | {"frameworks/base/native/android/sensor.cpp", "frameworks/native/services/sensorservice/SensorService.cpp",
      "src/native/SensorServiceBootstrap.cpp", "src/native/SensorClientTest.cpp"}
ANDROID_BRANCHES |= {"frameworks/base/libs/hwui/jni/" + name for name in (
    "Path.cpp", "PathIterator.cpp", "HardwareBufferHelpers.cpp",
)}
HEADER_PROBES = ANDROID_BRANCHES | {CORE + name for name in (
    "android_util_Binder.cpp", "android_os_Parcel.cpp", "android_hardware_Camera.cpp",
    "android_os_PerformanceHintManager.cpp",
)}
REGISTRARS = (
    "register_android_os_Binder(", "android::register_android_os_Parcel(",
    "android::register_android_os_ServiceManager(", "android::register_android_os_ServiceManagerNative(",
    "register_android_hardware_SensorManager(", "register_android_hardware_Camera(",
    "android::register_android_os_VintfObject(", "android::register_android_os_VintfRuntimeInfo(",
    "android::register_android_os_PerformanceHintManager(",
    "register_android_media_MediaCodec(", "register_android_media_MediaCodecList(",
)


def digest(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def decode_commands(model: dict, paths: list[Path]) -> list[list[str]]:
    tokens = [token["text"] if token.get("text") is not None else
              token.get("prefix", "") + str(paths[token["path"]]) + token.get("suffix", "")
              for token in model["tokens"]]
    return [[tokens[index] for index in command] for command in model["commands"]]


def source_and_object(command: list[str]) -> tuple[Path, Path]:
    if len(command) < 5 or command[1] not in ("cc", "c++") or "-c" not in command:
        raise ValueError("not a native island compiler command")
    output = command.index("-o")
    source = Path(command[output - 1])
    if source.suffix not in (".cpp", ".cc", ".c", ".cxx"):
        raise ValueError(f"unrecognized source argument: {source}")
    return source.resolve(strict=True), Path(command[output + 1]).resolve(strict=True)


def classify(path: Path, package: Path, reference: Path, repo: Path) -> dict:
    result = {"path": str(path), "sha256": digest(path)}
    if path.is_relative_to(package):
        relative = path.relative_to(package)
        original = reference / relative
        result["source"] = str(relative)
        result["kind"] = ("unchanged" if original.is_file() and digest(original) == result["sha256"]
                          else "modified" if original.is_file() else "missing_reference")
    elif path.is_relative_to(repo / ".zig-cache"):
        result["kind"] = "generated"
    elif path.is_relative_to(repo):
        result["kind"] = "owned"
        result["source"] = str(path.relative_to(repo))
    else:
        # System and other extracted dependency headers are intentionally not
        # labelled as original AOSP by location alone.
        result["kind"] = "external"
    return result


def dependency_paths(text: str) -> list[Path]:
    text = text.replace("\\\n", "")
    _, separator, body = text.partition(":")
    if not separator:
        raise ValueError("compiler dependency output has no target")
    return sorted({Path(value).resolve(strict=True) for value in shlex.split(body)})


def preprocess(command: list[str]) -> dict:
    with tempfile.TemporaryDirectory(prefix="azk-native-provenance-") as directory:
        depfile = Path(directory) / "headers.d"
        args = list(command)
        output = args.index("-o")
        del args[output:output + 2]
        args.remove("-c")
        args += ["-E", "-dM", "-MD", "-MF", str(depfile), "-MT", "audit"]
        result = subprocess.run(args, text=True, capture_output=True, timeout=180)
        if result.returncode:
            raise ValueError("native header probe failed: " + result.stderr[-6000:])
        macros = {}
        for line in result.stdout.splitlines():
            match = re.match(r"#define (\S+)(?: (.*))?$", line)
            if match:
                macros[match[1]] = match[2] or ""
        return {"macros": macros, "headers": dependency_paths(depfile.read_text())}


def boundary_errors(source: str, probe: dict, repo: Path) -> list[str]:
    errors = []
    if source in ANDROID_BRANCHES and "__ANDROID__" not in probe["macros"]:
        errors.append(f"Android implementation branch disabled: {source}")
    if source in (CORE + "android_util_Binder.cpp", CORE + "android_os_Parcel.cpp"):
        headers = {str(path) for path in probe["headers"]}
        for name in ("Binder", "Parcel", "IPCThreadState"):
            expected = repo / "src/native/binder/include/binder" / (name + ".h")
            if str(expected) not in headers:
                errors.append(f"required Binder facade header not consumed: {source}: {expected}")
    return errors


def elf(path: Path) -> dict:
    dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
    symbols = subprocess.check_output(["nm", "-D", "--defined-only", "--demangle", str(path)], text=True)
    return {"path": str(path), "sha256": digest(path),
            "needed": re.findall(r"\(NEEDED\).*\[(.*?)\]", dynamic),
            "search_paths": re.findall(r"\((?:RUNPATH|RPATH)\).*\[(.*?)\]", dynamic),
            "symbols": [line.split(maxsplit=2)[2] for line in symbols.splitlines()
                        if len(line.split(maxsplit=2)) == 3]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("reference", "package", "repo", "commands", "platform", "ndk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--path", type=Path, action="append", required=True)
    args = parser.parse_args()
    reference, package, repo = (getattr(args, name).resolve(strict=True) for name in ("reference", "package", "repo"))
    if reference == package:
        raise ValueError("reference must be independent of the compiled package")
    policy = json.loads((repo / "docs/native-client-audit.json").read_text())
    allowed = set(policy["packages"]["androlinzberg-aosp-android-16.0.0_r1.tar.gz"]["modified"])
    errors = []
    reference_state = {}
    for name, pin in policy["reference_projects"].items():
        directory = reference / name
        head = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
        dirty = subprocess.check_output(["git", "-C", str(directory), "status", "--porcelain", "--untracked-files=no"], text=True)
        reference_state[name] = {"revision": head, "tracked_clean": not dirty}
        if head != pin["revision"] or dirty:
            errors.append(f"reference project is dirty or not at its pin: {name}")
    commands = decode_commands(json.loads(args.commands.read_text()), args.path)
    sources, selected = [], {}
    for command in commands:
        source, obj = source_and_object(command)
        record = classify(source, package, reference, repo)
        record.update(command=command, object={"path": str(obj), "sha256": digest(obj)})
        sources.append(record)
        if "source" in record:
            name = record["source"]
            if name in selected:
                errors.append(f"source compiled more than once in native island: {name}")
            selected[name] = record
            if record["kind"] == "missing_reference" or record["kind"] == "modified" and name not in allowed:
                errors.append(f"unlisted native source adaptation: {name}")
    for name in sorted(REQUIRED):
        if name not in selected or selected[name]["kind"] != "unchanged":
            errors.append(f"restored client missing or changed: {name}")
    probes = sorted(HEADER_PROBES & selected.keys())
    headers = {}
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = pool.map(preprocess, [selected[name]["command"] for name in probes])
        for name, probe in zip(probes, results):
            errors.extend(boundary_errors(name, probe, repo))
            selected[name]["effective_macros"] = probe["macros"]
            selected[name]["consumed_headers"] = [str(path) for path in probe["headers"]]
            for path in probe["headers"]:
                if str(path) not in headers:
                    headers[str(path)] = classify(path, package, reference, repo)
    platform, ndk = elf(args.platform), elf(args.ndk)
    owners = {}
    for registrar in REGISTRARS:
        matches = [symbol for symbol in platform["symbols"] if symbol.startswith(registrar)]
        owners[registrar] = matches
        if len(matches) != 1 or any(symbol.startswith(registrar) for symbol in ndk["symbols"]):
            errors.append(f"missing, ambiguous or duplicate JNI registrar owner: {registrar}")
    for name in platform["needed"]:
        if name.startswith(("libavcodec.", "libavformat.", "libavutil.", "libswscale.", "libva.")):
            errors.append(f"media decode backend linked into client platform: {name}")
    del platform["symbols"], ndk["symbols"]
    report = {"passed": not errors, "scope": "Native island Step.Run translation units; effective macros and consumed headers for selected restored boundaries; platform/NDK ELF owners. Static linked libraries, ART and runtime loader resolution are outside this check.",
              "reference": reference_state, "source_counts": dict(Counter(record["kind"] for record in sources)),
              "required_original_clients": len(REQUIRED), "header_probe_count": len(probes),
              "sources": sources, "headers": list(headers.values()), "platform": platform, "ndk": ndk,
              "registrars": owners, "errors": errors}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"native island: {len(sources)} compiled inputs, {len(REQUIRED)} restored source guards, {len(probes)} preprocessed boundaries, {len(errors)} errors", flush=True)
    for error in errors:
        print(error, flush=True)
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
