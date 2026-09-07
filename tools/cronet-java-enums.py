#!/usr/bin/env python3
"""Run the pinned Chromium enum generator with a reproducible copyright year.

The original generator uses date.today() only in its copyright comment. Keep
its parser, source templates and hermetic ZIP writer unchanged; the build
supplies the release year and runs from a directory of declared source inputs.
"""
import datetime
import importlib.util
from pathlib import Path
import sys


def main():
    year = int(sys.argv[1])
    source = Path("build/android/gyp/java_cpp_enum.py").resolve()
    sys.path.insert(0, str(source.parent))
    spec = importlib.util.spec_from_file_location("java_cpp_enum", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    class ReleaseDate(datetime.date):
        @classmethod
        def today(cls):
            return cls(year, 1, 1)

    module.date = ReleaseDate
    # The generated provenance names the original generator, not this launcher.
    sys.argv[0] = str(source)
    module.DoMain(sys.argv[2:])


if __name__ == "__main__":
    main()
