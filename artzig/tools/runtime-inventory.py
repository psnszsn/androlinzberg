#!/usr/bin/env python3
"""Declared ART/Bionic exception inputs and actual staged ELF dependency closure.

This is not an exhaustive compiler/preprocessor or dynamic dlsym provenance test.
Compiler-injected archives and external compiler Run objects are identified by
artifact hash; the framework's separate native audit checks its stock boundary.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def elf_record(path):
    dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
    symbols = subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(path)], text=True)
    exports = []
    imports = []
    for line in symbols.splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[0].endswith(':') and fields[4] in ('GLOBAL', 'WEAK'):
            (imports if fields[6] == 'UND' else exports).append(fields[7])
    return dict(sha256=digest(path), needed=re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic),
                soname=re.findall(r'\(SONAME\).*?\[(.*?)\]', dynamic),
                rpath=re.findall(r'\((?:RPATH|RUNPATH)\).*?\[(.*?)\]', dynamic),
                exports=sorted(set(exports)), imports=sorted(set(imports)))


def resolved_libraries(text):
    if 'not found' in text:
        raise ValueError('unresolved runtime dependency: ' + text)
    result = {}
    for line in text.splitlines():
        match = re.match(r'\s*(\S+) => (.+?) \(0x[0-9a-f]+\)', line)
        if match:
            result[Path(match[1]).name] = Path(match[2]).resolve(strict=True)
        else:
            match = re.match(r'\s*(/\S+) \(0x[0-9a-f]+\)', line)
            if match:
                path = Path(match[1]).resolve(strict=True)
                result[path.name] = path
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'package', 'project', 'manifest', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--input', action='append', default=[], type=Path)
    parser.add_argument('--directory', action='append', default=[], type=Path)
    args = parser.parse_args()
    runtime, package, project = (p.resolve(strict=True) for p in (args.runtime, args.package, args.project))
    cache = {}
    def record(path):
        path = path.resolve(strict=True)
        if path not in cache:
            kind = ('pinned_runtime_package' if path.is_relative_to(package) else
                    'repository_owned' if path.is_relative_to(project) else
                    'generated' if '.zig-cache' in path.parts else
                    'pinned_dependency' if 'zig-pkg' in path.parts else 'host')
            cache[path] = dict(path=str(path), kind=kind, sha256=digest(path))
        return cache[path]
    inputs = [record(path) for path in args.input]
    directories = [str(path.resolve(strict=True)) for path in args.directory]
    binaries = sorted(path for parent in ('bin', 'lib64') for path in (runtime / parent).iterdir()
                      if path.is_file() and path.open('rb').read(4) == b'\x7fELF')
    if not binaries:
        raise ValueError('runtime contains no ELF artifacts')
    packaged = {path.name: path.resolve() for path in binaries}
    artifacts, external = {}, {}
    environment = dict(os.environ, LD_LIBRARY_PATH=str(runtime / 'lib64'))
    environment.pop('LD_PRELOAD', None)
    environment.pop('LD_AUDIT', None)
    # Remove the repository CWD so relative build-cache rpaths cannot hide an
    # incomplete installation. ldd inspects these trusted, locally built ELFs.
    with tempfile.TemporaryDirectory(prefix='azk-runtime-loader-') as work:
        for path in binaries:
            row = elf_record(path)
            loaded = subprocess.check_output(['ldd', str(path)], text=True, env=environment, cwd=work)
            resolved = resolved_libraries(loaded)
            for soname, dependency in resolved.items():
                if soname in packaged and dependency != packaged[soname]:
                    raise ValueError(f'{path.name}: {soname} escapes staged runtime: {dependency}')
                if soname not in packaged:
                    external[str(dependency)] = record(dependency)
            for needed in row['needed']:
                if needed not in resolved:
                    raise ValueError(f'{path.name}: missing loader resolution for {needed}')
            row['resolved'] = {name: str(value) for name, value in sorted(resolved.items())}
            artifacts[str(path.relative_to(runtime))] = row
    report = dict(schema=1, scope='declared ART/libcore/Bionic runtime exception',
                  limitation='Declared build inputs, not exhaustive preprocessor/compiler-injected inputs or dlsym resolution',
                  package=str(package), inputs=inputs, directories=directories, build=json.loads(args.manifest.read_text()),
                  artifacts=artifacts, external_libraries=external)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Runtime inventory: {len(artifacts)} ELF artifacts, {len(cache)} unique inputs/libraries; dependency closure resolves')


if __name__ == '__main__':
    main()
