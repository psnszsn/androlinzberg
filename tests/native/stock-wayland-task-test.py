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
parser.add_argument('--video', action='store_true')
parser.add_argument('--ime', action='store_true')
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
mixed_outputs = os.environ.get('AZK_TEST_MIXED_OUTPUTS') == '1'
second_output, second_scale = 'HEADLESS-2', 1
with tempfile.TemporaryDirectory(prefix='azk-task-control-', dir='/tmp') as control_dir, (out / 'app.log').open('wb') as log:
    environment = os.environ.copy()
    if desktop and not args.launcher and not mixed_outputs:
        environment['AZK_TEST_DESKTOP_CONFIGURATIONS'] = '1'
    if mixed_outputs:
        subprocess.run(['wlr-randr', '--output', 'HEADLESS-1', '--pos', '0,0', '--scale', '1',
                        '--output', 'HEADLESS-2', '--pos', '1280,0', '--scale', '1'],
                       check=True, capture_output=True, timeout=5)
    if args.launcher:
        assert args.testkit is not None
        # Exercise the v1-only creation request against the real compositor.
        # Physical pointer steps below use the presenter's virtual pointer.
        environment['AZK_TEST_VIRTUAL_POINTER_VERSION'] = '1'
        environment['AZK_TEST_CURSOR_SHAPE'] = '0'

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
            def output_pixels(output_name, color, size):
                # Discover placement only inside the compositor screenshot;
                # these coordinates never enter Android task geometry.
                ppm = subprocess.check_output(['grim', '-o', output_name, '-t', 'ppm', '-'], timeout=3)
                magic, dimensions, maximum, data = ppm.split(b'\n', 3)
                assert magic == b'P6' and maximum == b'255'
                width, height = map(int, dimensions.split())
                needle = bytes(color)
                rows = []
                for y in range(height):
                    row = data[y * width * 3:(y + 1) * width * 3]
                    left = row.find(needle)
                    while left >= 0 and left % 3: left = row.find(needle, left + 1)
                    right = row.rfind(needle)
                    while right >= 0 and right % 3: right = row.rfind(needle, 0, right + 2)
                    if left >= 0 and right >= left: rows.append((y, left // 3, right // 3))
                if not rows: return None
                left, top = min(r[1] for r in rows), rows[0][0]
                w, h = max(r[2] for r in rows) - left + 1, rows[-1][0] - top + 1
                if (w, h) != size: return None
                inset = min(w, h) // 16
                points = [(w // 2, h // 2), (inset, inset), (w - inset - 1, inset),
                          (inset, h - inset - 1), (w - inset - 1, h - inset - 1)]
                actual = [tuple(data[((top + y) * width + left + x) * 3:
                                     ((top + y) * width + left + x) * 3 + 3]) for x, y in points]
                expected = [color, (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]
                if not all(all(abs(a - b) <= 2 for a, b in zip(pixel, want))
                           for pixel, want in zip(actual, expected)): return None
                return dict(output=output_name, output_size=[width, height],
                            compositor_bounds=[left, top, w, h], colors=actual)

            def verify_pixels(marker, center, name, fullscreen=False, scale=1):
                deadline = time.monotonic() + 60
                while marker not in (out / 'app.log').read_text(errors='replace'):
                    if process.poll() is not None:
                        raise RuntimeError(f'session exited before {marker}: {process.returncode}')
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f'{marker} timeout')
                    time.sleep(0.05)
                deadline = time.monotonic() + 15
                if mixed_outputs:
                    sibling = name in ('second-task', 'grouped-activity', 'mixed-scaled', 'mixed-unplugged')
                    wanted = []
                    # Evacuation placement belongs to the compositor and may
                    # overlap the first task; verify it fully again after removal.
                    if not sibling or second_output != 'HEADLESS-1':
                        wanted.append(('HEADLESS-1', (0, 255, 255), (440, 300)))
                    if sibling:
                        wanted.append((second_output, center, (int(440 * second_scale), int(300 * second_scale))))
                    while True:
                        states = [output_pixels(*spec) for spec in wanted]
                        if all(states): break
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError(f'{name} output pixels did not match {wanted}: {states}; exit={process.poll()}')
                        time.sleep(.05)
                    result[name] = states
                    for state in states:
                        subprocess.run(['grim', '-o', state['output'], str(out / f"{name}-{state['output']}.png")],
                                       check=True, timeout=3)
                    process.stdin.write(b'continue\n')
                    process.stdin.flush()
                    return
                while True:
                    ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
                    magic, dimensions, maximum, pixels = ppm.split(b'\n', 3)
                    assert magic == b'P6' and maximum == b'255'
                    width, height = map(int, dimensions.split())
                    regions = [(0, 0, width, height, center)]
                    if desktop and not fullscreen:
                        regions = [(int(30 * scale), int(30 * scale), int(440 * scale), int(300 * scale), (0, 255, 255))]
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

            def verify_tablet_pixels():
                for marker, name, x, y, color in [
                        ('TABLET_PEN_RENDERED', 'tablet-pen', 550, 160, (255, 255, 255)),
                        ('TABLET_ERASER_RENDERED', 'tablet-eraser', 570, 180, (0, 0, 0))]:
                    deadline = time.monotonic() + 20
                    while marker not in (out / 'app.log').read_text(errors='replace'):
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError(f'{marker} timeout/exit: {process.poll()}')
                        time.sleep(.02)
                    while True:
                        ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
                        _, dimensions, _, data = ppm.split(b'\n', 3)
                        width, height = map(int, dimensions.split())
                        actual = tuple(data[(y * width + x) * 3:(y * width + x) * 3 + 3])
                        if actual == color:
                            break
                        if time.monotonic() >= deadline:
                            raise RuntimeError(f'{name} pixel {actual}, expected {color}')
                        time.sleep(.02)
                    subprocess.run(['grim', str(out / f'{name}.png')], check=True, timeout=3)
                    # The source fixture accepts the tablet tool's set_cursor,
                    # and moves the compositor cursor to the surface-local point.
                    cursor_color = (0, 255, 0) if name == 'tablet-pen' else (0, 0, 255)
                    while True:
                        ppm = subprocess.check_output(['grim', '-c', '-t', 'ppm', '-'], timeout=3)
                        _, dimensions, _, data = ppm.split(b'\n', 3)
                        width, height = map(int, dimensions.split())
                        def pixel(px, py):
                            return tuple(data[(py * width + px) * 3:(py * width + px) * 3 + 3])
                        # (2,3) hotspot gives this exact 16x16 immutable bitmap.
                        correct = all(pixel(px, py) == cursor_color for px, py in
                            [(x - 2, y - 3), (x + 13, y - 3), (x - 2, y + 12), (x + 13, y + 12)])
                        if correct: break
                        if time.monotonic() >= deadline:
                            raise RuntimeError(f'{name} tablet cursor/hotspot not rendered')
                        time.sleep(.02)
                    subprocess.run(['grim', '-c', str(out / f'{name}-cursor.png')], check=True, timeout=3)
                    result[name] = dict(point=[x, y], color=actual, cursor=cursor_color, hotspot=[2, 3])
                    process.stdin.write(b'continue\n')
                    process.stdin.flush()

            def change_pointer(marker, operation):
                deadline = time.monotonic() + 15
                while marker not in (out / 'app.log').read_text(errors='replace'):
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError(f'{marker} timeout/exit: {process.poll()}')
                    time.sleep(0.02)
                seat.stdin.write(operation)
                seat.stdin.flush()
                if not select.select([seat.stdout], [], [], 3)[0]:
                    raise RuntimeError(f'{marker} compositor device command failed')
                state = seat.stdout.readline().decode().strip().split()
                if len(state) != 3 or state[:2] != ['pointer', 'updated']:
                    raise RuntimeError(f'{marker} invalid capability acknowledgement: {state}')
                mask = int(state[2])
                result.setdefault('seat_capabilities', {})[marker] = mask
                process.stdin.write(f'{mask}\n'.encode())
                process.stdin.flush()

            def physical_pointer(marker, operation):
                deadline = time.monotonic() + 15
                while marker not in (out / 'app.log').read_text(errors='replace'):
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError(f'{marker} timeout/exit: {process.poll()}')
                    time.sleep(.02)
                seat.stdin.write(operation)
                seat.stdin.flush()
                if not select.select([seat.stdout], [], [], 3)[0]:
                    raise RuntimeError(f'{marker} pointer input command timed out')
                assert seat.stdout.readline() == b'pointer input sent\n'
                process.stdin.write(b'input sent\n')
                process.stdin.flush()

            def suspend_desktop():
                protocol_version = int(re.search(r'task xdg shell protocol version=(\d+)',
                    (out / 'app.log').read_text(errors='replace')).group(1))
                expected_hint = environment.get('AZK_TEST_SUSPENSION_EXPECTED') == '1'
                assert protocol_version == 2 if environment.get('AZK_TEST_XDG_SHELL_VERSION') == '2' else protocol_version >= 6
                for marker in ('SUSPEND_DESKTOP_READY', 'SUSPENDED_TASK_READY'):
                    deadline = time.monotonic() + 15
                    while marker not in (out / 'app.log').read_text(errors='replace'):
                        if process.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError(f'{marker} timeout/exit: {process.poll()}')
                        time.sleep(.02)
                    if marker == 'SUSPENDED_TASK_READY':
                        ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
                        pixels = ppm.split(b'\n', 3)[3]
                        assert not any(pixels[i:i+3] == b'\x00\xff\xff' for i in range(0, len(pixels), 3)), 'Minimized task remains visible'
                        subprocess.run(['grim', str(out / 'suspended.png')], check=True, timeout=3)
                    seat.stdin.write(b'h')
                    seat.stdin.flush()
                    if not select.select([seat.stdout], [], [], 3)[0]:
                        raise RuntimeError(f'{marker} compositor command timed out')
                    assert seat.stdout.readline() == b'compositor state changed\n'
                    process.stdin.write((b'hint\n' if expected_hint else b'unknown\n') if marker == 'SUSPEND_DESKTOP_READY' else b'toggled\n')
                    process.stdin.flush()
                result['suspension'] = dict(hint=expected_hint, protocol_version=protocol_version)
                verify_pixels('RESUMED_TASK_WINDOW_READY', (0, 255, 255), 'resumed')

            def change_keyboard(marker, operation):
                deadline = time.monotonic() + 15
                while marker not in (out / 'app.log').read_text(errors='replace'):
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError(f'{marker} timeout/exit: {process.poll()}')
                    time.sleep(0.02)
                seat.stdin.write(operation)
                seat.stdin.flush()
                if not select.select([seat.stdout], [], [], 3)[0]:
                    raise RuntimeError(f'{marker} compositor keymap command failed')
                state = seat.stdout.readline()
                if state != b'keyboard updated\n':
                    raise RuntimeError(f'{marker} invalid keymap acknowledgement: {state}')
                process.stdin.write(state)
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
                app_pid = int(re.search(r'\d\d-\d\d \S+\s+(\d+)\s+\d+ I WindowFixture:', text).group(1))
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
                def settled_text(value, description=None, task_id=None):
                    # Observe immediately: a subsequent wait would conceal a
                    # step that settled before its input reached the app.
                    current = control('observe', '--tree')['observation']['tree']
                    assert any(node.get('text') == value and str(node.get('task_id')) == (task_id or task)
                               and (description is None or node.get('content_description') == description)
                               for node in current['nodes']), (value, current)
                if args.ime:
                    ime_mark = control('mark')['mark']
                    control('step', 'click', '--task-id', task, '--text', label('Show IME editor'),
                            '--via', 'semantic', '--settle', 'idle')
                    control('wait', 'exists', '--text', 'IME text=a😀中z select=3:3 composing=-1:-1')
                    for action, expected in [
                        ('COMPOSE UNICODE', 'IME text=a😀é拼中z select=4:4 composing=3:5'),
                        ('HIDE PREEDIT CARET', 'IME text=a😀候中z select=4:4 composing=3:4 caret=hidden'),
                        ('COMMIT UNICODE', 'IME text=a😀文中z select=4:4 composing=-1:-1'),
                        ('DELETE UNICODE', 'IME text=a😀éz select=4:4 composing=-1:-1'),
                        ('FOCUS PASSWORD', 'Password length=14'),
                        ('VERIFY PASSWORD CONTEXT', 'Password length=15'),
                        ('FOCUS ORIGINAL EDITOR', 'IME text=a😀éz select=4:4 composing=-1:-1'),
                        ('COMPOSE THEN LEAVE', 'IME text=a😀éé拼z select=5:5 composing=4:6'),
                        ('REMOVE TEXT FOCUS', 'IME text=a😀éz select=4:4 composing=-1:-1'),
                        ('RESTORE TEXT FOCUS', 'IME text=a😀éz select=4:4 composing=-1:-1'),
                        ('COMPOSE THEN LEAVE', 'IME text=a😀éé拼z select=5:5 composing=4:6'),
                        ('FOCUS PASSWORD', 'Password length=15'),
                        # Android closes the old InputConnection, finishing its
                        # composition while retaining text and cursor position.
                        ('FOCUS ORIGINAL EDITOR', 'IME text=a😀éé拼z select=5:5 composing=-1:-1'),
                        ('INVALIDATE EDITOR', 'IME text=a😀éé拼z select=5:5 composing=-1:-1'),
                        ('COMPOSE UNICODE', 'IME text=a😀ééé拼拼z select=6:6 composing=5:7'),
                        ('COMMIT UNICODE', 'IME text=a😀éé文拼z select=6:6 composing=-1:-1'),
                    ]:
                        control('step', 'click', '--task-id', task, '--text', action,
                                '--via', 'semantic', '--settle', 'idle')
                        control('wait', 'exists', '--task-id', task, '--text', expected)
                    control('step', 'key', '--task-id', task, '--description', 'IME Unicode editor',
                            'Enter', '--via', 'wayland', '--settle', 'idle')
                    control('wait', 'exists', '--task-id', task, '--text', 'Editor submitted: 1')
                    control('step', 'click', '--task-id', task, '--text', 'REMOVE IME PROTOCOL',
                            '--via', 'semantic', '--settle', 'idle')
                    control('step', 'type', '--task-id', task, '--description', 'IME Unicode editor',
                            'X', '--via', 'wayland', '--settle', 'idle')
                    # Physical type first taps the selected editor; its center
                    # lies after this short text, placing the caret at the end.
                    settled_text('a😀éé文拼zX', 'IME Unicode editor')
                    control('step', 'click', '--task-id', task, '--text', 'RESTORE IME PROTOCOL',
                            '--via', 'semantic', '--settle', 'idle')
                    control('step', 'click', '--task-id', task, '--text', 'COMMIT UNICODE',
                            '--via', 'semantic', '--settle', 'idle')
                    control('wait', 'exists', '--task-id', task, '--text', 'IME text=a😀éé文拼zX文 select=10:10 composing=-1:-1')
                    protocol_log = (out / 'app.log').read_text(errors='replace')
                    assert 'task text input available=false' in protocol_log
                    assert 'task text input available=true' in protocol_log
                    diagnostics = control('diagnose', '--since', json.dumps(ime_mark))
                    (out / 'ime-diagnostics.json').write_text(json.dumps(diagnostics, indent=2))
                    assert not diagnostics['diagnostics']['runtime']['issues'], diagnostics
                    steps = [json.loads(line) for line in (out / 'steps.jsonl').read_text().splitlines()]
                    assert len(steps) == 22 and all(not step['diagnostics']['runtime']['issues'] for step in steps)
                    result['test_control'] = {'steps': len(steps), 'task': int(task)}
                elif args.video:
                    video_mark = control('mark')['mark']
                    control('step', 'click', '--task-id', task, '--text', label('Show decoded video'),
                            '--via', 'semantic', '--settle', 'idle')
                    control('wait', 'exists', '--task-id', task, '--text', 'Surface video queued')

                    def video_pixels(name, minimum_cyan, require_overlay=False):
                        # This harness places the one task at output origin, at scale 1.
                        # Restrict each check to its own Android view, so an old SurfaceView
                        # image cannot satisfy the TextureView screenshot assertion.
                        description = 'Decoded SurfaceView' if require_overlay else 'Decoded TextureView'
                        nodes = control('observe', '--tree')['observation']['tree']['nodes']
                        bounds = next(n['bounds'] for n in nodes if n.get('content_description') == description
                                      and str(n.get('task_id')) == task)
                        deadline = time.monotonic() + 10
                        while True:
                            ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
                            magic, dimensions, maximum, data = ppm.split(b'\n', 3)
                            w, h = map(int, dimensions.split())
                            data = b''.join(data[(y * w + bounds['left']) * 3:(y * w + bounds['right']) * 3]
                                            for y in range(bounds['top'], bounds['bottom']))
                            cyan = sum(r < 12 and g > 243 and b > 243 for r, g, b in zip(data[0::3], data[1::3], data[2::3]))
                            red = sum(r > 243 and g < 12 and b < 12 for r, g, b in zip(data[0::3], data[1::3], data[2::3]))
                            if cyan >= minimum_cyan and (not require_overlay or red >= 900): break
                            if time.monotonic() >= deadline:
                                raise RuntimeError(f'{name}: cyan={cyan}, red={red}, required={minimum_cyan}')
                            time.sleep(.05)
                        subprocess.run(['grim', str(out / f'{name}.png')], check=True, timeout=3)
                        result[name] = {'cyan_pixels': cyan, 'red_pixels': red}

                    video_pixels('surface-video', 25000, True)
                    for action, expected, area in [
                        ('MOVE VIDEO TO TEXTURE', 'Texture video pixels verified', 25000),
                        ('RESIZE VIDEO', 'Resized video pixels verified', 45000),
                        ('REMOVE VIDEO', 'Video surfaces released', 0),
                    ]:
                        control('step', 'click', '--task-id', task, '--text', action,
                                '--via', 'semantic', '--settle', 'idle')
                        control('wait', 'exists', '--task-id', task, '--text', expected)
                        if area: video_pixels(expected.split()[0].lower() + '-video', area)
                    diagnostics = control('diagnose', '--since', json.dumps(video_mark))
                    (out / 'video-diagnostics.json').write_text(json.dumps(diagnostics, indent=2))
                    assert not diagnostics['diagnostics']['runtime']['issues'], diagnostics
                    steps = [json.loads(line) for line in (out / 'steps.jsonl').read_text().splitlines()]
                    assert len(steps) == 4 and all(not step['diagnostics']['runtime']['issues'] for step in steps)
                    assert 'GPU VPP RGB path active' in (out / 'app.log').read_text(errors='replace')
                    result['test_control'] = {'steps': len(steps), 'task': int(task), 'window': int(window)}
                else:
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
                    settled_text('Counter: 2')
                    # The semantic target and explicit origin placement determine
                    # this cursor position; no global desktop coordinates are inferred.
                    bounds = next(node['bounds'] for node in tree['nodes']
                                  if (node.get('text') or '').lower() == 'increment counter')
                    cursor_x = (bounds['left'] + bounds['right']) // 2 - 2
                    cursor_y = (bounds['top'] + bounds['bottom']) // 2 - 3
                    deadline = time.monotonic() + 10
                    while True:
                        ppm = subprocess.check_output(['grim', '-c', '-t', 'ppm', '-'], timeout=3)
                        _, dimensions, _, rgba = ppm.split(b'\n', 3)
                        cursor_width, _ = map(int, dimensions.split())
                        colors = [tuple(rgba[((cursor_y + 8) * cursor_width + cursor_x + x) * 3:
                                             ((cursor_y + 8) * cursor_width + cursor_x + x) * 3 + 3])
                                  for x in (8, 24)]
                        if colors == [(255, 0, 255), (0, 255, 255)]:
                            break
                        if time.monotonic() >= deadline:
                            raise RuntimeError(f'Custom Android cursor pixels/hotspot differ: {colors}')
                        time.sleep(.02)
                    subprocess.run(['grim', '-c', str(out / 'custom-cursor.png')], check=True, timeout=3)
                    result['custom_cursor'] = dict(hotspot=[2, 3], colors=colors)
                    control('step', 'click', '--task-id', task, '--description', 'Fixture editor',
                            '--via', 'wayland', '--settle', 'idle')
                    control('step', 'type', '--task-id', task, '--description', 'Fixture editor', 'abc',
                            '--via', 'wayland', '--settle', 'idle')
                    settled_text('abc', 'Fixture editor')
                    control('step', 'key', '--task-id', task, '--description', 'Fixture editor', 'Backspace',
                            '--via', 'wayland', '--settle', 'idle')
                    settled_text('ab', 'Fixture editor')
                    long_text = 'A' * 1024
                    control('step', 'type', '--task-id', task, '--description', 'Fixture editor', long_text,
                            '--via', 'wayland', '--settle', 'idle')
                    settled_text('ab' + long_text, 'Fixture editor')
                    control('step', 'type', '--task-id', task, '--description', 'Fixture editor', 'ab',
                            '--via', 'semantic', '--settle', 'idle')
                    settled_text('ab', 'Fixture editor')
                    control('step', 'click', '--task-id', task, '--text', label('Open independent task'),
                            '--via', 'semantic', '--settle', 'idle')
                    multiple = control('observe', '--tree')['observation']['tree']
                    assert len(multiple['windows']) == 2, multiple
                    sibling = next(window for window in multiple['windows'] if str(window['task_id']) != task)
                    assert sibling['pid'] == app_pid
                    sibling_task = str(sibling['task_id'])
                    control('step', 'type', '--task-id', sibling_task, '--description', 'Fixture editor', 'Z',
                            '--via', 'wayland', '--settle', 'idle')
                    settled_text('Z', 'Fixture editor', sibling_task)
                    settled_text('ab', 'Fixture editor')
                    control('step', 'click', '--task-id', sibling_task, '--text', label('Finish this activity'),
                            '--via', 'semantic', '--settle', 'idle')
                    control('step', 'type', '--task-id', task, '--description', 'Fixture editor', 'C',
                            '--via', 'wayland', '--settle', 'idle')
                    settled_text('abC', 'Fixture editor')
                    after = control('observe', '--tree')['observation']
                    assert len(after['tree']['windows']) == 1
                    assert after['mark']['presented_frame_seq'] > observed['mark']['presented_frame_seq']
                    (out / 'a11y.json').write_text(json.dumps(after, indent=2))
                    steps = [json.loads(line) for line in (out / 'steps.jsonl').read_text().splitlines()]
                    assert len(steps) == 13 and all(not step['diagnostics']['runtime']['issues'] for step in steps)
                    cursor_log = (out / 'app.log').read_text(errors='replace')
                    assert 'task cursor shape protocol available=false' in cursor_log
                    assert 'task bitmap cursor task=' in cursor_log, 'Missing stock bitmap cursor fallback'
                    result['test_control'] = {'steps': len(steps), 'task': int(task), 'window': int(window)}
                subprocess.run(['grim', str(out / 'screenshot.png')], check=True, timeout=3)
                seat.stdin.write(b'c')
                seat.stdin.flush()
                if process.wait(timeout=30) != 0:
                    raise RuntimeError(f'desktop session did not close successfully: {process.returncode}')
                text = (out / 'app.log').read_text(errors='replace')
            else:
                verify_pixels('TASK_WINDOW_READY', (0, 255, 255), 'first-task')
                if desktop and not mixed_outputs:
                    physical_pointer('RESIZE_POINTER_PRESS_READY', b'b')
                    for marker, name, fullscreen in [
                            ('PENDING_FULLSCREEN_TASK_WINDOW_READY', 'pending-fullscreen', True),
                            ('FULLSCREEN_TASK_WINDOW_READY', 'fullscreen', True),
                            ('FULLSCREEN_RESTORED_TASK_WINDOW_READY', 'fullscreen-restored', False),
                            ('MAXIMIZED_TASK_WINDOW_READY', 'maximized', True),
                            ('MAXIMIZE_RESTORED_TASK_WINDOW_READY', 'maximize-restored', False)]:
                        verify_pixels(marker, (0, 255, 255), name, fullscreen=fullscreen)
                        if name == 'pending-fullscreen':
                            physical_pointer('RESIZE_PENDING_POINTER_READY', b't')
                        if name == 'fullscreen':
                            physical_pointer('RESIZE_RESUMED_POINTER_READY', b't')
                            change_pointer('RESIZE_POINTER_REMOVE_READY', b'r')
                            result['resize_input'] = dict(canceled=True, pending_suppressed=True, resumed=True)
                    for requested, marker, factor, name in [
                            ('SCALE_TASK_WINDOW_REQUESTED', 'SCALED_TASK_WINDOW_READY', 1.5, 'scaled'),
                            ('RESTORE_TASK_SCALE_REQUESTED', 'SCALE_RESTORED_TASK_WINDOW_READY', 1, 'scale-restored')]:
                        deadline = time.monotonic() + 10
                        while requested not in (out / 'app.log').read_text(errors='replace'):
                            if process.poll() is not None or time.monotonic() >= deadline:
                                raise RuntimeError(f'{requested} timeout/exit: {process.poll()}')
                            time.sleep(.02)
                        subprocess.run(['wlr-randr', '--output', 'HEADLESS-1', '--scale', str(factor)],
                                       check=True, capture_output=True, timeout=5)
                        process.stdin.write(b'scaled\n')
                        process.stdin.flush()
                        verify_pixels(marker, (0, 255, 255), name, scale=factor)
                    verify_pixels('STORM_PENDING_TASK_WINDOW_READY', (0, 255, 255), 'storm-pending')
                    verify_pixels('STORM_TASK_WINDOW_READY', (0, 255, 255), 'storm-restored')
                    suspend_desktop()

                for marker, operation in [('POINTER_DEVICE_CREATE', b'p'),
                                          ('POINTER_DEVICE_REMOVE', b'r'),
                                          ('POINTER_DEVICE_RECREATE', b'p')]:
                    change_pointer(marker, operation)
                verify_pixels('SECOND_TASK_WINDOW_READY', (255, 0, 255), 'second-task')
                if mixed_outputs:
                    for requested, marker, name, arguments in [
                            ('MIXED_OUTPUT_SCALE_REQUESTED', 'MIXED_OUTPUT_SCALED_READY', 'mixed-scaled', ['--scale', '1.25']),
                            ('MIXED_OUTPUT_UNPLUG_REQUESTED', 'MIXED_OUTPUT_UNPLUGGED_READY', 'mixed-unplugged', ['--off'])]:
                        deadline = time.monotonic() + 15
                        while requested not in (out / 'app.log').read_text(errors='replace'):
                            if process.poll() is not None or time.monotonic() >= deadline:
                                raise RuntimeError(f'{requested} timeout/exit: {process.poll()}')
                            time.sleep(.02)
                        subprocess.run(['wlr-randr', '--output', 'HEADLESS-2', *arguments],
                                       check=True, capture_output=True, timeout=5)
                        (out / f'{name}-outputs.json').write_bytes(
                            subprocess.check_output(['wlr-randr', '--json'], timeout=5))
                        second_output, second_scale = ('HEADLESS-2', 1.25) if name == 'mixed-scaled' else ('HEADLESS-1', 1)
                        process.stdin.write(b'changed\n')
                        process.stdin.flush()
                        verify_pixels(marker, (255, 0, 255), name)
                if environment.get('AZK_TEST_TABLET') == '1':
                    verify_tablet_pixels()
                verify_pixels('GROUPED_ACTIVITY_WINDOW_READY', (255, 127, 0), 'grouped-activity')
                verify_pixels('SURVIVING_TASK_WINDOW_READY', (0, 255, 255), 'screenshot')
                change_pointer('WINDOWLESS_POINTER_REMOVE', b'r')
                change_pointer('WINDOWLESS_POINTER_RECREATE', b'p')
                change_keyboard('WINDOWLESS_KEYBOARD_GERMAN', b'd')
                change_keyboard('WINDOWLESS_KEYBOARD_US', b'u')
                if process.wait(timeout=30) != 0:
                    raise RuntimeError(f'session failed: {process.returncode}')
                text = (out / 'app.log').read_text(errors='replace')
                assert 'Stock Wayland task: two tasks, grouped activities, one synthetic display, independent pixels and removal passed' in text
                assert 'Stock Wayland pointer: absence and compositor capability lifecycle passed' in text
                if mixed_outputs:
                    assert 'Stock task mixed outputs: scale and unplug preserve logical geometry, density, sibling output and pixels' in text
                if environment.get('AZK_TEST_TABLET') == '1':
                    assert 'Stock Wayland tablet: pen, eraser, axes, task coordinates, buttons, pixels and removal passed' in text
            if args.launcher:
                assert 'task virtual pointer protocol version=1' in text
                result['virtual_pointer_version'] = 1
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
