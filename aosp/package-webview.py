#!/usr/bin/env python3
"""Package the independently pinned, unmodified AOSP WebView provider APK."""
from __future__ import annotations
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile

VERSION = "140.0.7339.15"
REVISION = "27d78d3db63b2dbe56d1136b7cefc502ca50548d"
APK_PATH = f"{VERSION}/x86_64/webview.apk"
APK_SHA256 = "04b161c3e5cd125068e41633d60b027f9c96f46fbd5ae79a0b4e86eb71ea01af"
APK_BLOB = "0b8ea7c8f0961d1d84392830021673ec037f5215"
APK_SIZE = 327642777
SOURCE = f"https://android.googlesource.com/platform/external/chromium-webview/+/{REVISION}/{APK_PATH}"

def package(apk: Path, output: Path) -> None:
    # Keep one descriptor open from validation through archiving.
    with apk.open("rb") as source:
        size = os.fstat(source.fileno()).st_size
        if size != APK_SIZE:
            raise ValueError(f"unexpected APK size: {size}")
        sha = hashlib.sha256()
        blob = hashlib.sha1(f"blob {size}\0".encode())
        while chunk := source.read(1024 * 1024):
            sha.update(chunk)
            blob.update(chunk)
        if sha.hexdigest() != APK_SHA256 or blob.hexdigest() != APK_BLOB:
            raise ValueError("APK does not match the pinned AOSP artifact")
        source.seek(0)
        metadata = (json.dumps({"version": VERSION, "revision": REVISION,
            "path": APK_PATH, "url": SOURCE, "git_blob": APK_BLOB,
            "sha256": APK_SHA256, "size": APK_SIZE}, indent=2, sort_keys=True) + "\n").encode()
        output.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=output.name + ".", dir=output.parent)
        try:
            with os.fdopen(fd, "wb") as raw:
                with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed:
                    with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                        for name, length, stream in [("SOURCE.json", len(metadata), io.BytesIO(metadata)),
                                                       ("webview.apk", size, source)]:
                            entry = tarfile.TarInfo(name)
                            entry.size = length
                            entry.mode = 0o644
                            entry.mtime = 0
                            entry.uid = entry.gid = 0
                            archive.addfile(entry, stream)
            os.replace(temporary, output)
        finally:
            Path(temporary).unlink(missing_ok=True)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True,
        help=f"unmodified APK downloaded from {SOURCE}")
    parser.add_argument("--output", type=Path,
        default=Path(__file__).resolve().parent / f"androlinzberg-webview-{VERSION}.tar.gz")
    args = parser.parse_args()
    package(args.apk, args.output)
    print(args.output)

if __name__ == "__main__":
    main()
