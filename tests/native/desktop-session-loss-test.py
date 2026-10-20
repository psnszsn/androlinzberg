#!/usr/bin/env python3
"""Kill one owned session service after real APK presentation, under tools/shot.sh.

No test controller runs in the launcher: periodic test Binder calls would mask
an otherwise idle controller failing to notice that its session has died.
"""
import argparse
import json
import os
from pathlib import Path
import resource
import select
import signal
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--fault', choices=('system', 'surfaceflinger', 'presenter', 'media', 'broker', 'compositor'), required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--seat', type=Path, required=True)
p.add_argument('command', nargs=argparse.REMAINDER)
a = p.parse_args()
command = a.command[1:] if a.command[0] == '--' else a.command
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
env = os.environ.copy()
report = {'passed': False, 'fault': a.fault}
owned = {}
owner = None
seat = None
compositor_fd = None


def alive(fd):
    return not select.select([fd], [], [], 0)[0]


def descendants(pid):
    found = set()
    pending = [pid]
    while pending:
        parent = pending.pop()
        for path in Path(f'/proc/{parent}/task').glob('*/children'):
            try:
                children = [int(v) for v in path.read_text().split()]
            except FileNotFoundError:
                continue
            for child in children:
                if child not in found:
                    found.add(child)
                    pending.append(child)
    return found


def collect():
    for pid in descendants(owner.pid):
        if pid in owned:
            continue
        fd = None
        try:
            fd = os.pidfd_open(pid)
            argv = Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
            executable = Path(os.fsdecode(argv[0])).name
            # Stock Process.setArgV0 replaces the exec argument block once the
            # server enters Java; retain the exec flag for early-start cleanup.
            role = ('system' if argv[0] == b'system_server' or executable == 'azk-system-server' or b'--system-server' in argv else
                    'surfaceflinger' if executable == 'azk-surfaceflinger' else
                    'presenter' if executable == 'azk-task-presenter' else
                    'media' if executable == 'azk-media-service' else
                    'broker' if executable == 'azk-binder-broker' else 'app')
            socket = os.fsdecode(argv[argv.index(b'--socket') + 1]) if role == 'broker' else None
            owned[pid] = {'fd': fd, 'role': role, 'socket': socket}
        except (ProcessLookupError, FileNotFoundError):
            if fd is not None:
                os.close(fd)
            continue


try:
    with (out / 'app.log').open('wb') as log:
        seat = subprocess.Popen([str(a.seat.resolve()), '--keyboard-seat'], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=log, env=env)
        assert select.select([seat.stdout], [], [], 10)[0], 'keyboard did not start'
        assert seat.stdout.readline() == b'ready\n'
        owner = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=log, env=env)
        report['owner_pid'] = owner.pid
        deadline = time.monotonic() + 60
        while True:
            text = (out / 'app.log').read_text(errors='replace')
            if ('Desktop launch accepted: dev.artzig.fixtures.windows' in text
                    and 'Displayed dev.artzig.fixtures.windows/' in text
                    and 'surface-presentation submission=' in text):
                break
            if owner.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('fixture failed to launch')
            time.sleep(.05)
        # Let pending organizer callbacks and the first visible frame settle
        # so the controller is idle and the retained image shows the fixture.
        time.sleep(.25)
        subprocess.run(['grim', str(out / 'before.png')], check=True, timeout=3)
        collect()
        services = {role: [(pid, value) for pid, value in owned.items() if value['role'] == role]
                    for role in ('system', 'surfaceflinger', 'presenter', 'media', 'broker')}
        assert all(len(values) == 1 for values in services.values()), {k: len(v) for k, v in services.items()}
        session_dir = Path(services['broker'][0][1]['socket']).parent
        info = json.loads((session_dir / 'session.json').read_text())
        assert info['pid'] == owner.pid, 'service is not owned by this launcher'
        report['services'] = {role: values[0][0] for role, values in services.items()}
        report['session_dir'] = str(session_dir)
        if a.fault == 'compositor':
            compositor_pid = int(os.environ['SHOT_COMPOSITOR_PID'])
            compositor_fd = os.pidfd_open(compositor_pid)
            assert os.getpid() in descendants(compositor_pid), 'compositor does not own this runner'
            assert alive(compositor_fd), 'owned compositor has already exited'
            victim, record = compositor_pid, {'fd': compositor_fd}
            report['compositor_pid'] = compositor_pid
        else:
            victim, record = services[a.fault][0]
            assert victim in descendants(owner.pid) and alive(record['fd'])
        report['before_log_bytes'] = (out / 'app.log').stat().st_size
        start = time.monotonic()
        signal.pidfd_send_signal(record['fd'], signal.SIGKILL)
        report['owner_exit'] = owner.wait(timeout=20)
        report['retirement_ms'] = round((time.monotonic() - start) * 1000)
        assert report['owner_exit'] > 0, 'service loss must report a controlled nonzero exit'
        deadline = time.monotonic() + 5
        while any(alive(value['fd']) for value in owned.values()) and time.monotonic() < deadline:
            time.sleep(.02)
        report['live_descendants'] = [value['role'] for value in owned.values() if alive(value['fd'])]
        report['session_dir_removed'] = not session_dir.exists()
        assert not report['live_descendants'], report['live_descendants']
        assert report['session_dir_removed'], 'retired session directory was left behind'
        text = (out / 'app.log').read_text(errors='replace')
        assert 'Desktop session lost:' in text, 'missing explicit service-loss diagnostic'
        assert 'broker disconnected while revoking launch' not in text, 'grant cleanup threw after broker loss'
        for crash in ('Fatal signal', 'pthread_getattr_np failed', 'Native thread exited without calling DetachCurrentThread', 'panic:'):
            assert crash not in text, crash
        if a.fault != 'presenter':
            assert 'task presenter released all buffers' in text
        if a.fault != 'compositor':
            subprocess.run(['grim', str(out / 'after.png')], check=True, timeout=3)
        report['passed'] = True
except BaseException as failure:
    report['error'] = repr(failure)
    raise
finally:
    if compositor_fd is not None:
        os.close(compositor_fd)
    if owner is not None:
        # pidfds pin the identities found under this test's launcher. Cleanup
        # never discovers or signals unrelated user sessions or compositors.
        collect()
        if owner.poll() is None:
            owner.kill()
            owner.wait(timeout=5)
        for value in owned.values():
            if alive(value['fd']):
                try:
                    signal.pidfd_send_signal(value['fd'], signal.SIGKILL)
                except ProcessLookupError:
                    pass
            os.close(value['fd'])
    if seat is not None:
        if seat.poll() is None:
            seat.stdin.close()
            try:
                seat.wait(timeout=3)
            except subprocess.TimeoutExpired:
                seat.kill()
                seat.wait(timeout=3)
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
