#!/usr/bin/env python3
"""Package a compiled fixture: its resources APK plus the dex of its code."""
import sys
import zipfile

mode, first, second, output, *native = sys.argv[1:]
if mode == 'package':
    with zipfile.ZipFile(output, 'w') as target:
        for source in (first, second):
            with zipfile.ZipFile(source) as archive:
                for info in archive.infolist():
                    if source == second and not info.filename.endswith('.dex'):
                        continue
                    target.writestr(info, archive.read(info))
        if native:
            # Cached Nix inputs have epoch mtimes, outside ZIP's date range.
            with open(native[0], 'rb') as library:
                target.writestr("lib/x86_64/libxapk_fixture.so", library.read())
else:
    raise SystemExit('unknown fixture operation: ' + mode)
