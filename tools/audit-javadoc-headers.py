#!/usr/bin/env python3
"""Reject packaging or bytecode use of compile-only SDK Javadoc imports."""

import struct
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


def audit(archive, names):
    names = tuple(name.encode("ascii") for name in names)
    failures = []
    with zipfile.ZipFile(archive) as jar:
        for entry in jar.infolist():
            if not entry.filename.endswith(".class"):
                continue
            if entry.filename.removesuffix(".class").encode() in names:
                failures.append(f"packaged Javadoc header: {entry.filename}")
            # Also catch array descriptors, generic signatures and annotation class
            # literals. Scan every UTF8 entry so no method/field shape is missed.
            try:
                for value in utf8_constants(jar.read(entry)):
                    for name in names:
                        if (value == name or b"L" + name + b";" in value
                                or b"L" + name + b"<" in value):
                            failures.append(f"{entry.filename}: runtime reference to {name.decode()}")
            except ValueError as error:
                failures.append(f"{entry.filename}: {error}")
    return sorted(set(failures))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("usage: audit-javadoc-headers.py <runtime-classes.jar> <internal-class-names...>")
    failures = audit(sys.argv[1], sys.argv[2:])
    if failures:
        sys.exit("\n".join(failures))
