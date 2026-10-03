#!/usr/bin/env python3
"""Link an android_app's resources with its prebuilt AAR static_libs.

Soong's android_app links its static Android libraries' resources into the
app: each AAR's res/ is compiled, the libraries are layered in dependency
order (dependencies first) beneath the app's own resources with
--auto-add-overlay, and an R class is generated for the app and for every
library package (--extra-packages), since library code references its own
package's R fields.

usage: android-app-link.py --aapt2 AAPT2 --framework FRAMEWORK_RES --manifest MANIFEST
           --out-apk OUT --out-java DIR [--app-res FLATS.zip] [--feature-flags FLAGS]
           [--min-sdk N] [--target-sdk N] -- AAR...
"""
import argparse
import pathlib
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ElementTree
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--aapt2', required=True)
    parser.add_argument('--framework', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--out-apk', required=True)
    parser.add_argument('--out-java', required=True)
    parser.add_argument('--app-res')
    parser.add_argument('--feature-flags')
    parser.add_argument('--min-sdk', default='35')
    parser.add_argument('--target-sdk', default='35')
    parser.add_argument('aars', nargs='*')
    options = parser.parse_args()

    with tempfile.TemporaryDirectory() as scratch:
        root = pathlib.Path(scratch)
        packages = []
        overlays = []
        for index, aar in enumerate(options.aars):
            extracted = root / f'lib{index}'
            with zipfile.ZipFile(aar) as archive:
                members = [name for name in archive.namelist()
                           if name == 'AndroidManifest.xml' or name.startswith('res/')]
                archive.extractall(extracted, members)
            manifest = ElementTree.parse(extracted / 'AndroidManifest.xml').getroot()
            package = manifest.get('package')
            if not package:
                raise SystemExit(f'{aar}: manifest has no package')
            if package not in packages:
                packages.append(package)
            resources = extracted / 'res'
            if resources.is_dir() and any(path.is_file() for path in resources.rglob('*')):
                flats = root / f'lib{index}.zip'
                subprocess.run([options.aapt2, 'compile', '--dir', str(resources), '-o', str(flats)], check=True)
                overlays.append(str(flats))
        if options.app_res:
            overlays.append(options.app_res)

        link = [options.aapt2, 'link', '--manifest', options.manifest, '-I', options.framework,
                '--auto-add-overlay', '--min-sdk-version', options.min_sdk,
                '--target-sdk-version', options.target_sdk, '--java', options.out_java,
                '-o', options.out_apk]
        if options.feature_flags:
            link += ['--feature-flags', options.feature_flags]
        if packages:
            link += ['--extra-packages', ':'.join(packages)]
        for flats in overlays:
            link += ['-R', flats]
        subprocess.run(link, check=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
