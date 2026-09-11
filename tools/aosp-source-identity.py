#!/usr/bin/env python3
"""Compare exact static compiler inputs with an explicitly selected AOSP checkout.

Generated sources are checked by the build's generator/source audits instead.
This tool neither searches build caches nor assumes that the newest jar is live.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import sys


NON_CODE = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
PACKAGE = re.compile(r'^\s*package\s+([\w]+(?:\.[\w]+)*)\s*(?:;|$)', re.MULTILINE)


def package_name(source: bytes) -> str:
    code = NON_CODE.sub(lambda match: "\n" * match.group().count("\n"), source.decode("utf-8"))
    match = PACKAGE.search(code)
    return match.group(1) if match else ""


def server_package(name: str) -> bool:
    return name == "com.android.server" or name.startswith("com.android.server.")


def client_package(name: str) -> bool:
    return name == "android" or name.startswith("android.") or name == "com.android.internal" or name.startswith("com.android.internal.")


def compare(reference: Path, package: Path, responses: list[Path], classes: Path) -> dict:
    reference, package = reference.resolve(strict=True), package.resolve(strict=True)
    if reference == package:
        raise ValueError("reference must be independent of the compiled package")
    paths = {Path(path).resolve() for response in responses for path in shlex.split(response.read_text())}
    result = {
        "reference": str(reference), "package": str(package),
        "response_files": [str(path.resolve()) for path in responses],
        "unchanged": 0, "modified_clients": [], "modified_servers": [],
        "missing_reference": [], "owned_clients": [], "host_sources": [],
    }
    selected = 0
    for path in sorted(paths):
        source = path.read_bytes()
        try:
            relative = path.relative_to(package)
        except ValueError:
            key = "owned_clients" if client_package(package_name(source)) else "host_sources"
            result[key].append(str(path))
            continue
        selected += 1
        original = reference / relative
        if not original.is_file():
            result["missing_reference"].append(str(relative))
            continue
        baseline = original.read_bytes()
        if source == baseline:
            result["unchanged"] += 1
        else:
            # A renamed client or a comment mentioning a server package must
            # never be classified as an allowed server adaptation.
            name = package_name(source)
            key = "modified_servers" if server_package(name) and name == package_name(baseline) else "modified_clients"
            result[key].append(str(relative))
    if selected == 0:
        raise ValueError("response files contain no sources from the compiled AOSP package")
    with classes.open("rb") as jar:
        digest = hashlib.file_digest(jar, "sha256").hexdigest()
    result["classes_jar"] = {"path": str(classes.resolve()), "sha256": digest}
    result["passed"] = not any(result[key] for key in ("modified_clients", "missing_reference", "owned_clients"))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path, help="clean checkout at the pinned AOSP revision")
    parser.add_argument("--package", required=True, type=Path, help="extracted package used by this build")
    parser.add_argument("--sources", required=True, action="append", type=Path, help="exact compiler source response file (repeatable)")
    parser.add_argument("--classes", required=True, type=Path, help="classes jar produced by the same build")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = compare(args.reference, args.package, args.sources, args.classes)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    except (OSError, ValueError) as failure:
        print(f"source identity: {failure}", file=sys.stderr)
        return 1
    print(f"source identity: {result['unchanged']} unchanged, "
          f"{len(result['modified_clients'])} modified clients, "
          f"{len(result['modified_servers'])} server adaptations, "
          f"{len(result['missing_reference'])} missing references, "
          f"{len(result['owned_clients'])} owned clients; report: {args.output}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
