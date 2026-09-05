#!/usr/bin/env python3
"""Optional hardware probe against the built production facade, never fake Binder.

Run inside the development shell. The full pinned AOSP checkout is read-only;
only the opaque graphics-handle adapters from the experiment are reused.
"""

import argparse
import json
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('output', type=Path)
p.add_argument('aosp', type=Path)
p.add_argument('--binder', type=Path, required=True)
p.add_argument('--ndk', type=Path, required=True)
a = p.parse_args()
out, aosp = a.output.resolve(), a.aosp.resolve()
repo = Path(__file__).resolve().parents[3]
probe = Path(__file__).resolve().parent
out.mkdir(parents=True, exist_ok=True)
adapters = out / 'adapters'
for name in [
    'ui/GraphicBufferMapper.h',
    'ui/GraphicBufferAllocator.h',
    'ui/DependencyMonitor.h',
    'utils/Trace.h',
]:
    target = adapters / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(probe / 'include' / name, target)
generated = out / 'generated'
generated.mkdir(exist_ok=True)
elf = subprocess.check_output(['readelf', '-l', str(Path(sys.executable).resolve())], text=True)
loader = re.search(r'Requesting program interpreter: (.*?)\]', elf).group(1)
subprocess.run(
    [
        loader,
        str(aosp / 'prebuilts/sdk/tools/linux/bin/aidl'),
        '--lang=cpp',
        '-I' + str(aosp / 'frameworks/native/libs/gui/aidl'),
        '-h' + str(generated),
        '-o' + str(generated),
        str(aosp / 'frameworks/native/libs/gui/aidl/android/gui/IFpsListener.aidl'),
    ],
    check=True,
)
include_names = [
    'frameworks/native/libs/binder/include',
    'frameworks/native/libs/binder/ndk/include_ndk',
    'frameworks/native/libs/binder/ndk/include_platform',
    'system/core/libutils/binder/include',
    'system/core/libutils/include',
    'system/libbase/include',
    'system/logging/liblog/include',
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
flags = [
    '-std=c++23',
    '-O0',
    '-g',
    '-fno-rtti',
    '-fno-exceptions',
    '-fno-sanitize=undefined',
    '-DANDROID_UTILS_CALLSTACK_ENABLED=0',
    '-include',
    str(probe / 'compat.h'),
    '-include',
    'cinttypes',
]
for path in [repo / 'src/native/binder/include', adapters, generated] + [aosp / n for n in include_names]:
    flags += ['-I', str(path)]
source_names = [
    'frameworks/native/libs/ui/Fence.cpp',
    'frameworks/native/libs/ui/GraphicBuffer.cpp',
    'frameworks/native/libs/ui/PixelFormat.cpp',
    'system/core/libgrallocusage/GrallocUsageConversion.cpp',
]
sources = [probe / 'probe.cpp', generated / 'android/gui/IFpsListener.cpp'] + [aosp / n for n in source_names]
cc = [
    'zig',
    'cc',
    '-std=gnu11',
    '-D__INTRODUCED_IN(x)=',
    '-I',
    str(aosp / 'system/core/libsync/include'),
    '-I',
    str(aosp / 'frameworks/native/libs/nativewindow/include'),
    '-c',
    str(aosp / 'system/core/libsync/sync.c'),
    '-o',
    str(out / 'sync.o'),
]
drm = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'libdrm'], text=True))
libdir = subprocess.check_output(['pkg-config', '--variable=libdir', 'libdrm'], text=True).strip()
cmd = [
    'zig',
    'c++',
    *flags,
    *map(str, sources),
    str(out / 'sync.o'),
    str(a.binder.resolve()),
    str(a.ndk.resolve()),
    *drm,
    '-Wl,-rpath,' + libdir,
    '-o',
    str(out / 'probe'),
]
(out / 'production-compile-command.json').write_text(json.dumps([cc, cmd], indent=2))
with (out / 'production-compile.log').open('w') as log:
    for command in [cc, cmd]:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        if result.returncode:
            print((out / 'production-compile.log').read_text()[-12000:])
            raise SystemExit(result.returncode)
print(out / 'probe')
