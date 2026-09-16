"""Audit the real pinned rustc invocations and their consumed dependency files."""
import os
from pathlib import Path
import shlex
import subprocess


def unwrap(command):
    """Decode only directives emitted by rust.zig; never capture inherited secrets."""
    argv, environment = [], {}
    index = 1  # build-command executable
    while index < len(command):
        directive = command[index]
        index += 1
        if directive == '--arg':
            argv.append(command[index])
            index += 1
        elif directive == '--prefix':
            argv.append(command[index] + command[index + 1])
            index += 2
        elif directive in ('--env', '--env-path'):
            name, value = command[index:index + 2]
            environment[name] = str(Path(value).resolve()) if directive == '--env-path' else value
            index += 2
        else:
            raise ValueError(f'unrecognized Rust command directive: {directive}')
    if len(argv) < 6 or argv[0] != 'python3' or Path(argv[1]).name != 'rust-compile.py':
        raise ValueError('Rust invocation lacks its dependency recording wrapper')
    argv = argv[3:]
    if Path(argv[1]).name != 'rustc':
        raise ValueError('Rust command does not invoke the pinned compiler through its runner')
    return argv, environment


def dep_inputs(text):
    # rustc emits several targets for the same dependency set, followed by
    # empty phony rules and optional # env-dep entries. Read the first rule only.
    lines = text.replace('\\\n', '').splitlines()
    line = next((line for line in lines if line and not line.startswith('#')), '')
    target, separator, body = line.partition(': ')
    if not separator or not target:
        raise ValueError('Rust dependency output has no input rule')
    paths = sorted(set(shlex.split(body)))
    if not paths:
        raise ValueError('Rust dependency output has no consumed inputs')
    env = {}
    for line in lines:
        if line.startswith('# env-dep:'):
            key, separator, value = line[len('# env-dep:'):].partition('=')
            env[key] = value if separator else None
    return paths, env


def print_command(argv, query):
    result = []
    index = 0
    while index < len(argv):
        arg = argv[index]
        index += 1
        if arg == '-o':
            index += 1
        elif not arg.startswith('--emit='):
            result.append(arg)
    result.append('--print=' + query)
    return result


def audit(model, commands, paths, package, reference, repo, platform_symbols, classify, digest):
    records, errors = [], []
    compiler = package / 'prebuilts/rust/linux-x86/1.83.0/bin/rustc'
    compiler_record = classify(compiler, package, reference, repo)
    if compiler_record['kind'] != 'unchanged':
        errors.append('Rust compiler is not the pinned original prebuilt')
    sysroots = {}
    for item in model['rust']:
        argv, environment = unwrap(commands[item['command']])
        if Path(argv[1]).resolve(strict=True) != compiler:
            raise ValueError('Rust invocation uses an unexpected compiler')
        source = next(Path(arg).resolve(strict=True) for arg in argv[2:] if arg.endswith('.rs'))
        depfile = paths[item['depfile']].resolve(strict=True)
        inputs, env_deps = dep_inputs(depfile.read_text())
        consumed = [classify(Path(path).resolve(strict=True), package, reference, repo) for path in inputs]
        if str(source) not in {record['path'] for record in consumed}:
            errors.append(f"Rust root missing from compiler dependencies: {item['name']}")
        if any(record['kind'] not in ('unchanged', 'generated') for record in consumed):
            errors.append(f"Rust crate consumed non-original input: {item['name']}")
        for key, value in env_deps.items():
            if environment.get(key) != value:
                errors.append(f"Rust crate has an unrecorded environment dependency: {item['name']}:{key}")
        execution_env = dict(os.environ, **environment)
        cfg = subprocess.check_output(print_command(argv, 'cfg'), env=execution_env, text=True).splitlines()
        if 'target_os="linux"' not in cfg or 'target_arch="x86_64"' not in cfg or 'soong' not in cfg:
            errors.append(f"Rust crate has unexpected effective target/cfg: {item['name']}")
        root = subprocess.check_output(print_command(argv, 'target-libdir'), env=execution_env, text=True).strip()
        if root not in sysroots:
            directory = Path(root).resolve(strict=True)
            if not directory.is_relative_to(package / 'prebuilts/rust/linux-x86/1.83.0'):
                errors.append('Rust sysroot is outside the pinned package')
            sysroots[root] = [classify(path.resolve(), package, reference, repo)
                             for path in sorted(directory.glob('*.rlib'))]
            if not sysroots[root] or any(row['kind'] != 'unchanged' for row in sysroots[root]):
                errors.append('Rust sysroot contains missing or non-original runtime libraries')
        artifact = paths[item['artifact']].resolve(strict=True)
        role = ('compiler_plugin' if item['kind'] == 'proc_macro' else
                'runtime' if item['production'] else 'build_tool')
        records.append(dict(name=item['name'], role=role, production_dependency=item['production'],
                            kind=item['kind'], command=argv, declared_environment=environment,
                            effective_cfg=cfg, consumed_inputs=consumed, environment_dependencies=env_deps,
                            artifact=dict(path=str(artifact), sha256=digest(artifact)),
                            depfile=dict(path=str(depfile), sha256=digest(depfile))))
    runtime = next((row for row in records if row['name'] == 'libinput_rust_ffi'), None)
    if not runtime or runtime['role'] != 'runtime':
        errors.append('Production Rust input verifier is absent from the audit')
    # These CXX exports belong to the original Rust bridge, rather than the C++
    # wrapper alone. Require their presence in both archive and final platform.
    bridge = ('create_input_verifier', 'process_movement', 'reset_device')
    owners = {}
    if runtime:
        symbols = subprocess.check_output(['nm', '--defined-only', runtime['artifact']['path']],
                                          text=True, stderr=subprocess.DEVNULL)
        symbols = [line.split()[-1] for line in symbols.splitlines() if line.split()]
        for name in bridge:
            archive = [symbol for symbol in symbols if name in symbol and '$' in symbol]
            linked = [symbol for symbol in platform_symbols if name in symbol and '$' in symbol]
            owners[name] = dict(archive=archive, platform=linked)
            if not archive or not linked:
                errors.append(f'Original Rust bridge not linked into platform: {name}')
    return dict(compiler=compiler_record, crates=records, sysroots=sysroots, bridge_owners=owners), errors
