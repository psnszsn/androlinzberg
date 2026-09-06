#!/usr/bin/env python3
"""Multiprocess stock-Surface fixture; tools/shot.sh owns the compositor."""
import argparse
import hashlib
import json
import os
import re
import resource
from pathlib import Path
import selectors
import subprocess
import time
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--broker', type=Path, required=True)
p.add_argument('--server', type=Path, required=True)
p.add_argument('--producer', type=Path, required=True)
p.add_argument('--library', type=Path, action='append', default=[])
p.add_argument('--output', type=Path, required=True)
p.add_argument('--scenario', choices=['normal', 'producer-crash', 'consumer-crash', 'broker-loss', 'backpressure'], default='normal')
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
runtime = tempfile.TemporaryDirectory(prefix='azk-surface-', dir=os.environ.get('XDG_RUNTIME_DIR', '/tmp'))
env = os.environ.copy()
env.update(AZK_BINDER_SOCKET=str(Path(runtime.name) / 'binder.sock'),
           AZK_PROP_AREA=str(out / 'props'), AZK_BINDER_COOKIE='surface-fixture',
           AZK_BINDER_SYSTEM_COOKIE='surface-fixture-system',
           AZK_BINDER_PACKAGE='dev.artzig.surface.server',
           LD_LIBRARY_PATH=':'.join(str(x.resolve().parent) for x in a.library))
children, streams = [], []
result = {}


def spawn(binary, role, args=()):
    log = (out / (role + '.log')).open('wb')
    streams.append(log)
    child = subprocess.Popen([str(binary.resolve()), *args], env=env, cwd=out,
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=log, bufsize=0)
    children.append(child)
    return child


def line(child, timeout=10):
    with selectors.DefaultSelector() as selector:
        selector.register(child.stdout, selectors.EVENT_READ)
        if not selector.select(timeout):
            raise RuntimeError('fixture output timeout')
    value = child.stdout.readline().decode().strip()
    if not value:
        raise RuntimeError('fixture exited before expected output')
    with (out / 'frames.jsonl').open('a') as journal:
        journal.write(json.dumps(dict(pid=child.pid, line=value)) + '\n')
    return value


try:
    (out / 'frames.jsonl').write_text('')
    broker = spawn(a.broker, 'broker', ('--socket', env['AZK_BINDER_SOCKET'],
                                      '--prop-area', env['AZK_PROP_AREA']))
    assert line(broker).startswith('ready')
    pressure = a.scenario == 'backpressure'
    server = spawn(a.server, 'server', ('--hold-presentation',) if pressure else ())
    assert line(server) == 'ready'
    env.pop('AZK_BINDER_SYSTEM_COOKIE')
    env['AZK_BINDER_PACKAGE'] = 'dev.artzig.surface.producer'
    expect_death = a.scenario in ('consumer-crash', 'broker-loss')
    producer = spawn(a.producer, 'producer', ('--expect-death',) if expect_death else ('--pressure',) if pressure else ())
    for frame in range(1, 13):
        value = line(producer)
        if value == 'BACKPRESSURE':
            assert pressure and not result.get('backpressure')
            # The Wayland owner continues dispatching while its queue is full.
            subprocess.run(['grim', str(out / 'withheld.png')], check=True, timeout=3)
            result['backpressure'] = True
            server.stdin.write(b'resume\n')
            producer.stdin.write(b'resume\n')
            value = line(producer)
        assert value == f'queued {frame}'
    assert not pressure or result.get('backpressure')
    assert line(producer) == 'READY_SCREENSHOT'
    presented = 0
    while presented < 12:
        value = line(server)
        assert value.startswith('presented ')
        presented = int(value.split()[1])
    time.sleep(0.2)
    subprocess.run(['grim', str(out / 'screenshot.png')], check=True)
    ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'])
    magic, dimensions, maximum, pixels = ppm.split(b'\n', 3)
    assert magic == b'P6' and maximum == b'255'
    width, height = map(int, dimensions.split())
    offset = ((height // 2) * width + width // 2) * 3
    actual = tuple(pixels[offset:offset + 3])
    assert all(abs(x - y) <= 2 for x, y in zip(actual, (171, 120, 52))), actual
    result.update(presented=presented, screenshot_size=[width, height], center_rgb=actual)
    if a.scenario == 'consumer-crash':
        server.kill()
        assert server.wait(timeout=5) == -9
    if a.scenario == 'broker-loss':
        broker.kill()
        assert broker.wait(timeout=5) == -9
    if a.scenario == 'producer-crash':
        producer.kill()
        assert producer.wait(timeout=5) == -9
    else:
        producer.stdin.close()
        if expect_death:
            assert line(producer) == 'PASS owner loss fails further producer calls'
        assert producer.wait(timeout=5) == 0
    if a.scenario != 'consumer-crash':
        server.stdin.close()
        assert line(server) == 'released all buffers'
        assert server.wait(timeout=5) == 0
        result['live_buffers_after_shutdown'] = 0
    if a.scenario != 'broker-loss':
        broker.stdin.close()
        assert broker.wait(timeout=5) == 0
    frames = re.findall(r'surface-frame number=(\d+) buffer=(\d+) width=(\d+) height=(\d+)',
                        (out / 'server.log').read_text())
    assert len(frames) == 12, frames
    assert [int(x[0]) for x in frames] == list(range(1, 13))
    assert [tuple(map(int, x[2:])) for x in frames] == [(320, 240)] * 6 + [(480, 360)] * 6
    result['frames'] = frames
    result['scenario'] = a.scenario
    result['passed'] = True
except (AssertionError, RuntimeError, OSError, subprocess.SubprocessError) as error:
    result['error'] = repr(error)
finally:
    for child in reversed(children):
        if child.stdin and not child.stdin.closed:
            child.stdin.close()
        try:
            child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
    for stream in streams:
        stream.close()
    runtime.cleanup()
    result['exit_codes'] = [child.returncode for child in children]
    result['sha256'] = {str(x.resolve()): hashlib.sha256(x.read_bytes()).hexdigest()
                        for x in [a.broker, a.server, a.producer, *a.library]}
    (out / 'results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    if not result.get('passed'):
        raise SystemExit(1)
