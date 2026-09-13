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
p.add_argument('--scenario', choices=['normal', 'producer-crash', 'consumer-crash', 'broker-loss', 'backpressure', 'pointer-capture', 'pointer-capture-unavailable', 'cursor-animation', 'delayed-fence', 'fence-shutdown', 'fence-timeout', 'paced', 'paced-fallback', 'drag-icon', 'drag-icon-v4', 'drag-icon-offset', 'drag-icon-v4-offset'], default='normal')
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
    paced = a.scenario.startswith('paced')
    delayed = a.scenario == 'delayed-fence' or a.scenario.startswith('fence-')
    early = False
    server = spawn(a.server, 'server', ('--hold-presentation',) if pressure else ('--' + a.scenario,) if a.scenario.startswith('pointer-capture') or a.scenario == 'cursor-animation' or a.scenario.startswith('drag-icon') or paced else ())
    assert line(server) == 'ready'
    env.pop('AZK_BINDER_SYSTEM_COOKIE')
    env['AZK_BINDER_PACKAGE'] = 'dev.artzig.surface.producer'
    expect_death = a.scenario in ('consumer-crash', 'broker-loss')
    producer = spawn(a.producer, 'producer', ('--expect-death',) if expect_death else ('--pressure',) if pressure else ('--' + a.scenario,) if delayed or paced else ())
    presented = 0
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
        if value == 'FENCE_PENDING':
            assert delayed and frame == 7
            pending_started = time.monotonic()
            while presented < 6:
                record = line(server)
                assert record.startswith('presented '), record
                presented = int(record.split()[1])
            assert presented == 6
            # queueBuffer acknowledges the producer before the owner's event
            # loop necessarily acquires that frame. Wait for observed acquisition
            # before checking that an unsignaled fence leaves dispatch responsive.
            acquisition_deadline = time.monotonic() + .5
            while True:
                server.stdin.write(b'p')
                while True:
                    remaining = acquisition_deadline - time.monotonic()
                    assert remaining > 0, 'Owner did not acquire queued fence'
                    record = line(server, timeout=remaining)
                    if record.startswith('owner responsive '): break
                    assert record == 'presented 6', record
                if record == 'owner responsive frames=6 pendingFence=7': break
                assert record == 'owner responsive frames=6 pendingFence=0', record
                time.sleep(.01)
            end = time.monotonic() + .15
            with selectors.DefaultSelector() as selector:
                selector.register(server.stdout, selectors.EVENT_READ)
                while time.monotonic() < end and selector.select(end - time.monotonic()):
                    assert line(server) == 'presented 6', 'Unsignaled frame was presented'
            result['delayed_fence'] = dict(pending_frame=7, presented_before_release=6,
                                          owner_responsive=True, withheld_millis=150)
            if a.scenario in ('fence-shutdown', 'fence-timeout'):
                shutdown_started = time.monotonic()
                if a.scenario == 'fence-shutdown':
                    server.stdin.close()
                    expected = 'released all buffers'
                else:
                    expected = 'released all buffers after failure'
                # A wake can publish an unchanged presentation count before EOF.
                while True:
                    record = line(server, timeout=2)
                    if record == expected: break
                    assert record == 'presented 6', record
                status = server.wait(timeout=2)
                elapsed = time.monotonic() - shutdown_started
                if a.scenario == 'fence-shutdown':
                    assert status == 0 and elapsed < .5, (status, elapsed)
                else:
                    assert status != 0 and .75 <= time.monotonic() - pending_started < 1.8
                    assert 'surface queue acquire fence failed: -110' in (out / 'server.log').read_text()
                result['live_buffers_after_shutdown'] = 0
                result['fence_retirement'] = dict(exit=status, elapsed_millis=round(elapsed * 1000),
                                                 gpu_still_pending=True)
                # Release GPU work even on the timeout path; the producer then
                # verifies the queue's dead owner through its stock proxy.
                producer.stdin.write(b'release\n')
                assert line(producer) == 'PASS fence consumer loss fails further producer calls'
                assert producer.wait(timeout=2) == 0
                early = True
                break
            producer.stdin.write(b'release\n')
            value = line(producer)
        assert value == f'queued {frame}'
    if not early:
        assert not pressure or result.get('backpressure')
        assert line(producer) == 'READY_SCREENSHOT'
        while presented < 12:
            value = line(server)
            assert value.startswith('presented ')
            presented = int(value.split()[1])
        if a.scenario.startswith('pointer-capture'):
            assert line(server) == ('pointer capture unavailable passed' if a.scenario.endswith('unavailable') else 'pointer capture passed')
            result['pointer_capture'] = 'unsupported without state/objects' if a.scenario.endswith('unavailable') else 'compositor lock, raw relative motion and unlock acknowledged'
        time.sleep(0.2)
        subprocess.run(['grim', str(out / 'screenshot.png')], check=True)
        ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'])
        magic, dimensions, maximum, pixels = ppm.split(b'\n', 3)
        assert magic == b'P6' and maximum == b'255'
        width, height = map(int, dimensions.split())
        center_x, center_y = width // 2, height // 2
        if a.scenario.endswith('-offset'):
            center_x += 32 - 80
            center_y += 24 - 60
        offset = (center_y * width + center_x) * 3
        actual = tuple(pixels[offset:offset + 3])
        assert all(abs(x - y) <= 2 for x, y in zip(actual, (171, 120, 52))), actual
        result.update(presented=presented, screenshot_size=[width, height], center_rgb=actual)
        if a.scenario.startswith('drag-icon'):
            # Twelve resized producer buffers must retain one logical icon and
            # one hotspot offset. Reapplying the offset per commit drifts it.
            def pixel(x, y):
                return tuple(pixels[(y * width + x) * 3:(y * width + x) * 3 + 3])
            hotspot = [80, 60] if a.scenario.endswith('-offset') else [12, 9]
            left, top = width // 2 - hotspot[0], height // 2 - hotspot[1]
            for x, y in ((left + 1, top + 1), (left + 62, top + 46)):
                assert all(abs(a-b) <= 2 for a, b in zip(pixel(x, y), (171, 120, 52))), (x, y, pixel(x, y))
            for x, y in ((left - 2, top + 24), (left + 65, top + 24),
                         (left + 32, top - 2), (left + 32, top + 49)):
                assert pixel(x, y) == (0, 0, 0), (x, y, pixel(x, y))
            result['drag_icon'] = dict(size=[64, 48], hotspot=hotspot, frames=12)

        if a.scenario == 'cursor-animation':
            assert line(server) == 'cursor animation ready'
            colors = set()
            deadline = time.monotonic() + 3
            while len(colors) < 2 and time.monotonic() < deadline:
                ppm = subprocess.check_output(['grim', '-c', '-t', 'ppm', '-'], timeout=3)
                _, dimensions, _, data = ppm.split(b'\n', 3)
                w, h = map(int, dimensions.split())
                x, y = w // 2 - 4 + 16, h // 2 - 5 + 16
                color = tuple(data[(y * w + x) * 3:(y * w + x) * 3 + 3])
                if color in ((255, 0, 0), (0, 255, 0)):
                    colors.add(color)
                time.sleep(.03)
            assert colors == {(255, 0, 0), (0, 255, 0)}, colors
            subprocess.run(['grim', '-c', str(out / 'cursor.png')], check=True, timeout=3)
            result['animated_cursor_colors'] = sorted(colors)
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
            if paced:
                record = line(server)
                while record == 'presented 12': record = line(server)
                match = re.fullmatch(r'paced submissions=(\d+) deferred=(\d+) coalesced=(\d+) callbacks=(\d+)', record)
                assert match, 'Missing pacing counters'
                result['pacing'] = dict(zip(['submissions', 'deferred', 'coalesced', 'callbacks'], map(int, match.groups())))
                counts = result['pacing']
                assert counts['submissions'] + counts['coalesced'] == 12 and counts['deferred'] > 0
                assert counts['submissions'] <= counts['callbacks'] + 1
                match = re.fullmatch(r'feedback supported=(true|false) observed=(\d+) discarded=(\d+) unobserved=(\d+) frame=(\d+) clock=(\d+) seconds=(\d+) nanoseconds=(\d+) refresh_ns=(\d+)', line(server))
                assert match, 'Missing presentation observations'
                supported = match[1] == 'true'
                timing = dict(zip(['observed', 'discarded', 'unobserved', 'frame', 'clock', 'seconds', 'nanoseconds', 'refresh_ns'], map(int, match.groups()[1:])))
                result['presentation_feedback'] = dict(supported=supported, **timing)
                assert supported == (a.scenario == 'paced')
                if supported:
                    assert timing['frame'] == 12 and timing['seconds'] > 0 and timing['nanoseconds'] < 1_000_000_000
                    assert timing['observed'] + timing['discarded'] + timing['unobserved'] == counts['submissions']
                else:
                    assert not any(timing.values()), 'Fallback fabricated actual presentation timing'
            assert line(server) == 'released all buffers'
            assert server.wait(timeout=5) == 0
            result['live_buffers_after_shutdown'] = 0
    if a.scenario != 'broker-loss':
        broker.stdin.close()
        assert broker.wait(timeout=5) == 0
    frames = re.findall(r'surface-frame number=(\d+) buffer=(\d+) width=(\d+) height=(\d+)',
                        (out / 'server.log').read_text())
    expected_frames = result['pacing']['submissions'] if paced else 6 if early else 12
    assert len(frames) == expected_frames, frames
    numbers = [int(x[0]) for x in frames]
    if paced:
        assert numbers == sorted(set(numbers)) and numbers[-1] == 12
    else:
        assert numbers == list(range(1, expected_frames + 1))
    assert [tuple(map(int, x[2:])) for x in frames] == [(320, 240) if n <= 6 else (480, 360) for n in numbers]
    result['frames'] = frames
    if delayed:
        result['gpu_device'] = re.search(r'GPU fence device: (.+)', (out / 'producer.log').read_text()).group(1)
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
