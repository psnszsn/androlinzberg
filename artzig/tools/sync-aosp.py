#!/usr/bin/env python3
"""Populate vendor/ from an AOSP repo manifest without the `repo` tool.

Usage: tools/sync-aosp.py [-j N] [--only path ...]
Clones each <project> shallowly (depth 1) at the manifest revision into
vendor/<path>, and applies <linkfile>/<copyfile> entries. Projects listed in
EXCLUDE (test infra, CI, Bazel, device images, remote execution) are skipped
unless named with --only. Re-running is idempotent: existing checkouts are
left alone.
"""
import argparse, os, subprocess, sys, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR = os.path.join(ROOT, "vendor")
MANIFEST = os.path.join(VENDOR, "manifest.xml")
BASE_URL = "https://android.googlesource.com/"

# Not needed for a host build of ART with Soong. Add back with --only if Soong
# complains about a missing module.
EXCLUDE_PREFIXES = (
    "device/google/cuttlefish_vmm",
    "test/", "tools/tradefederation/contrib", "tools/asuite", "tools/loganalysis",
    "tools/repohooks", "tools/apifinder", "platform_testing", "development",
    "prebuilts/asuite", "prebuilts/bazel", "prebuilts/remoteexecution-client",
    "prebuilts/module_sdk", "prebuilts/cmdline-tools",
    "prebuilts/bundletool", "external/bazel", "external/mockftpserver",
    "external/mockwebserver", "external/caliper", "external/vogar", "external/nist-pkits",
    "external/toybox", "external/mksh", 
    
    "external/bcc", "external/libbpf",
    "system/bpf", "system/gsid", "system/netd", "packages/modules/DnsResolver",
    "packages/modules/Connectivity", "packages/modules/adb", "external/licenseclassifier",
    "external/spdx-tools", "sdk",
)

def parse(only):
    tree = ET.parse(MANIFEST)
    root = tree.getroot()
    default = root.find("default")
    default_rev = default.get("revision", "main") if default is not None else "main"
    projects = []
    for p in root.findall("project"):
        path = p.get("path", p.get("name"))
        if only:
            if path not in only:
                continue
        elif path.startswith(EXCLUDE_PREFIXES) or path in EXCLUDE_PREFIXES:
            continue
        projects.append({
            "path": path,
            "name": p.get("name"),
            "rev": p.get("revision", default_rev),
            "links": [(l.get("src"), l.get("dest")) for l in p.findall("linkfile")],
            "copies": [(c.get("src"), c.get("dest")) for c in p.findall("copyfile")],
        })
    return projects

def sync(p):
    dest = os.path.join(VENDOR, p["path"])
    if os.path.isdir(os.path.join(dest, ".git")):
        return f"skip   {p['path']}"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    url = BASE_URL + p["name"]
    cmd = ["git", "clone", "-q", "--depth", "1", "--single-branch",
           "--branch", p["rev"], url, dest]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        # revision may be a sha rather than a branch
        os.makedirs(dest, exist_ok=True)
        subprocess.run(["git", "init", "-q", dest], check=True)
        r = subprocess.run(["git", "-C", dest, "fetch", "-q", "--depth", "1", url, p["rev"]],
                           capture_output=True, text=True)
        if r.returncode == 0:
            subprocess.run(["git", "-C", dest, "checkout", "-q", "FETCH_HEAD"], check=True)
        else:
            return f"FAILED {p['path']}: {r.stderr.strip().splitlines()[-1:]}"
    for src, d in p["links"]:
        target = os.path.join(VENDOR, d)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if not os.path.lexists(target):
            os.symlink(os.path.relpath(os.path.join(dest, src), os.path.dirname(target)), target)
    for src, d in p["copies"]:
        target = os.path.join(VENDOR, d)
        if not os.path.exists(target):
            subprocess.run(["cp", os.path.join(dest, src), target], check=True)
    return f"done   {p['path']}"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-j", type=int, default=8)
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    projects = parse(a.only)
    if a.list:
        for p in projects: print(p["path"])
        return
    fails = 0
    with ThreadPoolExecutor(a.j) as ex:
        for msg in ex.map(sync, projects):
            print(msg, flush=True)
            fails += msg.startswith("FAILED")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
