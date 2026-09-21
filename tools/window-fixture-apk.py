#!/usr/bin/env python3
"""Package a compiled fixture: its resources APK plus the dex of its code."""
import sys
import zipfile

mode, first, second, output = sys.argv[1:]
if mode == 'package':
    with zipfile.ZipFile(output, 'w') as target:
        for source in (first, second):
            with zipfile.ZipFile(source) as archive:
                for info in archive.infolist():
                    if source == second and not info.filename.endswith('.dex'):
                        continue
                    target.writestr(info, archive.read(info))
else:
    raise SystemExit('unknown fixture operation: ' + mode)
