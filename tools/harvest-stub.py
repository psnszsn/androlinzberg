#!/usr/bin/env python3
"""harvest-stub.py FQCN [FQCN...] — regenerate stub classes by scanning the
vendored sources for members referenced on each class:
  FLAG_UPPER / *_FLAG → String constant (aconfig @FlaggedApi ids)
  UPPER_CASE          → int constant (stable ordinal)
  camelCase()         → public static boolean method returning false
Writes to src/framework-stubs/<path>. Skips names already present."""
import os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FB = os.path.join(ROOT, '..', 'extra', 'frameworks-base')
SCAN = [os.path.join(FB, 'core', 'java'), os.path.join(FB, 'graphics', 'java'),
        os.path.join(ROOT, '.cache', 'aidl-java')]
def harvest(fq):
    pkg, cls = fq.rsplit('.', 1)
    consts, methods = set(), set()
    imp_static = re.compile(r'import static ' + re.escape(fq) + r'\.([A-Za-z0-9_]+);')
    use = re.compile(r'\b' + re.escape(cls) + r'(?:\.|::)([A-Za-z0-9_]+)(\s*\()?')
    imp_plain = re.compile(r'import (static )?' + re.escape(fq).replace(r'\.', r'\.') + r'[;.]')
    for base in SCAN:
        for dirpath, _, files in os.walk(base):
            for f in files:
                if not f.endswith('.java'): continue
                p = os.path.join(dirpath, f)
                try: s = open(p, encoding='utf-8', errors='ignore').read()
                except OSError: continue
                if cls not in s: continue
                relevant = imp_plain.search(s) or (pkg in s)
                if not relevant: continue
                for m in imp_static.finditer(s):
                    n = m.group(1)
                    (methods if n[0].islower() else consts).add(n)
                    # also catch usage as bare name after static import
                for m in use.finditer(s):
                    n, call = m.group(1), m.group(2)
                    if n in ('class',): continue
                    if call or '::'+n in s[m.start():m.end()+2] or ('::' in s[max(0,m.start()):m.start()+len(cls)+2] and n[0].islower()):
                        methods.add(n)
                    elif n[0].isupper() and not n[0].isdigit(): consts.add(n)
                for m in re.finditer(re.escape(cls)+r'::([A-Za-z0-9_]+)', s):
                    methods.add(m.group(1))
    # bare static-imported method calls: name( after import static fq.name
    return consts, methods
for fq in sys.argv[1:]:
    pkg, cls = fq.rsplit('.', 1)
    out = os.path.join(ROOT, 'src', 'framework-stubs', pkg.replace('.', '/'), cls + '.java')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    existing = open(out).read() if os.path.exists(out) else ''
    consts, methods = harvest(fq)
    lines = []
    for i, n in enumerate(sorted(consts)):
        if re.search(r'\b'+n+r'\b', existing): continue
        if n.startswith('FLAG_') and cls.endswith('Flags'):
            lines.append(f'    public static final String {n} = "{pkg}.{n.lower()}";')
        else:
            lines.append(f'    public static final int {n} = {i + 1000};')
    for n in sorted(methods):
        if re.search(r'\b'+n+r'\s*\(', existing): continue
        lines.append(f'    public static boolean {n}() {{ return false; }}')
    if existing:
        i = existing.rstrip().rfind('}')
        s = existing.rstrip()[:i] + '\n'.join(lines) + '\n}\n'
    else:
        s = (f'/* artzig stub (harvested from usage sites). */\npackage {pkg};\n'
             f'public final class {cls} {{\n    private {cls}() {{}}\n' + '\n'.join(lines) + '\n}\n')
    open(out, 'w').write(s)
    print(f'{fq}: +{len(lines)} members')
