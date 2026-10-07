#!/usr/bin/env python3
"""Rewrite an ELF executable's program interpreter in place.

Usage: set-interpreter.py <interpreter> --output <out> <in>

Zig embeds the host system's dynamic loader (detected from /usr/bin/env) and
offers no way to choose another one without giving up native system-library
discovery; patchelf cannot relocate .interp in Zig-linked executables. The new
path must fit in the existing PT_INTERP segment, which is why the pinned
runtime exposes its loader through a short store path. Only ELF64 little-endian
files are handled: the x86-64 host executables of this build. A statically
linked executable has no PT_INTERP and is copied unchanged.
"""
import os
import struct
import sys


def main(argv):
    if len(argv) != 5 or argv[2] != "--output":
        sys.exit(__doc__)
    interpreter, out_path, in_path = argv[1].encode(), argv[3], argv[4]
    with open(in_path, "rb") as f:
        data = bytearray(f.read())
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        sys.exit(f"{in_path}: not an ELF64 little-endian file")
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    PT_INTERP = 3
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        p_type, = struct.unpack_from("<I", data, base)
        if p_type != PT_INTERP:
            continue
        p_offset, _, _, p_filesz = struct.unpack_from("<QQQQ", data, base + 8)
        if len(interpreter) + 1 > p_filesz:
            sys.exit(f"{in_path}: interpreter {interpreter.decode()} does not fit in {p_filesz} bytes")
        data[p_offset:p_offset + p_filesz] = interpreter.ljust(p_filesz, b"\0")
        break
    else:
        pass  # Statically linked: nothing to pin; the file passes through unchanged.
    with open(out_path, "wb") as f:
        f.write(data)
    os.chmod(out_path, os.stat(in_path).st_mode & 0o7777)


if __name__ == "__main__":
    main(sys.argv)
