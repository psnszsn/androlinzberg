#!/usr/bin/env python3
"""Audit production native compiler inputs and preprocessed client boundaries.

The command graph comes from Step.Run.argv and Clang records in linked libraries,
not a second source manifest. ART, injected compiler runtimes, Rust and hosted
loader resolution require separate inventory. No stock-client percentage is assigned.
"""
from __future__ import annotations

import argparse
import native_rust_audit
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile

CORE = "frameworks/base/core/jni/"
REQUIRED = {CORE + name for name in (
    "AndroidRuntime.cpp", "android_util_Process.cpp", "android_app_Activity.cpp", "android_app_ActivityThread.cpp", "android_ddm_DdmHandleNativeHeap.cpp",
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
    "ByteBufferStreamAdaptor.cpp", "Movie.cpp", "MovieImpl.cpp", "GIFMovie.cpp",
    "YuvToJpegEncoder.cpp", "pdf/PdfDocument.cpp",
)}
REQUIRED.update({
    'frameworks/base/native/android/hardware_buffer_jni.cpp',
    'external/skia/src/gpu/ganesh/surface/SkSurface_AndroidFactories.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/SkiaProfileRenderer.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/VkFunctorDrawable.cpp',
    'frameworks/native/opengl/libs/EGL/BlobCache.cpp',
    'frameworks/native/opengl/libs/EGL/FileBlobCache.cpp',
    'system/core/libstats/socket_lazy/libstatssocket_lazy.cpp',
})
# These units previously lost Android registrations or allocated the wrong ABI
# when compiled as host variants. Inspect the compiler's effective macros and
# complete header dependency output, not just the presence of a -D argument.
ANDROID_BRANCHES = {CORE + name for name in (
    "AndroidRuntime.cpp", "android_util_Process.cpp",
    "android_hardware_SensorManager.cpp", "android_view_Surface.cpp",
    "android_media_ImageReader.cpp", "android_media_Utils.cpp",
)} | {"frameworks/base/native/android/sensor.cpp", "frameworks/native/services/sensorservice/SensorService.cpp",
      "src/native/SensorServiceBootstrap.cpp", "src/native/SensorClientTest.cpp"}
ANDROID_BRANCHES |= {"frameworks/base/libs/hwui/jni/" + name for name in (
    "Path.cpp", "PathIterator.cpp", "HardwareBufferHelpers.cpp",
)}
HEADER_PROBES = ANDROID_BRANCHES | {CORE + name for name in (
    "AndroidRuntime.cpp", "android_util_Process.cpp", "android_app_Activity.cpp", "android_app_ActivityThread.cpp", "android_ddm_DdmHandleNativeHeap.cpp",
    "android_util_Binder.cpp", "android_os_Parcel.cpp", "android_hardware_Camera.cpp",
    "android_os_PerformanceHintManager.cpp",
)}
HEADER_PROBES.update({"frameworks/base/libs/hwui/jni/" + name for name in (
    "Movie.cpp", "YuvToJpegEncoder.cpp", "pdf/PdfDocument.cpp",
)})
REQUIRED.add("external/skia/src/pdf/SkPDFDocument.cpp")
REQUIRED.add("frameworks/av/media/ndk/NdkImageReader.cpp")
HEADER_PROBES.add("frameworks/av/media/ndk/NdkImageReader.cpp")
REQUIRED.add("frameworks/native/libs/nativedisplay/surfacetexture/EGLConsumer.cpp")
HEADER_PROBES.add("frameworks/native/libs/nativedisplay/surfacetexture/EGLConsumer.cpp")
REQUIRED.update({
    "frameworks/base/libs/hwui/apex/jni_runtime.cpp",
    "frameworks/base/libs/hwui/jni/pdf/PdfEditor.cpp",
    "frameworks/base/libs/hwui/jni/pdf/PdfUtils.cpp",
    "external/pdfium/fpdfsdk/fpdf_view.cpp",
    "external/pdfium/core/fxge/android/cfpf_skiafontmgr.cpp",
})
HEADER_PROBES.update({
    "frameworks/base/libs/hwui/apex/jni_runtime.cpp",
    "frameworks/base/libs/hwui/jni/pdf/PdfEditor.cpp",
    "external/pdfium/core/fxge/android/cfpf_skiafontmgr.cpp",
})
REQUIRED.update({
    "external/skia/src/codec/SkWuffsCodec.cpp",
    "external/wuffs-mirror-release-c/release/c/wuffs-v0.3.c",
    "packages/providers/MediaProvider/pdf/framework/libs/pdfClient/pdf_document_jni.cc",
    "packages/providers/MediaProvider/pdf/framework/libs/pdfClient/document.cc",
})
HEADER_PROBES.update({
    "external/skia/src/codec/SkWuffsCodec.cpp",
    "packages/providers/MediaProvider/pdf/framework/libs/pdfClient/pdf_document_jni.cc",
})
ANDROID_BRANCHES.add("packages/providers/MediaProvider/pdf/framework/libs/pdfClient/pdf_document_jni.cc")
REQUIRED.update({CORE + "android_os_Debug.cpp", "frameworks/av/media/libmedia/mediarecorder.cpp",
                 "system/memory/libmeminfo/procmeminfo.cpp", "system/memory/libmeminfo/sysmeminfo.cpp",
                 "system/memory/libmemtrack/memtrack.cpp"})
REQUIRED.update({"frameworks/base/media/jni/" + name for name in (
    "android_media_MediaRecorder.cpp", "android_media_MediaProfiles.cpp",
    "audioeffect/android_media_AudioEffect.cpp", "audioeffect/android_media_SourceDefaultEffect.cpp",
    "audioeffect/android_media_StreamDefaultEffect.cpp", "audioeffect/android_media_Visualizer.cpp",
    "audioeffect/Visualizer.cpp",
)})
ANDROID_BRANCHES.update({CORE + "android_os_Debug.cpp", "frameworks/base/media/jni/android_media_MediaRecorder.cpp"})
HEADER_PROBES.update({CORE + "android_os_Debug.cpp", "frameworks/base/media/jni/android_media_MediaRecorder.cpp",
                     "frameworks/base/media/jni/audioeffect/android_media_AudioEffect.cpp",
                     "system/memory/libmemtrack/memtrack.cpp"})
SKIA_ANDROID_BUFFER = "external/skia/src/gpu/ganesh/vk/AHardwareBufferVk.cpp"
SKIA_ANDROID_SOURCES = {SKIA_ANDROID_BUFFER,
    "external/skia/src/gpu/vk/VulkanInterface.cpp",
    "external/skia/src/gpu/vk/VulkanUtilsPriv.cpp",
    "external/skia/src/gpu/ganesh/vk/GrVkCaps.cpp",
}
# Restored renderer and native buffer clients must stay original in the actual build.
REQUIRED.update({
    'external/skia/src/gpu/android/AHardwareBufferUtils.cpp',
    'external/skia/src/gpu/ganesh/GrAHardwareBufferImageGenerator.cpp',
    'external/skia/src/gpu/ganesh/GrAHardwareBufferUtils.cpp',
    'external/skia/src/gpu/ganesh/gl/AHardwareBufferGL.cpp',
    'external/skia/src/image/SkImage_AndroidFactories.cpp',
    'frameworks/base/libs/hwui/AutoBackendTextureRelease.cpp',
    'frameworks/base/libs/hwui/renderthread/CacheManager.cpp',
    'frameworks/base/libs/hwui/DeferredLayerUpdater.cpp',
    'frameworks/base/libs/hwui/HardwareBitmapUploader.cpp',
    'frameworks/base/libs/hwui/ProfileDataContainer.cpp',
    'frameworks/base/libs/hwui/apex/android_bitmap.cpp',
    'frameworks/base/libs/hwui/hwui/Bitmap.cpp',
    'frameworks/base/libs/hwui/jni/Bitmap.cpp',
    'frameworks/base/libs/hwui/jni/Graphics.cpp',
    'frameworks/base/libs/hwui/jni/android_graphics_HardwareRenderer.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/GLFunctorDrawable.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/SkiaGpuPipeline.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/SkiaOpenGLPipeline.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/SkiaRecordingCanvas.cpp',
    'frameworks/base/libs/hwui/pipeline/skia/SkiaVulkanPipeline.cpp',
    'frameworks/base/libs/hwui/renderthread/CanvasContext.cpp',
    'frameworks/base/libs/hwui/renderthread/DrawFrameTask.cpp',
    'frameworks/base/libs/hwui/renderthread/EglManager.cpp',
    'frameworks/base/libs/hwui/renderthread/HintSessionWrapper.cpp',
    'frameworks/base/libs/hwui/renderthread/RenderProxy.cpp',
    'frameworks/base/libs/hwui/renderthread/RenderThread.cpp',
    'frameworks/base/libs/hwui/renderthread/VulkanManager.cpp',
    'frameworks/base/libs/hwui/renderthread/VulkanSurface.cpp',
})
HWUI_ANDROID_SOURCES = {"frameworks/base/libs/hwui/" + name for name in (
    "renderthread/EglManager.cpp", "renderthread/RenderThread.cpp",
    "renderthread/CanvasContext.cpp", "pipeline/skia/SkiaOpenGLPipeline.cpp",
    "hwui/Bitmap.cpp", "jni/Bitmap.cpp", "jni/android_graphics_HardwareRenderer.cpp",
)}
ANDROID_BRANCHES.update(HWUI_ANDROID_SOURCES)
HEADER_PROBES.update(HWUI_ANDROID_SOURCES)
SKIA_ANDROID_SOURCES.add("external/skia/src/gpu/ganesh/gl/AHardwareBufferGL.cpp")
REQUIRED.update(SKIA_ANDROID_SOURCES)
HEADER_PROBES.update(SKIA_ANDROID_SOURCES)
REQUIRED.update({"frameworks/native/libs/gui/" + name for name in (
    "GLConsumer.cpp", "SyncFeatures.cpp", "BufferQueueCore.cpp", "BufferQueueThreadState.cpp",
)})
HEADER_PROBES.update({"frameworks/native/libs/gui/GLConsumer.cpp", "frameworks/native/libs/gui/BufferQueueThreadState.cpp"})
REGISTRARS = (
    "android::register_com_android_internal_os_RuntimeInit(",
    "register_android_graphics_classes", "android::register_android_graphics_pdf_PdfEditor(",
    "android::register_android_app_Activity(", "android::register_android_app_ActivityThread(",
    "android::register_android_ddm_DdmHandleNativeHeap(",
    "register_android_os_Binder(", "android::register_android_os_Parcel(",
    "android::register_android_os_ServiceManager(", "android::register_android_os_ServiceManagerNative(",
    "register_android_hardware_SensorManager(", "register_android_hardware_Camera(",
    "android::register_android_os_VintfObject(", "android::register_android_os_VintfRuntimeInfo(",
    "android::register_android_os_PerformanceHintManager(",
    "register_android_graphics_ByteBufferStreamAdaptor(", "register_android_graphics_Movie(",
    "register_android_graphics_YuvImage(", "android::register_android_graphics_pdf_PdfDocument(",
    "register_android_media_MediaCodec(", "register_android_media_MediaCodecList(",
)


def registrar_matches(symbol: str, registrar: str) -> bool:
    # Demangled C++ entries include their argument list; C exports are exact names.
    return symbol.startswith(registrar) if registrar.endswith("(") else symbol == registrar


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
        if "-o" in args:
            output = args.index("-o")
            del args[output:output + 2]
        if "-c" in args:
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
    if source == "frameworks/native/libs/gui/BufferQueueThreadState.cpp":
        if "NO_BINDER" in probe["macros"]:
            errors.append("BufferQueue lost the AIDL caller identity branch")
        headers = {str(path) for path in probe["headers"]}
        if str(repo / "src/native/binder/include/binder/IPCThreadState.h") not in headers:
            errors.append("BufferQueue caller identity bypasses the authenticated Binder transport")
    if source == CORE + "android_os_Debug.cpp":
        expected = {"mallinfo": "mallinfo2", "mallinfo2(...)": "azk_android_mallinfo(__VA_ARGS__)",
                    "mallopt": "azk_android_mallopt", "LoadKernelConfigs": "LoadDebugStackConfigs"}
        if any(probe["macros"].get(key) != value for key, value in expected.items()):
            errors.append("Debug client lost the host allocator ABI or private kernel-query boundary")
    if source == "external/skia/src/codec/SkWuffsCodec.cpp":
        macros = probe["macros"]
        if not {"SK_CODEC_DECODES_GIF", "SK_HAS_WUFFS_LIBRARY"}.issubset(macros) or "WUFFS_IMPLEMENTATION" in macros:
            errors.append("Skia GIF decoder disabled or mixed with Wuffs implementation flags")
    if source in SKIA_ANDROID_SOURCES:
        macros = probe["macros"]
        if (not {"SK_BUILD_FOR_ANDROID", "SK_VULKAN"}.issubset(macros)
                or "SK_BUILD_FOR_UNIX" in macros
                or macros.get("__ANDROID_API__") != "36"):
            errors.append(f"Skia Android hardware-buffer implementation disabled or mixed with Unix ABI: {source}")
    if source.startswith("external/pdfium/") and "OS_ANDROID" not in probe["macros"]:
        errors.append(f"PDFium Android implementation branch disabled: {source}")
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
    symbols = subprocess.check_output(["nm", "--defined-only", "--demangle", str(path)], text=True)
    return {"path": str(path), "sha256": digest(path),
            "needed": re.findall(r"\(NEEDED\).*\[(.*?)\]", dynamic),
            "search_paths": re.findall(r"\((?:RUNPATH|RPATH)\).*\[(.*?)\]", dynamic),
            "symbols": [line.split(maxsplit=2)[2] for line in symbols.splitlines()
                        if len(line.split(maxsplit=2)) == 3]}


def command_records(data: bytes) -> list[tuple[str, list[str]]]:
    if data.startswith(b'!<arch>\n'):
        result = []
        offset = 8
        names = b''
        while offset < len(data):
            header = data[offset:offset + 60]
            if len(header) != 60 or header[58:] != b'`\n':
                raise ValueError('invalid ar member header')
            size = int(header[48:58])
            start = offset + 60
            member = data[start:start + size]
            if len(member) != size:
                raise ValueError('truncated ar member')
            name = header[:16].decode().strip()
            if name == '//':
                names = member
            elif name not in ('/', '/SYM64/'):
                if name.startswith('#1/'):
                    length = int(name[3:])
                    name, member = member[:length].decode().rstrip('\0'), member[length:]
                elif name.startswith('/'):
                    name = names[int(name[1:]):].split(b'/\n', 1)[0].decode()
                else:
                    name = name.rstrip('/')
                for _, command in command_records(member):
                    result.append((name, command))
            offset = start + size + size % 2
        return result
    if data.startswith((b'BC\xc0\xde', b'\xde\xc0\x17\x0b', b'!<thin>')):
        raise ValueError('unsupported bitcode or thin archive: cannot verify compiler records')
    if not data.startswith(b'\x7fELF'):
        raise ValueError('native input is neither ELF nor ordinary ar')
    if data[:6] != b'\x7fELF\x02\x01' or len(data) < 64:
        raise ValueError('unsupported or truncated ELF header')
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    offset, entrysize, count, names = header[6], header[11], header[12], header[13]
    if entrysize != 64 or not count or names >= count or offset + count * entrysize > len(data):
        raise ValueError('invalid ELF section table')
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, offset + i * entrysize) for i in range(count)]
    def content(section):
        start, size = section[4], section[5]
        if start + size > len(data):
            raise ValueError('truncated ELF section')
        return data[start:start + size]
    strings = content(sections[names])
    records = []
    for section in sections:
        index = section[0]
        if index >= len(strings) or b'\0' not in strings[index:]:
            raise ValueError('invalid ELF section name')
        if strings[index:].split(b'\0', 1)[0] == b'.GCC.command.line':
            raw = content(section)
            if raw and not raw.endswith(b'\0'):
                raise ValueError('unterminated compiler record')
            for value in raw.split(b'\0'):
                if not value:
                    continue
                # LLVM escapes whitespace/backslashes, but leaves quotes literal.
                lexer = shlex.shlex(value.decode(), posix=True)
                lexer.whitespace_split = True
                lexer.quotes = ''
                lexer.commenters = ''
                records.append(('', list(lexer)))
    return records


def replay_record(command: list[str], compiler: Path) -> tuple[Path, list[str]]:
    if len(command) < 4 or Path(command[0]).resolve() != compiler.resolve():
        raise ValueError('untrusted or malformed recorded compiler command')
    source_index = 1
    if command[1] == "-x":
        if len(command) < 6 or command[2] not in ("c", "c++", "assembler", "assembler-with-cpp"):
            raise ValueError("unsupported recorded source language")
        source_index = 3
    source = Path(command[source_index]).resolve(strict=True)
    if source.suffix not in ('.c', '.cc', '.cpp', '.cxx', '.S', '.s') or '-c' not in command:
        raise ValueError('record has no native compilation source')
    args = [str(compiler), 'clang']
    index = 1
    while index < len(command):
        value = command[index]
        if value in ('-o', '-MF', '-MT', '-MQ'):
            if index + 1 >= len(command):
                raise ValueError('missing recorded compiler option argument')
            index += 2
        elif value in ('-c', '-MD', '-MMD', '-MV'):
            index += 1
        else:
            args.append(value)
            index += 1
    return source, args


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("reference", "package", "repo", "commands", "platform", "ndk", "pdfclient", "compiler", "output"):
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
    model = json.loads(args.commands.read_text())
    commands = decode_commands(model, args.path)
    sources, selected = [], {}
    for command in commands[:model["native_count"]]:
        source, obj = source_and_object(command)
        record = classify(source, package, reference, repo)
        record.update(command=command, object={"path": str(obj), "sha256": digest(obj)})
        sources.append(record)
        if "source" in record:
            name = record["source"]
            if name in selected:
                errors.append(f"source compiled more than once in audited native inputs: {name}")
            selected[name] = record
            if record["kind"] == "missing_reference" or record["kind"] == "modified" and name not in allowed:
                errors.append(f"unlisted native source adaptation: {name}")
    platform_commands = {tuple(command) for _, command in command_records(args.platform.read_bytes())}
    libraries, linked_sources = [], []
    for library in model["libraries"]:
        artifact = args.path[library["artifact"]].resolve(strict=True)
        declared = {args.path[index].resolve(strict=True) for index in library["sources"]}
        actual = set()
        library_record = {"path": str(artifact), "sha256": digest(artifact),
                          "declared_sources": [str(path) for path in sorted(declared)], "records": []}
        for member, command in command_records(artifact.read_bytes()):
            source, replay = replay_record(command, args.compiler)
            actual.add(source)
            record = classify(source, package, reference, repo)
            record.update(command=replay, recorded_command=command, library=str(artifact), member=member,
                          object_in_platform=tuple(command) in platform_commands)
            linked_sources.append(record)
            library_record["records"].append(len(linked_sources) - 1)
            if "source" in record:
                name = record["source"]
                selected.setdefault(name, record)
                if record["kind"] == "missing_reference" or record["kind"] == "modified" and name not in allowed:
                    errors.append(f"unlisted linked native adaptation: {name}")
        # Assembly and Zig compiler/runtime support are retained as explicit
        # inventory; every declared C/C++ translation unit must have a record.
        missing = {path for path in declared - actual if path.suffix in (".c", ".cc", ".cpp", ".cxx")}
        if missing:
            errors.append(f"missing compiler records in {artifact}: {sorted(map(str, missing))}")
        libraries.append(library_record)
    for name in sorted(REQUIRED):
        if name not in selected or selected[name]["kind"] != "unchanged":
            errors.append(f"restored client missing or changed: {name}")
    probes = [record for record in sources + linked_sources if record.get("source") in HEADER_PROBES]
    headers = {}
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = pool.map(preprocess, [record["command"] for record in probes])
        for record, probe in zip(probes, results):
            errors.extend(boundary_errors(record["source"], probe, repo))
            record["effective_macros"] = probe["macros"]
            record["consumed_headers"] = [str(path) for path in probe["headers"]]
            for path in probe["headers"]:
                if str(path) not in headers:
                    headers[str(path)] = classify(path, package, reference, repo)
    platform, ndk, pdfclient = elf(args.platform), elf(args.ndk), elf(args.pdfclient)
    exports = subprocess.check_output(["nm", "-D", "--defined-only", str(args.pdfclient)], text=True)
    exports = [line.split()[-1].split("@")[0] for line in exports.splitlines() if line.split()]
    if "JNI_OnLoad" not in exports or "Java_android_graphics_pdf_PdfDocumentProxy_createFromFd" not in exports:
        errors.append("original PDF client JNI entry missing")
    if any(not name.startswith(("JNI_", "Java_", "VERS_")) for name in exports):
        errors.append("PDF client leaks native globals outside original jni.lds boundary")
    pdfclient["exports"] = exports
    del pdfclient["symbols"]
    owners = {}
    for registrar in REGISTRARS:
        matches = [symbol for symbol in platform["symbols"] if registrar_matches(symbol, registrar)]
        owners[registrar] = matches
        if len(matches) != 1 or any(registrar_matches(symbol, registrar) for symbol in ndk["symbols"]):
            errors.append(f"missing, ambiguous or duplicate JNI registrar owner: {registrar}")
    for name in platform["needed"]:
        if name.startswith(("libavcodec.", "libavformat.", "libavutil.", "libswscale.", "libva.")):
            errors.append(f"media decode backend linked into client platform: {name}")
    rust, rust_errors = native_rust_audit.audit(model, commands, args.path, package, reference, repo, platform["symbols"], classify, digest)
    errors.extend(rust_errors)
    del platform["symbols"], ndk["symbols"]
    report = {"passed": not errors, "scope": "Native island/Skia Step.Run commands and actual ELF compiler records for declared C/C++ inputs in linked Step.Compile libraries; all compilation contexts of selected boundary header/macro probes; platform/NDK/PDF ELF owners. Pinned Rust compiler commands, effective cfg, consumed inputs, sysroot and bridge ownership are included. ART, injected C/C++ compiler runtimes and runtime loader resolution require separate inventory.",
              "reference": reference_state, "source_counts": dict(Counter(record["kind"] for record in sources)),
              "required_original_clients": len(REQUIRED), "header_probe_count": len(probes),
              "sources": sources, "rust": rust, "linked_libraries": libraries, "linked_sources": linked_sources,
              "linked_source_counts": dict(Counter(record["kind"] for record in linked_sources)), "headers": list(headers.values()), "platform": platform, "ndk": ndk, "pdfclient": pdfclient,
              "registrars": owners, "errors": errors}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"native island: {len(sources)} direct inputs and {len(linked_sources)} records across {len(libraries)} linked libraries, {len(REQUIRED)} restored source guards, {len(probes)} preprocessed boundaries, {len(errors)} errors", flush=True)
    for error in errors:
        print(error, flush=True)
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
