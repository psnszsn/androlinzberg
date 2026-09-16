#!/usr/bin/env python3
"""Keep rustc's full dep-info and supply Zig's Make parser a comment-free rule."""
import subprocess
import sys
from pathlib import Path


def main():
    cache_depfile = Path(sys.argv[1])
    command = sys.argv[2:]
    result = subprocess.run(command)
    if result.returncode:
        return result.returncode
    emit = next(arg for arg in command if arg.startswith('--emit=link,dep-info='))
    original = Path(emit.split('dep-info=', 1)[1])
    # Zig's depfile parser does not accept rustc's # env-dep comments. Keep
    # those in the original audit artifact. Declared CARGO/OUT_DIR values are
    # already part of this build command's cache key; the audit checks that no
    # undeclared environment input slipped through.
    rule = next(line for line in original.read_text().replace('\\\n', '').splitlines()
                if line and not line.startswith('#'))
    cache_depfile.write_text(rule + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
