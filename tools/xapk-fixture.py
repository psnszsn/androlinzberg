#!/usr/bin/env python3
"""Build a distribution envelope from unchanged, signed fixture APKs."""
import json
import sys
import zipfile

base, split, output = sys.argv[1:]
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for path, name in ((base, 'base.apk'), (split, 'feature.apk')):
        with open(path, 'rb') as apk:
            archive.writestr(name, apk.read())
    archive.writestr('manifest.json', json.dumps({
        'xapk_version': 2, 'package_name': 'dev.artzig.fixtures.xapk',
        'split_apks': [{'file': 'base.apk', 'id': 'base'}, {'file': 'feature.apk', 'id': 'feature'}],
    }))
    archive.writestr('Android/obb/dev.artzig.fixtures.xapk/main.1.dev.artzig.fixtures.xapk.obb',
                     'Expansion data from XAPK\n')
