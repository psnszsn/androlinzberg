#!/usr/bin/env bash
# Rebase artzig onto current AOSP master-art.
#
#   tools/rebase.sh [--sync] [--build] [--to <manifest-ref>]
#
# Steps (each is a no-op if nothing changed):
#   1. fetch the master-art manifest at <manifest-ref> (default: origin HEAD),
#      diff it against the pinned vendor/manifest.xml, and adopt it
#   2. --sync: re-run tools/sync-aosp.py (fast-forwards existing checkouts)
#   3. reset every patched vendor project to its manifest revision and re-apply
#      patches/ with `git am` (reports any patch that no longer applies)
#   4. --build: Soong once (regen inputs) -> regen.sh + ninja2java.py ->
#      zig build dist -> smoke test
#
# The point is to make tracking AOSP main cheap: a bump is a manifest diff + a
# patch re-apply, and only patches that actually conflict need hand-work.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
SYNC=0; BUILD=0; REF=""
while [ $# -gt 0 ]; do case "$1" in
  --sync) SYNC=1;; --build) BUILD=1;; --to) REF=$2; shift;;
  *) echo "unknown arg: $1"; exit 1;;
esac; shift; done

MANIFEST_URL=https://android.googlesource.com/platform/manifest
echo "== fetching master-art manifest (${REF:-HEAD})"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
git clone -q --depth 1 -b master-art "$MANIFEST_URL" "$tmp/m"
[ -n "$REF" ] && git -C "$tmp/m" fetch -q --depth 1 origin "$REF" && git -C "$tmp/m" checkout -q FETCH_HEAD
new=$tmp/m/default.xml

if diff -q vendor/manifest.xml "$new" >/dev/null; then
  echo "   manifest unchanged (already at latest)"
else
  echo "   manifest changed — project revision diffs:"
  diff <(grep -oE 'name="[^"]+" (path="[^"]+" )?revision="[^"]+"' vendor/manifest.xml | sort) \
       <(grep -oE 'name="[^"]+" (path="[^"]+" )?revision="[^"]+"' "$new" | sort) | grep '^[<>]' | head -40 || true
  cp "$new" vendor/manifest.xml
  echo "   adopted new manifest.xml"
fi

if [ "$SYNC" = 1 ]; then
  echo "== syncing vendor/ (fast-forward existing checkouts)"
  # fast-forward each existing project to its manifest revision
  python3 - <<'PY'
import subprocess, xml.etree.ElementTree as ET, os
root=os.getcwd(); vend=os.path.join(root,"vendor")
t=ET.parse(os.path.join(vend,"manifest.xml")); d=t.getroot().find("default")
defrev=d.get("revision","main") if d is not None else "main"
for p in t.getroot().findall("project"):
    path=p.get("path",p.get("name")); repo=os.path.join(vend,path)
    if not os.path.isdir(os.path.join(repo,".git")): continue
    rev=p.get("revision",defrev)
    url="https://android.googlesource.com/"+p.get("name")
    subprocess.run(["git","-C",repo,"fetch","-q","--depth","1",url,rev],check=False)
    r=subprocess.run(["git","-C",repo,"checkout","-q","FETCH_HEAD"],capture_output=True,text=True)
    if r.returncode: print(f"  keep {path} (checkout failed: {r.stderr.strip()[:60]})")
PY
  python3 tools/sync-aosp.py   # pick up any newly-added projects
fi

echo "== re-applying patches"
# reset patched projects to manifest revision, then git am the patch series
for dir in $(find patches -name '*.patch' -printf '%h\n' | sort -u); do
  proj=${dir#patches/}; case "$proj" in bionic_translation*) repo="extra/$proj";; *) repo="vendor/$proj";; esac
  [ -d "$repo/.git" ] || { echo "  MISSING $repo (run with --sync)"; continue; }
  git -C "$repo" am --abort 2>/dev/null || true
  base=$(git -C "$repo" rev-list --max-parents=0 HEAD | tail -1)
  git -C "$repo" reset -q --hard "$base"
  for p in "$dir"/*.patch; do
    if git -C "$repo" am -q --3way "$ROOT/$p" 2>/dev/null; then
      echo "  ok    $proj: $(basename "$p")"
    else
      git -C "$repo" am --abort 2>/dev/null || true
      echo "  FAIL  $proj: $(basename "$p") — needs rebase (git -C $repo am --3way $ROOT/$p)"
    fi
  done
done

if [ "$BUILD" = 1 ]; then
  echo "== Soong (regen inputs) + regen + build"
  tools/soong-build.sh build-art-host generate-boot-image-host d8-host \
    out/host/linux-x86/apex/com.android.art/javalib/core-oj.jar >/dev/null
  tools/regen.sh
  nix develop -c zig build dist
  echo "== smoke test"
  d=$(mktemp -d)
  ANDROID_DATA="$d" zig-out/dist/art/bin/art -cp "$ROOT/examples/hello" 2>/dev/null || true
  echo "   (run examples manually: zig build run)"
fi
echo "== done"
