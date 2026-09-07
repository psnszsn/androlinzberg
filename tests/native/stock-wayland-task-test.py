#!/usr/bin/env python3
"""Stock APK -> system organizer -> SF -> AZKB queue -> Wayland pixel gate.

tools/shot.sh owns Cage; this runner owns only the application session.
"""
import argparse
import json
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
with (out / 'app.log').open('wb') as log:
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 60
        while 'TASK_WINDOW_READY' not in (out / 'app.log').read_text(errors='replace'):
            if process.poll() is not None:
                raise RuntimeError(f'session exited before task readiness: {process.returncode}')
            if time.monotonic() >= deadline:
                raise RuntimeError('task readiness timeout')
            time.sleep(0.05)
        deadline = time.monotonic() + 15
        expected = [(0, 255, 255), (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]
        while True:
            ppm = subprocess.check_output(['grim', '-t', 'ppm', '-'], timeout=3)
            magic, dimensions, maximum, pixels = ppm.split(b'\n', 3)
            assert magic == b'P6' and maximum == b'255'
            width, height = map(int, dimensions.split())
            inset = min(width, height) // 16
            points = [(width // 2, height // 2), (inset, inset),
                      (width - inset - 1, inset), (inset, height - inset - 1),
                      (width - inset - 1, height - inset - 1)]
            actual = [tuple(pixels[(y * width + x) * 3:(y * width + x) * 3 + 3]) for x, y in points]
            if all(all(abs(a - b) <= 2 for a, b in zip(pixel, color)) for pixel, color in zip(actual, expected)):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError(f'compositor task colors {actual}, expected {expected}')
            time.sleep(0.05)
        subprocess.run(['grim', str(out / 'screenshot.png')], check=True, timeout=3)
        result.update(size=[width, height], colors=actual)
        process.stdin.write(b'close\n')
        process.stdin.flush()
        if process.wait(timeout=30) != 0:
            raise RuntimeError(f'session failed: {process.returncode}')
        text = (out / 'app.log').read_text(errors='replace')
        assert 'Stock Wayland task: session organizer, remote Surface and task removal passed' in text
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
            process.kill()
            process.wait(timeout=5)
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
