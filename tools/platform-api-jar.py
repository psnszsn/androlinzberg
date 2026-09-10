#!/usr/bin/env python3
"""Project unchanged platform class bytes for the hosted APK compiler context."""
import argparse
import zipfile

# Match HostedPlatformClassLoader. Its smoke compares this output against every
# source class, so changes to either boundary must remain consistent.
PREFIXES = (
    'java/', 'javax/', 'jdk/', 'sun/', 'android/', 'com/android/', 'dalvik/',
    'libcore/', 'org/apache/harmony/', 'org/apache/http/', 'org/w3c/',
    'org/xml/', 'org/json/', 'dev/artzig/', 'com/google/android/collect/',
    'com/google/android/gles_jni/', 'com/google/android/mms/', 'org/ccil/cowan/tagsoup/',
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source')
    parser.add_argument('output')
    args = parser.parse_args()
    with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(args.output, 'w') as output:
        for name in sorted(source.namelist()):
            if name.endswith('.class') and name.startswith(PREFIXES):
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.external_attr = 0o100644 << 16
                output.writestr(entry, source.read(name))


if __name__ == '__main__':
    main()
