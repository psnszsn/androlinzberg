#!/usr/bin/env python3
"""list-java.py <out-rsp> <dir>... — write an rsp with absolute paths of all .java under dirs."""
import os, sys
out, dirs = sys.argv[1], sys.argv[2:]
files = []
for d in dirs:
    for root, _, names in os.walk(d):
        files += [os.path.abspath(os.path.join(root, n)) for n in names if n.endswith(".java")]
with open(out, "w") as f:
    f.write("\n".join(sorted(files)) + "\n")
