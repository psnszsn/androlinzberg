#!/usr/bin/env python3
"""Emit a Kotlin abstract base class extending <Iface>.Stub whose every method
throws UnsupportedOperationException, from the aidl-generated Java interface.
Usage: gen_unsupported.py <Generated.java> <kotlin package> <class name> <out.kt> [oneway]
Hosted services extend the base and override the methods they serve."""
import re, sys

src, pkg, cls, out = sys.argv[1:5]
oneway = len(sys.argv) > 5 and sys.argv[5] == "oneway"
text = open(src).read()
iface = re.search(r'public interface (\w+) extends android\.os\.IInterface', text).group(1)
# The Default class carries every method with a body; parse its signatures.
default = text[text.index('public static class Default implements'):]
default = default[:default.index('\n  }\n')]
sigs = re.findall(r'@Override public (\S+(?:<[^>]*>)?) (\w+)\(([^)]*)\)', default)

JAVA2KT = {
    'boolean': 'Boolean', 'int': 'Int', 'long': 'Long', 'float': 'Float', 'double': 'Double',
    'byte': 'Byte', 'char': 'Char', 'short': 'Short', 'void': 'Unit',
    'java.lang.String': 'String?', 'java.lang.Object': 'Any?', 'java.lang.CharSequence': 'CharSequence?',
    'android.content.pm.ParceledListSlice': 'android.content.pm.ParceledListSlice<*>?',
}

def kt_type(t, nullable=True):
    t = t.strip()
    if t.endswith('[]'):
        inner = t[:-2]
        prim = {'boolean': 'BooleanArray', 'int': 'IntArray', 'long': 'LongArray', 'float': 'FloatArray',
                'double': 'DoubleArray', 'byte': 'ByteArray', 'char': 'CharArray', 'short': 'ShortArray'}
        if inner in prim:
            return prim[inner] + ('?' if nullable else '')
        return f'Array<{kt_type(inner, True)}>' + ('?' if nullable else '')
    if t in JAVA2KT:
        base = JAVA2KT[t]
        return base
    m = re.match(r'(java\.util\.(?:List|Map))<(.*)>$', t)
    if m:
        name = 'MutableList' if 'List' in m.group(1) else 'MutableMap'
        args = ', '.join(kt_type(a, True) for a in split_generics(m.group(2)))
        return f'{name}<{args}>' + ('?' if nullable else '')
    if t == 'java.util.List':
        return 'MutableList<*>?'
    if t == 'java.util.Map':
        return 'MutableMap<*, *>?'
    return t + ('?' if nullable else '')

def split_generics(s):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch == '<': depth += 1
        if ch == '>': depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur); cur = ''
        else:
            cur += ch
    if cur.strip(): out.append(cur)
    return [x.strip() for x in out]

def split_params(s):
    if not s.strip():
        return []
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch == '<': depth += 1
        if ch == '>': depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur); cur = ''
        else:
            cur += ch
    out.append(cur)
    params = []
    for p in out:
        p = p.strip()
        typ, name = p.rsplit(' ', 1)
        params.append((typ.strip(), name))
    return params

lines = [f'package {pkg}', '',
         f'/** Every {iface} method throws UnsupportedOperationException; the hosted',
         ' * service overrides what it serves (generated from the AIDL interface by',
         ' * tools/gen-unsupported-stub.py; regenerate when the interface changes). */',
         f'abstract class {cls} : {iface}.Stub() {{']
for ret, name, params in sigs:
    ps = split_params(params)
    kparams = ', '.join(f'{n}: {kt_type(t)}' for t, n in ps)
    kret = kt_type(ret, True) if ret != 'void' else 'Unit'
    body = f'throw UnsupportedOperationException("{iface}.{name} is not hosted")'
    if kret == 'Unit':
        lines.append(f'    override fun {name}({kparams}) {{ {body} }}')
    else:
        lines.append(f'    override fun {name}({kparams}): {kret} = {body}')
lines.append('}')
open(out, 'w').write('\n'.join(lines) + '\n')
print(f'{out}: {len(sigs)} methods')
