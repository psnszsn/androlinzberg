#!/usr/bin/env python3
"""Stock APK -> system organizer -> SF -> AZKB queue -> Wayland pixel gate.

tools/shot.sh owns Cage; this runner owns only the application session.
"""
import argparse
import json
import os
from pathlib import Path
import resource
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
command = args.command[1:] if args.command[0] == '--' else args.command
result = {'passed': False}
desktop = os.environ.get('SHOT_COMPOSITOR') == 'labwc'
with (out / 'app.log').open('wb') as log:
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=log)
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
