#!/usr/bin/env python3
"""Reject packaging or runtime use of compile-only SDK headers."""

import re
import struct
import subprocess
import tempfile
from pathlib import Path
import sys
import zipfile


def utf8_constants(data):
    """Read all classfile UTF8 entries, including descriptors and signatures."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("invalid classfile header")
    count = struct.unpack_from(">H", data, 8)[0]
    offset = 10
    index = 1
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
              11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        if offset >= len(data):
            raise ValueError("truncated constant pool")
        tag = data[offset]
        offset += 1
        if tag == 1:
            if offset + 2 > len(data):
                raise ValueError("truncated UTF8 length")
            size = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            if offset + size > len(data):
                raise ValueError("truncated UTF8 constant")
            yield data[offset:offset + size]
        else:
            if tag not in widths:
                raise ValueError(f"unknown constant-pool tag {tag}")
            size = widths[tag]
        offset += size
        if offset > len(data):
            raise ValueError("truncated constant pool")
        index += 2 if tag in (5, 6) else 1
        if index > count:
            raise ValueError("invalid double-slot constant")


def referenced_bare_names(data, names):
    """Javac retains unused CONSTANT_Class owners for inlined static constants.

    Use the JDK classfile reader to inspect code and every class attribute, with
    only the constant-pool listing removed. This keeps class literals, member
    calls, inheritance, stack maps, bootstrap arguments and nest metadata visible.
    Descriptors/signatures are checked independently from all UTF8 constants.
    Missing tools, malformed output and decode failures all fail closed.
    """
    with tempfile.TemporaryDirectory(prefix="azk-header-audit-") as directory:
        path = Path(directory) / "Input.class"
        path.write_bytes(data)
        result = subprocess.run(["javap", "-p", "-v", str(path)],
                                capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise ValueError("JDK classfile inspection failed")
    before, separator, pool = result.stdout.partition("Constant pool:\n")
    _, body_start, body = pool.partition("\n{\n")
    if not separator or not body_start:
        raise ValueError("unrecognized JDK classfile inspection output")
    live = before + body
    return {name for name in names if re.search(
        r"(?<![\w$/])" + re.escape(name.decode()).replace("/", r"[/.]")
        + r"(?![\w$/])", live)}


def audit(archive, names):
    names = tuple(name.encode("ascii") for name in names)
    failures = []
    with zipfile.ZipFile(archive) as jar:
        for entry in jar.infolist():
            if not entry.filename.endswith(".class"):
                continue
            if entry.filename.removesuffix(".class").encode() in names:
                failures.append(f"packaged Javadoc header: {entry.filename}")
            # Descriptors also cover arrays, generics and annotation class literals.
            try:
                data = jar.read(entry)
                bare = set()
                runtime = set()
                for value in utf8_constants(data):
                    for name in names:
                        if value == name:
                            bare.add(name)
                        if b"L" + name + b";" in value or b"L" + name + b"<" in value:
                            runtime.add(name)
                if bare - runtime:
                    runtime.update(referenced_bare_names(data, bare - runtime))
                for name in runtime:
                    failures.append(f"{entry.filename}: runtime reference to {name.decode()}")
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                failures.append(f"{entry.filename}: {error}")
    return sorted(set(failures))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("usage: audit-javadoc-headers.py <runtime-classes.jar> <internal-class-names...>")
    failures = audit(sys.argv[1], sys.argv[2:])
    if failures:
        sys.exit("\n".join(failures))
