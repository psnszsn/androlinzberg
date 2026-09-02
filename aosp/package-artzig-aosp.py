#!/usr/bin/env python3
"""Build the patched, pruned AOSP source archive consumed by Artzig."""

from __future__ import annotations

import atexit
import fcntl
import gzip
import hashlib
import os
from pathlib import Path, PurePosixPath
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile


PROJECT_ROOT = Path(__file__).resolve().parents[1]
AOSP_ROOT = PROJECT_ROOT / "aosp"
SOURCE_MANIFEST = AOSP_ROOT / "artzig-files.txt"
PACKAGE_NAME = "artzig"
PROJECT_LIST = AOSP_ROOT / ".repo" / "project.list"
MANIFEST_REPOSITORY = AOSP_ROOT / ".repo" / "manifests"

# The patches are applied to the live Repo checkout and reversed before this
# program exits. Keep this list deliberately narrow: bionic_translation and the
# Androlinzberg patches do not belong in the Artzig source package.
PATCH_SERIES = (
    ("art", PROJECT_ROOT / "artzig" / "patches" / "art"),
    (
        "external/conscrypt",
        PROJECT_ROOT / "artzig" / "patches" / "external" / "conscrypt",
    ),
    ("libcore", PROJECT_ROOT / "artzig" / "patches" / "libcore"),
    (
        "system/libbase",
        PROJECT_ROOT / "artzig" / "patches" / "system" / "libbase",
    ),
)


class PackagingError(RuntimeError):
    pass


def run(
    args: list[str],
    *,
    cwd: Path | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        args,
        cwd=cwd,
        check=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def captured(args: list[str], *, cwd: Path | None = None) -> str:
    result = run(args, cwd=cwd, capture=True)
    return result.stdout.decode("utf-8", "strict").strip()


def check_inputs() -> None:
    required = (
        AOSP_ROOT,
        SOURCE_MANIFEST,
        PROJECT_LIST,
        MANIFEST_REPOSITORY,
    )
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise PackagingError("missing required input(s):\n  " + "\n  ".join(missing))


def android_release() -> str:
    ref = captured(
        ["git", "config", "--get", "branch.default.merge"],
        cwd=MANIFEST_REPOSITORY,
    )
    release = ref.rsplit("/", 1)[-1]
    if not release.startswith("android-"):
        raise PackagingError(f"unexpected Repo manifest revision: {ref}")
    return release


def safe_manifest_path(value: str, line_number: int) -> str:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in ("", ".", "..") for part in path.parts):
        raise PackagingError(
            f"{SOURCE_MANIFEST}:{line_number}: unsafe path: {value!r}"
        )
    return path.as_posix()


def read_manifest() -> list[tuple[str, str]]:
    rules: list[tuple[str, str]] = []
    for line_number, raw_line in enumerate(
        SOURCE_MANIFEST.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if len(fields) != 3:
            raise PackagingError(
                f"{SOURCE_MANIFEST}:{line_number}: expected '<kind> <source> <path>'"
            )
        kind, source, raw_path = fields
        if kind not in {"D", "F", "L", "P"}:
            raise PackagingError(
                f"{SOURCE_MANIFEST}:{line_number}: unknown rule kind: {kind}"
            )
        if source != "aosp":
            raise PackagingError(
                f"{SOURCE_MANIFEST}:{line_number}: unsupported source: {source}"
            )
        path = safe_manifest_path(raw_path, line_number)
        if path == "bionic_translation" or path.startswith("bionic_translation/"):
            raise PackagingError(
                f"{SOURCE_MANIFEST}:{line_number}: bionic_translation is a separate dependency"
            )
        rules.append((kind, path))
    if not rules:
        raise PackagingError(f"source manifest is empty: {SOURCE_MANIFEST}")
    return rules


def read_projects() -> list[str]:
    projects: list[str] = []
    for line_number, raw_line in enumerate(
        PROJECT_LIST.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line:
            continue
        project = safe_manifest_path(line, line_number)
        projects.append(project)
    # A nested Repo project owns its paths instead of its nearest ancestor.
    return sorted(set(projects), key=lambda value: (-len(PurePosixPath(value).parts), value))


def owning_project(path: str, projects: list[str]) -> tuple[str, str]:
    for project in projects:
        if path == project:
            return project, "."
        prefix = project + "/"
        if path.startswith(prefix):
            return project, path[len(prefix) :]
    raise PackagingError(f"manifest path is not owned by a Repo project: {path}")


def tracked_files(project: str) -> set[str]:
    result = run(
        ["git", "ls-files", "-z"],
        cwd=AOSP_ROOT / project,
        capture=True,
    )
    return {
        entry.decode("utf-8", "strict")
        for entry in result.stdout.split(b"\0")
        if entry
    }


def require_clean(projects: set[str]) -> None:
    dirty: list[str] = []
    for project in sorted(projects):
        status = captured(
            ["git", "status", "--porcelain", "--untracked-files=no"],
            cwd=AOSP_ROOT / project,
        )
        if status:
            dirty.append(f"[{project}]\n{status}")
    if dirty:
        raise PackagingError(
            "refusing to package modified tracked AOSP files:\n" + "\n".join(dirty)
        )


def expand_manifest(
    rules: list[tuple[str, str]], projects: list[str]
) -> tuple[list[str], set[str], set[str]]:
    resolved = [(kind, path, *owning_project(path, projects)) for kind, path in rules]
    used_projects = {project for _, _, project, _ in resolved}
    tracked = {project: tracked_files(project) for project in used_projects}

    files: set[str] = set()
    patch_created: set[str] = set()
    for kind, path, project, relative in resolved:
        project_files = tracked[project]
        if kind == "D":
            if relative == ".":
                matches = {f"{project}/{entry}" for entry in project_files}
            else:
                prefix = relative.rstrip("/") + "/"
                matches = {
                    f"{project}/{entry}"
                    for entry in project_files
                    if entry == relative or entry.startswith(prefix)
                }
            if not matches:
                raise PackagingError(f"directory rule matched no tracked files: {path}")
            files.update(matches)
        elif kind in {"F", "L"}:
            if relative not in project_files:
                raise PackagingError(f"exact rule is not tracked by Git: {path}")
            source = AOSP_ROOT / path
            if kind == "L" and not source.is_symlink():
                raise PackagingError(f"L rule is not a symlink: {path}")
            if kind == "F" and source.is_symlink():
                raise PackagingError(f"F rule unexpectedly names a symlink: {path}")
            files.add(path)
        else:
            if relative in project_files or (AOSP_ROOT / path).exists():
                raise PackagingError(f"P rule already exists before patching: {path}")
            patch_created.add(path)

    files.update(patch_created)
    return sorted(files), used_projects, patch_created


def collect_patch_series() -> list[tuple[str, Path]]:
    result: list[tuple[str, Path]] = []
    for project, source in PATCH_SERIES:
        patches = [source] if source.is_file() else sorted(source.glob("*.patch"))
        if not patches:
            raise PackagingError(f"no patches found in {source}")
        result.extend((project, patch.resolve()) for patch in patches)
    return result


def apply_patches(
    series: list[tuple[str, Path]], applied: list[tuple[str, Path]]
) -> None:
    for project, patch in series:
        run(["git", "apply", str(patch)], cwd=AOSP_ROOT / project)
        applied.append((project, patch))


def reverse_patches(applied: list[tuple[str, Path]]) -> list[str]:
    failures: list[str] = []
    for project, patch in reversed(applied):
        try:
            run(["git", "apply", "--reverse", str(patch)], cwd=AOSP_ROOT / project)
        except subprocess.CalledProcessError as error:
            failures.append(f"{project}: {patch.name} (exit {error.returncode})")
    return failures


def validate_archive_inputs(paths: list[str]) -> tuple[int, int]:
    regular_bytes = 0
    symlinks = 0
    for relative in paths:
        source = AOSP_ROOT / relative
        try:
            metadata = source.lstat()
        except FileNotFoundError as error:
            raise PackagingError(f"archive input does not exist after patching: {relative}") from error
        if stat.S_ISREG(metadata.st_mode):
            regular_bytes += metadata.st_size
        elif stat.S_ISLNK(metadata.st_mode):
            symlinks += 1
        else:
            raise PackagingError(f"archive input is not a regular file or symlink: {relative}")
    return regular_bytes, symlinks


def directory_entries(paths: list[str]) -> list[str]:
    directories: set[str] = set()
    for value in paths:
        parent = PurePosixPath(value).parent
        while parent != PurePosixPath("."):
            directories.add(parent.as_posix())
            parent = parent.parent
    return sorted(directories)


def normalized_tar_info(name: str, mode: int, entry_type: bytes) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.type = entry_type
    info.mode = mode
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.mtime = 0
    return info


def write_archive(destination: Path, archive_root: str, paths: list[str]) -> None:
    with destination.open("wb") as raw_output:
        with gzip.GzipFile(
            filename="",
            mode="wb",
            compresslevel=9,
            fileobj=raw_output,
            mtime=0,
        ) as compressed:
            with tarfile.open(
                fileobj=compressed,
                mode="w|",
                format=tarfile.GNU_FORMAT,
                dereference=False,
            ) as archive:
                archive.addfile(
                    normalized_tar_info(archive_root, 0o755, tarfile.DIRTYPE)
                )
                for directory in directory_entries(paths):
                    archive.addfile(
                        normalized_tar_info(
                            f"{archive_root}/{directory}", 0o755, tarfile.DIRTYPE
                        )
                    )
                for relative in paths:
                    source = AOSP_ROOT / relative
                    metadata = source.lstat()
                    name = f"{archive_root}/{relative}"
                    if stat.S_ISLNK(metadata.st_mode):
                        info = normalized_tar_info(
                            name, stat.S_IMODE(metadata.st_mode), tarfile.SYMTYPE
                        )
                        info.linkname = os.readlink(source)
                        archive.addfile(info)
                    else:
                        info = normalized_tar_info(
                            name, stat.S_IMODE(metadata.st_mode), tarfile.REGTYPE
                        )
                        info.size = metadata.st_size
                        with source.open("rb") as input_file:
                            archive.addfile(info, input_file)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        while chunk := input_file.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def human_size(size: int) -> str:
    units = ("B", "KiB", "MiB", "GiB")
    value = float(size)
    for unit in units:
        if value < 1024 or unit == units[-1]:
            return f"{value:.2f} {unit}" if unit != "B" else f"{size} B"
        value /= 1024
    raise AssertionError("unreachable")


def verify_reverted(projects: set[str]) -> None:
    dirty: list[str] = []
    for project in sorted(projects):
        status = captured(
            ["git", "status", "--porcelain", "--untracked-files=no"],
            cwd=AOSP_ROOT / project,
        )
        if status:
            dirty.append(f"[{project}]\n{status}")
    if dirty:
        raise PackagingError(
            "AOSP checkout was not restored after packaging:\n" + "\n".join(dirty)
        )


def interrupted(signum: int, _frame: object) -> None:
    raise InterruptedError(f"received signal {signum}")


def main() -> int:
    check_inputs()
    signal.signal(signal.SIGHUP, interrupted)
    signal.signal(signal.SIGTERM, interrupted)

    lock_path = AOSP_ROOT / f".{PACKAGE_NAME}-package.lock"
    atexit.register(lock_path.unlink, missing_ok=True)
    with lock_path.open("w", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise PackagingError(f"another {PACKAGE_NAME} packaging process is running") from error

        release = android_release()
        output = AOSP_ROOT / f"{PACKAGE_NAME}-aosp-{release}.tar.gz"
        archive_root = output.name.removesuffix(".tar.gz")

        rules = read_manifest()
        projects = read_projects()
        paths, selected_projects, patch_created = expand_manifest(rules, projects)
        patch_series = collect_patch_series()
        patch_projects = {project for project, _ in patch_series}
        require_clean(selected_projects | patch_projects)
        temporary_file = tempfile.NamedTemporaryFile(
            dir=AOSP_ROOT,
            prefix=f".{archive_root}.",
            suffix=".tmp",
            delete=False,
        )
        temporary_file.close()
        temporary_path = Path(temporary_file.name)

        applied: list[tuple[str, Path]] = []
        build_error: BaseException | None = None
        regular_bytes = 0
        symlink_count = 0
        try:
            apply_patches(patch_series, applied)
            regular_bytes, symlink_count = validate_archive_inputs(paths)
            write_archive(temporary_path, archive_root, paths)
        except BaseException as error:
            build_error = error

        cleanup_failures = reverse_patches(applied)
        try:
            verify_reverted(selected_projects | patch_projects)
        except BaseException as error:
            cleanup_failures.append(str(error))
        remaining_patch_files = [
            path for path in sorted(patch_created) if (AOSP_ROOT / path).exists()
        ]
        if remaining_patch_files:
            cleanup_failures.append(
                "patch-created files remain:\n    "
                + "\n    ".join(remaining_patch_files)
            )

        if build_error is not None or cleanup_failures:
            temporary_path.unlink(missing_ok=True)
            if cleanup_failures:
                raise PackagingError(
                    "failed to restore the AOSP checkout:\n  "
                    + "\n  ".join(cleanup_failures)
                ) from build_error
            raise build_error

        os.chmod(temporary_path, 0o644)
        os.replace(temporary_path, output)

        compressed_bytes = output.stat().st_size
        print(f"archive:       {output}")
        print(f"AOSP release:  {release}")
        print(f"source files:  {len(paths)}")
        print(f"symlinks:      {symlink_count}")
        print(f"patch files:   {len(patch_series)}")
        print(f"patch-created: {len(patch_created)}")
        print(f"source size:   {human_size(regular_bytes)}")
        print(f"archive size:  {human_size(compressed_bytes)}")
        print(f"SHA-256:       {sha256(output)}")
        print("AOSP checkout: restored")
    lock_path.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PackagingError, subprocess.CalledProcessError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
