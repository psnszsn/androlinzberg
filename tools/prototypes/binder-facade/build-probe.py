#!/usr/bin/env python3
from pathlib import Path
import argparse
import subprocess
import json
import shlex
import sys
import re
import hashlib

p = argparse.ArgumentParser()
p.add_argument('output', type=Path)
p.add_argument('aosp', type=Path)
a = p.parse_args()
r = Path(__file__).resolve().parent
out = a.output.resolve()
root = a.aosp.resolve()
out.mkdir(parents=True, exist_ok=True)
# Run the pinned AIDL compiler through this environment's ELF loader on NixOS.
elf = subprocess.check_output(['readelf', '-l', str(Path(sys.executable).resolve())], text=True)
loader = re.search(r'Requesting program interpreter: (.*?)\]', elf).group(1)
generated = out / 'generated'
generated.mkdir(exist_ok=True)
aidl = root / 'prebuilts/build-tools/linux-x86/bin/aidl'
command = [
    loader,
    str(aidl),
    '--lang=cpp',
    '-I' + str(root / 'frameworks/native/libs/gui/aidl'),
    '-h' + str(generated),
    '-o' + str(generated),
    str(root / 'frameworks/native/libs/gui/aidl/android/gui/IFpsListener.aidl'),
]
subprocess.run(command, check=True)
incs = [r / 'include', out / 'generated'] + [
    root / x
    for x in [
        'frameworks/native/libs/binder/include',
        'frameworks/native/libs/binder/ndk/include_ndk',
        'frameworks/native/libs/binder/ndk/include_platform',
        'system/core/libutils/binder/include',
        'system/core/libutils/include',
        'system/libbase/include',
        'system/core/libcutils/include',
        'system/core/libsystem/include',
        'frameworks/native/libs/ui/include',
        'system/core/libsync/include',
        'frameworks/native/libs/nativewindow/include',
        'frameworks/native/libs/nativebase/include',
        'hardware/libhardware/include_all',
        'system/core/libgrallocusage/include',
        'frameworks/native/libs/math/include',
        'frameworks/native/libs/arect/include',
    ]
]
flags = [
    '-std=c++23',
    '-O0',
    '-g',
    '-pthread',
    '-DANDROID_UTILS_CALLSTACK_ENABLED=0',
    '-include',
    str(r / 'compat.h'),
]
for d in incs:
    flags += ['-I', str(d)]
sources = [r / 'facade.cpp', r / 'probe.cpp', out / 'generated/android/gui/IFpsListener.cpp'] + [
    root / x
    for x in [
        'frameworks/native/libs/binder/IInterface.cpp',
        'system/core/libutils/binder/RefBase.cpp',
        'system/core/libutils/binder/StrongPointer.cpp',
        'system/core/libutils/binder/String16.cpp',
        'system/core/libutils/binder/String8.cpp',
        'system/core/libutils/binder/SharedBuffer.cpp',
        'system/core/libutils/binder/Unicode.cpp',
        'frameworks/native/libs/ui/Fence.cpp',
        'frameworks/native/libs/ui/GraphicBuffer.cpp',
        'frameworks/native/libs/ui/PixelFormat.cpp',
        'system/core/libgrallocusage/GrallocUsageConversion.cpp',
        'system/core/libcutils/native_handle.cpp',
    ]
]
cc = [
    'cc',
    '-std=gnu11',
    '-D__INTRODUCED_IN(x)=',
    '-I',
    str(root / 'system/core/libsync/include'),
    '-I',
    str(root / 'frameworks/native/libs/nativewindow/include'),
    '-c',
    str(root / 'system/core/libsync/sync.c'),
    '-o',
    str(out / 'sync.o'),
]
drm_flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'libdrm'], text=True))
drm_libdir = subprocess.check_output(['pkg-config', '--variable=libdir', 'libdrm'], text=True).strip()
cmd = [
    'c++',
    *flags,
    *map(str, sources),
    str(out / 'sync.o'),
    '-L',
    str(out / 'runtime/zig-out/lib'),
    '-Wl,-rpath,' + str(out / 'runtime/zig-out/lib'),
    '-lbinder_ndk',
    *drm_flags,
    '-Wl,-rpath,' + drm_libdir,
    '-o',
    str(out / 'probe'),
]
(out / 'compile-command.json').write_text(json.dumps([command, cc, cmd], indent=2))
with (out / 'compile.log').open('w') as log:
    for c in [cc, cmd]:
        p = subprocess.run(c, stdout=log, stderr=subprocess.STDOUT)
        if p.returncode:
            print((out / 'compile.log').read_text()[-14000:])
            raise SystemExit(p.returncode)
(out / 'aosp-provenance.json').write_text(
    json.dumps(
        {
            'root': str(root),
            'frameworks_native_commit': subprocess.check_output(
                ['git', '-C', str(root / 'frameworks/native'), 'rev-parse', 'HEAD'], text=True
            ).strip(),
            'aidl_sha256': hashlib.sha256(aidl.read_bytes()).hexdigest(),
            'compiled_source_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        },
        indent=2,
    )
)
print(out / 'probe')
