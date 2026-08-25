#!/usr/bin/env python3
"""parcelify.py <stub.java> <ClassName> — make a stub class Parcelable with CREATOR."""
import sys, re
p, cls = sys.argv[1], sys.argv[2]
s = open(p).read()
if f'Creator<{cls}> CREATOR' in s:
    sys.exit()
decl = re.search(r'public (?:final )?(?:abstract )?class ' + cls + r'\b([^{]*){', s)
if decl and 'Parcelable' not in decl.group(0):
    s = s[:decl.end()-1] + (' implements android.os.Parcelable ' if 'implements' not in decl.group(1) else '') + '{' + s[decl.end():]
i = s.rstrip().rfind('}')
ctor = f'new {cls}()' if re.search(r'public '+cls+r'\(\)', s) or not re.search(r'public '+cls+r'\(', s) else 'null'
add = f'''    public int describeContents() {{ return 0; }}
    public void writeToParcel(android.os.Parcel dest, int flags) {{}}
    public static final android.os.Parcelable.Creator<{cls}> CREATOR =
            new android.os.Parcelable.Creator<{cls}>() {{
        public {cls} createFromParcel(android.os.Parcel in) {{ return {ctor}; }}
        public {cls}[] newArray(int size) {{ return new {cls}[size]; }}
    }};
'''
s = s.rstrip()[:i] + add + '}\n'
open(p, 'w').write(s)
print('parcelified', cls)
