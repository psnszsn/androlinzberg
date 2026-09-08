#!/usr/bin/env python3
"""Live output lifecycle checks; tools/shot.sh owns the only compositor."""
import argparse
import json
from pathlib import Path
import select
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--client', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--scenario', choices=['preferred', 'fallback'], required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
result = {'passed': False, 'scenario': args.scenario}


def configure(*arguments):
    subprocess.run(['wlr-randr', *arguments], check=True, capture_output=True, timeout=5)


# Give the outputs disjoint positions in the private compositor. The test
# chooses its target from the window's actual output membership.
configure('--output', 'HEADLESS-1', '--pos', '0,0', '--scale', '1',
          '--output', 'HEADLESS-2', '--pos', '1280,0', '--scale', '1')
with (args.output / 'client.log').open('wb') as log, (args.output / 'states.jsonl').open('w') as states:
    child = subprocess.Popen([str(args.client.resolve()), '--output-lifecycle', args.scenario],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, bufsize=0)
    try:
        def line():
            if not select.select([child.stdout], [], [], 5)[0]:
                raise RuntimeError('output client timed out')
            value = child.stdout.readline()
            if not value:
                raise RuntimeError(f'output client exited: {child.poll()}')
            return value

        assert line() == b'ready\n'

        def await_state(label, predicate):
            end = time.monotonic() + 8
            while True:
                child.stdin.write(b's')
                state = json.loads(line())
                states.write(json.dumps({'step': label, **state}) + '\n')
                states.flush()
                if predicate(state):
                    result[label] = state
                    return state
                if time.monotonic() >= end:
                    raise AssertionError(f'{label}: {state}')
                time.sleep(.05)

        initial = await_state('initial', lambda s: s['count'] == 2 and s['scale'] == 120 and sum(s['members']) == 1)
        first = initial['names'][initial['members'].index(True)]
        selected = initial['labels'][initial['members'].index(True)]
        other = next(label for label in initial['labels'] if label != selected)
        scale = '1.5' if args.scenario == 'preferred' else '2'
        expected = 180 if args.scenario == 'preferred' else 240
        configure('--output', selected, '--scale', scale)
        changed = await_state('scaled', lambda s: s['scale'] == expected and max(s['scales']) == 2)
        assert changed['buffer_width'] == (changed['width'] * expected + 119) // 120
        assert changed['buffer_height'] == (changed['height'] * expected + 119) // 120
        configure('--output', selected, '--off')
        # labwc may retain its last per-surface preference across evacuation;
        # wl_output removal alone does not revoke a fractional-scale preference.
        removed = await_state('removed', lambda s: s['count'] == 1 and s['members'] == [True])
        assert removed['scale'] in ({120, expected} if args.scenario == 'preferred' else {120})
        survivor_scale = '1.25' if args.scenario == 'preferred' else '2'
        survivor_expected = 150 if args.scenario == 'preferred' else 240
        configure('--output', other, '--scale', survivor_scale)
        await_state('survivor_scaled', lambda s: s['scales'] == [2])
        # A newly created surface must receive the surviving output's current
        # preference even when labwc retains the evacuated surface's old hint.
        child.stdin.write(b'n')
        await_state('survivor_created', lambda s: s['probe_scale'] == survivor_expected)
        assert first not in removed['names']
        configure('--output', selected, '--on', '--pos', '0,0' if selected == 'HEADLESS-1' else '1280,0', '--scale', '2')
        restored = await_state('restored', lambda s: s['count'] == 2 and s['probe_scale'] == survivor_expected and sum(s['members']) == 1)
        assert first not in restored['names'], 'Removed global was reused as a live output identity'
        # labwc may return an evacuated window to the re-enabled output. Its
        # current membership must refer to a live global, never the removed ID.
        assert restored['names'][restored['members'].index(True)] != first
        configure('--output', other, '--transform', '90')
        await_state('transformed', lambda s: 1 in s['transforms'] and s['probe_scale'] == survivor_expected)
        subprocess.run(['grim', '-o', other, str(args.output / 'screenshot.png')], check=True, timeout=5)
        child.stdin.write(b'q')
        assert child.wait(timeout=5) == 0
        result['passed'] = True
    except BaseException as error:
        result['error'] = str(error)
        raise
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=5)
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
