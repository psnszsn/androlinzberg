#!/usr/bin/env python3
"""Package a compiled app: its resources APK, the dex of its code and,
optionally, the Java resources (non-class entries) of jars it links."""
import sys
import zipfile

mode, first, second, output, *resource_jars = sys.argv[1:]


def java_resource(name: str) -> bool:
    if name.endswith('/') or name.endswith('.class'):
        return False
    if name == 'META-INF/MANIFEST.MF' or name.startswith('META-INF/versions/'):
        return False
    return not name.startswith('META-INF/') or not name.endswith(('.SF', '.RSA', '.DSA', '.EC'))


if mode == 'package':
    with zipfile.ZipFile(output, 'w') as target:
        written = set()
        for source in (first, second):
            with zipfile.ZipFile(source) as archive:
                for info in archive.infolist():
                    if source == second and not info.filename.endswith('.dex'):
                        continue
                    target.writestr(info, archive.read(info))
                    written.add(info.filename)
        for jar in resource_jars:
            with zipfile.ZipFile(jar) as archive:
                for info in archive.infolist():
                    if info.filename in written or not java_resource(info.filename):
                        continue
                    target.writestr(info, archive.read(info))
                    written.add(info.filename)
else:
    raise SystemExit('unknown fixture operation: ' + mode)
