#!/usr/bin/env python3
"""Package a compiled fixture and add its exact artifact hash to testkit inputs."""
import hashlib
import json
from pathlib import Path
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
elif mode == 'registry':
    registry = json.loads(Path(first).read_text())
    registry['window-contracts'] = {
        'environment': 'AZK_APK_WINDOW_FIXTURE',
        'sha256': hashlib.sha256(Path(second).read_bytes()).hexdigest(),
        'package': 'dev.artzig.fixtures.windows',
        'versionCode': 1,
        'versionName': '1',
    }
    Path(output).write_text(json.dumps(registry, indent=2) + '\n')
else:
    raise SystemExit('unknown fixture operation: ' + mode)
