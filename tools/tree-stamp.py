#!/usr/bin/env python3
"""tree-stamp.py <out> <dir-or-file>... — write a content stamp (sha256 over
path, size, mtime of every file) so a build step depending on whole source
trees gets a precise cache key (zig's Run step does not hash directory args)."""
import hashlib, os, sys
h = hashlib.sha256()
for root in sys.argv[2:]:
    if os.path.isfile(root):
        st = os.stat(root); h.update(f"{root} {st.st_size} {st.st_mtime_ns}\n".encode()); continue
    for dp, dns, fns in os.walk(root):
        dns.sort()
        for f in sorted(fns):
            p = os.path.join(dp, f)
            try: st = os.stat(p)
            except OSError: continue
            h.update(f"{p} {st.st_size} {st.st_mtime_ns}\n".encode())
(sys.stdout if sys.argv[1] == "-" else open(sys.argv[1], "w")).write(h.hexdigest() + "\n")
