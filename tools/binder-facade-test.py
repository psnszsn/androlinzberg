#!/usr/bin/env python3
"""Run only isolated regression children; terminate service to test Binder death."""

import argparse
import hashlib
import json
import os
import resource
import selectors
import subprocess
import tempfile
import time
from pathlib import Path

resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
p.add_argument('--probe', type=Path, required=True)
p.add_argument('--server', type=Path, help='Use a production service executable in place of the probe server mode')
p.add_argument('--support-service', type=Path, action='append', default=[],
               help='Start a production dependency with ready/stdin-EOF supervision')
p.add_argument('--broker', type=Path, required=True)
p.add_argument('--declare-service', action='append', default=[], help='Immutable session AIDL declaration')
p.add_argument('--library', type=Path, action='append', default=[])
p.add_argument('--producer-crash', action='store_true')
p.add_argument('--release-pressure', action='store_true')
p.add_argument('--standalone', action='store_true', help='Run one native test with an authenticated broker session')
p.add_argument('--system-client', action='store_true', help='Authenticate a host service client using the session system cookie')
p.add_argument('--probe-arg', action='append', default=[], help='Explicit client argv (default: client)')
p.add_argument('--probe-file-arg', action='append', dest='probe_arg', type=lambda value: str(Path(value).resolve()), help='Client argv path resolved before changing directory')
a = p.parse_args()
r = a.output.resolve()
d = r / 'run'
d.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
socket_dir = tempfile.TemporaryDirectory(prefix='azk-native-binder-')
env['LD_LIBRARY_PATH'] = ':'.join(str(p.resolve().parent) for p in a.library)
env.update(
    # Artifact paths can exceed Linux's Unix socket path limit.
    AZK_BINDER_SOCKET=str(Path(socket_dir.name) / 'binder.sock'),
    AZK_BINDER_COOKIE='facade-v2',
    AZK_BINDER_SYSTEM_COOKIE='facade-v2-system',
    AZK_BINDER_PACKAGE='dev.artzig.facade.v2.server',
    AZK_PROP_AREA=str(d / 'props'),
)
children = []
intentional_crashes = set()
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
        [str(a.broker.resolve()), '--socket', env['AZK_BINDER_SOCKET'], '--prop-area', env['AZK_PROP_AREA'],
         *[arg for name in a.declare_service for arg in ('--declare-service', name)]],
        env=env,
        cwd=r,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=log,
        text=True,
    )
    children.append(b)
    assert line(b).startswith('ready')
    for index, executable in enumerate(a.support_service):
        log = (d / f'support-{index}.log').open('w')
        logs.append(log)
        support = subprocess.Popen(
            [str(executable.resolve())], env=env, cwd=r,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
        )
        children.append(support)
        assert line(support).startswith('ready')
    if a.standalone:
        log = (d / 'client.log').open('w')
        logs.append(log)
        c = subprocess.Popen(
            [str(a.probe.resolve()), *(a.probe_arg or [])], env=env, cwd=r,
            stdout=subprocess.PIPE, stderr=log, text=True,
        )
        children.append(c)
        lines.append(c.communicate(timeout=60)[0])
        result['client_exit'] = c.returncode
    else:
        log = (d / 'server.log').open('w')
        logs.append(log)
        s = subprocess.Popen(
            [str(a.server.resolve())] if a.server else [str(a.probe.resolve()), 'server'],
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
        if not a.system_client:
            env.pop('AZK_BINDER_SYSTEM_COOKIE', None)  # Ordinary native clients have an app UID.
        if a.release_pressure:
            log = (d / 'release-pressure-server.log').open('w')
            logs.append(log)
            pressure_env = env.copy()
            pressure_env['AZK_BINDER_PACKAGE'] = 'dev.artzig.facade.v2'
            pressure_server = subprocess.Popen(
                [str(a.probe.resolve()), 'server-pressure'], env=pressure_env, cwd=r,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
            )
            children.append(pressure_server)
            assert line(pressure_server).startswith('ready')
            log = (d / 'release-pressure.log').open('w')
            logs.append(log)
            pressure = subprocess.Popen(
                [str(a.probe.resolve()), 'release-pressure'], env=env, cwd=r,
                stdout=subprocess.PIPE, stderr=log, text=True,
            )
            children.append(pressure)
            lines.append(pressure.communicate(timeout=60)[0])
            result['release_pressure_exit'] = pressure.returncode
            pressure_server.stdin.close()
            pressure_server.wait(timeout=5)
            result['release_pressure_server_exit'] = pressure_server.returncode
            if pressure.returncode != 0:
                raise RuntimeError(f'release pressure probe failed: {pressure.returncode}')
        if a.producer_crash:
            log = (d / 'producer-crash.log').open('w')
            logs.append(log)
            crash = subprocess.Popen(
                [str(a.probe.resolve()), 'crash-producer'], env=env, cwd=r,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True,
            )
            children.append(crash)
            assert line(crash).strip() == 'READY_CRASH'
            crash.kill()
            intentional_crashes.add(crash)
            crash.wait(timeout=5)
            result['producer_crash_exit'] = crash.returncode
            assert crash.returncode == -9
        log = (d / 'client.log').open('w')
        logs.append(log)
        # Unbuffered binary stdout avoids TextIO read-ahead hiding ready lines from select.
        c = subprocess.Popen(
            [str(a.probe.resolve()), *(a.probe_arg or ['client'])], env=env, cwd=r, stdout=subprocess.PIPE, stderr=log, bufsize=0
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
        result['server_exit'] = s.returncode
        if s.returncode != 0:
            raise RuntimeError(f'service exited unexpectedly: {s.returncode}')
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
    socket_dir.cleanup()
    result['stdout'] = ''.join(lines)
    result['child_exit_codes'] = [p.returncode for p in children]
    unexpected = [p.returncode for p in children
                  if p.returncode != 0 and p not in intentional_crashes]
    if unexpected:
        result.setdefault('error', f'unexpected child exit codes: {unexpected}')
    result['sha256'] = {
        str(p): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in [a.probe.resolve(), a.broker.resolve(), *(p.resolve() for p in a.library),
                  *([a.server.resolve()] if a.server else []), *(p.resolve() for p in a.support_service)]
    }
    (d / 'results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    if result.get('client_exit') != 0 or 'error' in result:
        raise SystemExit(1)
