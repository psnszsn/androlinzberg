#!/usr/bin/env python3
"""Stock APK -> system organizer -> SF -> AZKB queue -> Wayland pixel gate.

tools/shot.sh owns Cage; this runner owns only the application session.
"""
import argparse
import json
import os
from pathlib import Path
import resource
import re
import select
import subprocess
import time
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--launcher', action='store_true')
parser.add_argument('--testkit', type=Path)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--seat', type=Path, required=True)
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
for artifact in ('result.json', 'control-failure.txt', 'failure.json', 'a11y-before.json', 'a11y.json'):
    (out / artifact).unlink(missing_ok=True)
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
command = args.command[1:] if args.command[0] == '--' else args.command
result = {'passed': False}
desktop = os.environ.get('SHOT_COMPOSITOR') == 'labwc'
with tempfile.TemporaryDirectory(prefix='azk-task-control-', dir='/tmp') as control_dir, (out / 'app.log').open('wb') as log:
    environment = os.environ.copy()
    if args.launcher:
        assert args.testkit is not None
        environment.update(AZK_TEST_SOCKET=str(Path(control_dir) / 'control.sock'),
                           AZK_TESTKIT_ROOT=str(args.testkit.resolve()),
                           AZK_TEST_RAW_LOG=str(out / 'app.log'),
                           AZK_TEST_STEP_LOG=str(out / 'steps.jsonl'))
        (out / 'steps.jsonl').write_text('')
        # launcher-desktop.xml deliberately places this fixture at output origin.
        # Supply that harness knowledge explicitly; Android cannot discover it.
        initial_ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
        output_width, output_height = map(int, initial_ppm.split(b'\n', 3)[1].split())
        environment['AZK_TEST_WINDOW_PLACEMENT'] = f'0,0,{output_width},{output_height}'

    def control(*arguments):
        completed = subprocess.run([str(Path(__file__).resolve().parents[2] / 'tools/azk-test'), *arguments],
                                   env=environment, capture_output=True, text=True, timeout=30)
        if completed.returncode != 0:
            (out / 'control-failure.txt').write_text(completed.stdout + completed.stderr)
            raise RuntimeError(f'azk-test {arguments}: {completed.returncode}: {completed.stderr}')
        return json.loads(completed.stdout)

    with subprocess.Popen([str(args.seat.resolve()), '--keyboard-seat'],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log) as seat:
        if not select.select([seat.stdout], [], [], 10)[0]:
            seat.kill()
            raise RuntimeError('virtual keyboard startup timed out')
        if seat.stdout.readline() != b'ready\n':
            raise RuntimeError('virtual keyboard startup failed')
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=log, env=environment)
        try:
            def verify_pixels(marker, center, name):
                deadline = time.monotonic() + 60
                while marker not in (out / 'app.log').read_text(errors='replace'):
                    if process.poll() is not None:
                        raise RuntimeError(f'session exited before {marker}: {process.returncode}')
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f'{marker} timeout')
                    time.sleep(0.05)
                deadline = time.monotonic() + 15
                while True:
                    ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
                    magic, dimensions, maximum, pixels = ppm.split(b'\n', 3)
                    assert magic == b'P6' and maximum == b'255'
                    width, height = map(int, dimensions.split())
                    regions = [(0, 0, width, height, center)]
                    if desktop:
                        regions = [(30, 30, 440, 300, (0, 255, 255))]
                        if name in ('second-task', 'grouped-activity'):
                            regions.append((500, 100, 440, 300, center))
                    points, expected = [], []
                    for left, top, w, h, color in regions:
                        inset = min(w, h) // 16
                        points += [(left + x, top + y) for x, y in [
                            (w // 2, h // 2), (inset, inset), (w - inset - 1, inset),
                            (inset, h - inset - 1), (w - inset - 1, h - inset - 1)]]
                        expected += [color, (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]
                    actual = [tuple(pixels[(y * width + x) * 3:(y * width + x) * 3 + 3]) for x, y in points]
                    if all(all(abs(a - b) <= 2 for a, b in zip(pixel, color)) for pixel, color in zip(actual, expected)):
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f'{name} compositor colors {actual}, expected {expected}')
                    time.sleep(0.05)
                subprocess.run(['grim', str(out / f'{name}.png')], check=True, timeout=3)
                result[name] = dict(size=[width, height], colors=actual)
                process.stdin.write(b'continue\n')
                process.stdin.flush()

            if args.launcher:
                deadline = time.monotonic() + 60
                while True:
                    text = (out / 'app.log').read_text(errors='replace')
                    if 'Desktop launch accepted: dev.artzig.fixtures.windows' in text and 'Displayed dev.artzig.fixtures.windows/' in text:
                        break
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError(f'desktop APK launch failed: {process.poll()}')
                    time.sleep(0.05)
                app_pid = int(re.search(r'\d\d-\d\d \S+ (\d+) +\d+ I WindowFixture:', text).group(1))
                assert app_pid != process.pid, 'launcher attached as the APK'
                deadline = time.monotonic() + 15
                while True:
                    ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
                    magic, dimensions, maximum, pixels = ppm.split(b'\n', 3)
                    assert magic == b'P6' and maximum == b'255'
                    width, height = map(int, dimensions.split())
                    samples = [tuple(pixels[(y * width + x) * 3:(y * width + x) * 3 + 3])
                               for y in range(20, 460, 5) for x in range(20, 620, 5)]
                    if sum(min(pixel) > 220 for pixel in samples) > 500 and len(set(samples)) > 10:
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError('launcher fixture did not reach compositor pixels')
                    time.sleep(0.05)
                subprocess.run(['grim', str(out / 'screenshot.png')], check=True, timeout=3)
                result['launcher'] = {'pid': process.pid, 'app_pid': app_pid, 'size': [width, height]}
                control('wait', 'ready')
                control('wait', 'idle')
                observed = control('observe', '--tree')['observation']
                tree = observed['tree']
                (out / 'a11y-before.json').write_text(json.dumps(observed, indent=2))
                windows = tree['windows']
                assert len(windows) == 1 and windows[0]['pid'] == app_pid
                task = str(windows[0]['task_id'])
                window = str(windows[0]['id'])
                def label(value):
                    return next(node['text'] for node in tree['nodes'] if (node.get('text') or '').lower() == value.lower())
                control('step', 'click', '--task-id', task, '--window-id', window,
                        '--text', label('Increment counter'), '--via', 'semantic', '--settle', 'idle')
                control('wait', 'exists', '--task-id', task, '--text', 'Counter: 1')
                control('step', 'click', '--task-id', task, '--text', label('Show attached dialog'),
                        '--via', 'semantic', '--settle', 'idle')
                dialog = control('observe', '--tree')['observation']['tree']
                assert len(dialog['windows']) == 2
                assert {str(w['task_id']) for w in dialog['windows']} == {task}
                close = next(node for node in dialog['nodes'] if (node.get('text') or '').lower() == 'close dialog')
                control('step', 'click', '--window-id', str(close['window_id']), '--text', close['text'],
                        '--via', 'semantic', '--settle', 'idle')
                control('step', 'click', '--task-id', task, '--text', label('Increment counter'),
                        '--via', 'wayland', '--settle', 'idle')
                control('wait', 'exists', '--task-id', task, '--text', 'Counter: 2')
                control('step', 'click', '--task-id', task, '--description', 'Fixture editor',
                        '--via', 'wayland', '--settle', 'idle')
                control('step', 'type', '--task-id', task, '--description', 'Fixture editor', 'abc',
                        '--via', 'wayland', '--settle', 'idle')
                control('step', 'key', '--task-id', task, '--description', 'Fixture editor', 'Backspace',
                        '--via', 'wayland', '--settle', 'idle')
                control('wait', 'exists', '--task-id', task, '--description', 'Fixture editor', '--text', 'ab')
                after = control('observe', '--tree')['observation']
                assert len(after['tree']['windows']) == 1
                assert after['mark']['presented_frame_seq'] > observed['mark']['presented_frame_seq']
                (out / 'a11y.json').write_text(json.dumps(after, indent=2))
                steps = [json.loads(line) for line in (out / 'steps.jsonl').read_text().splitlines()]
                assert len(steps) == 7 and all(not step['diagnostics']['runtime']['issues'] for step in steps)
                result['test_control'] = {'steps': len(steps), 'task': int(task), 'window': int(window)}
                subprocess.run(['grim', str(out / 'screenshot.png')], check=True, timeout=3)
                seat.stdin.write(b'c')
                seat.stdin.flush()
                if process.wait(timeout=30) != 0:
                    raise RuntimeError(f'desktop session did not close successfully: {process.returncode}')
                text = (out / 'app.log').read_text(errors='replace')
            else:
                verify_pixels('TASK_WINDOW_READY', (0, 255, 255), 'first-task')
                verify_pixels('SECOND_TASK_WINDOW_READY', (255, 0, 255), 'second-task')
                verify_pixels('GROUPED_ACTIVITY_WINDOW_READY', (255, 127, 0), 'grouped-activity')
                verify_pixels('SURVIVING_TASK_WINDOW_READY', (0, 255, 255), 'screenshot')
                if process.wait(timeout=30) != 0:
                    raise RuntimeError(f'session failed: {process.returncode}')
                text = (out / 'app.log').read_text(errors='replace')
                assert 'Stock Wayland task: two tasks, grouped activities, one synthetic display, independent pixels and removal passed' in text
            assert 'task presenter released all buffers' in text
            for fatal in ['Native thread exited without calling DetachCurrentThread',
                          'FATAL EXCEPTION', 'Unhandled exception', 'panic:']:
                assert fatal not in text, fatal
            result['passed'] = True
        except BaseException as error:
            result['error'] = str(error)
            if args.launcher:
                try:
                    (out / 'failure.json').write_text(json.dumps(control('diagnose'), indent=2))
                except Exception:
                    pass
            subprocess.run(['grim', str(out / 'screenshot.png')], timeout=3, check=False)
            raise
        finally:
            if process.poll() is None:
                # Release the driver's pixel gate and let Session.stop preserve
                # system -> presenter -> SF -> broker shutdown order on failure.
                process.stdin.close()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
