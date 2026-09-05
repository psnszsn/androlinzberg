#!/usr/bin/env python3
"""Run only isolated regression children; terminate service to test Binder death."""

import argparse
import hashlib
import json
import os
import resource
import selectors
import subprocess
import time
from pathlib import Path

resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
p.add_argument('--probe', type=Path, required=True)
p.add_argument('--broker', type=Path, required=True)
p.add_argument('--library', type=Path, action='append', default=[])
a = p.parse_args()
r = a.output.resolve()
d = r / 'run'
d.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env['LD_LIBRARY_PATH'] = ':'.join(str(p.resolve().parent) for p in a.library)
env.update(
    AZK_BINDER_SOCKET=str(d / 'binder.sock'),
    AZK_BINDER_COOKIE='facade-v2',
    AZK_BINDER_SYSTEM_COOKIE='facade-v2-system',
    AZK_BINDER_PACKAGE='dev.artzig.facade.v2.server',
    AZK_PROP_AREA=str(d / 'props'),
)
children = []
logs = []
result = {}
lines = []


def line(p, timeout=10):
    s = selectors.DefaultSelector()
    s.register(p.stdout, selectors.EVENT_READ)
    if not s.select(timeout):
        raise RuntimeError('stdout timeout')
    v = p.stdout.readline()
    s.close()
    if not v:
        raise RuntimeError('child exited before expected output')
    return v


try:
    log = (d / 'broker.log').open('w')
    logs.append(log)
    b = subprocess.Popen(
        [str(a.broker.resolve()), '--socket', env['AZK_BINDER_SOCKET'], '--prop-area', env['AZK_PROP_AREA']],
        env=env,
        cwd=r,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=log,
        text=True,
    )
    children.append(b)
    assert line(b).startswith('ready')
    log = (d / 'server.log').open('w')
    logs.append(log)
    s = subprocess.Popen(
        [str(a.probe.resolve()), 'server'],
        env=env,
        cwd=r,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=log,
        text=True,
    )
    children.append(s)
    assert line(s).startswith('ready')
    env['AZK_BINDER_PACKAGE'] = 'dev.artzig.facade.v2.client'
    env.pop('AZK_BINDER_SYSTEM_COOKIE', None)  # Native client must have an app UID.
    log = (d / 'client.log').open('w')
    logs.append(log)
    # Unbuffered binary stdout avoids TextIO read-ahead hiding ready lines from select.
    c = subprocess.Popen(
        [str(a.probe.resolve()), 'client'], env=env, cwd=r, stdout=subprocess.PIPE, stderr=log, bufsize=0
    )
    children.append(c)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        v = line(c).decode()
        lines.append(v)
        if v.strip() == 'READY_DEATH':
            break
    else:
        raise RuntimeError('client did not reach death test')
    s.stdin.close()
    s.wait(timeout=5)
    tail = c.communicate(timeout=10)[0].decode()
    lines.append(tail)
    result['client_exit'] = c.returncode
except (AssertionError, RuntimeError, OSError, subprocess.TimeoutExpired) as e:
    result['error'] = str(e)
finally:
    for p in reversed(children):
        if p.stdin and not p.stdin.closed:
            p.stdin.close()
        try:
            p.wait(timeout=2)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
    for f in logs:
        f.close()
    result['stdout'] = ''.join(lines)
    result['child_exit_codes'] = [p.returncode for p in children]
    result['sha256'] = {
        str(p): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in [a.probe.resolve(), a.broker.resolve(), *(p.resolve() for p in a.library)]
    }
    (d / 'results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    if result.get('client_exit') != 0:
        raise SystemExit(1)
