#!/usr/bin/env python3
"""Stock APK -> system organizer -> SF -> AZKB queue -> Wayland pixel gate.

tools/shot.sh owns the compositor; this runner owns only the application session.
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

parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--seat', type=Path, required=True)
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
for artifact in ('result.json',):
    (out / artifact).unlink(missing_ok=True)
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
command = args.command[1:] if args.command[0] == '--' else args.command
result = {'passed': False}
desktop = os.environ.get('SHOT_COMPOSITOR') == 'labwc'
mixed_outputs = os.environ.get('AZK_TEST_MIXED_OUTPUTS') == '1'
second_output, second_scale = 'HEADLESS-2', 1
with (out / 'app.log').open('wb') as log:
    environment = os.environ.copy()
    if desktop and not mixed_outputs:
        environment['AZK_TEST_DESKTOP_CONFIGURATIONS'] = '1'
    if mixed_outputs:
        subprocess.run(['wlr-randr', '--output', 'HEADLESS-1', '--pos', '0,0', '--scale', '1',
                        '--output', 'HEADLESS-2', '--pos', '1280,0', '--scale', '1'],
                       check=True, capture_output=True, timeout=5)
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
                    # Stock InputManagerService shows stylus pointer icons only with
                    # config_enableStylusPointerIcon, off in the pinned configuration,
                    # so the compositor keeps its own tablet tool cursor.
                    result[name] = dict(point=[x, y], color=actual)
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
                assert protocol_version >= 6
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

            verify_pixels('TASK_WINDOW_READY', (0, 255, 255), 'first-task')
            if desktop and not mixed_outputs:
                change_pointer('RESIZE_POINTER_CREATE_READY', b'p')
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
            assert 'task presenter released all buffers' in text
            for fatal in ['Native thread exited without calling DetachCurrentThread',
                          'FATAL EXCEPTION', 'Unhandled exception', 'panic:']:
                assert fatal not in text, fatal
            result['passed'] = True
        except BaseException as error:
            result['error'] = str(error)
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
