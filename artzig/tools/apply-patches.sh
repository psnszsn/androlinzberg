#!/usr/bin/env bash
# Apply patches/<project-path>/*.patch onto vendor/<project-path> with `git am`.
# Idempotent: a patch whose patch-id already appears in the project's local
# commits is skipped. Use `tools/apply-patches.sh -R` to reset every patched
# project back to its manifest revision (drops local commits!).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/patches"
for dir in $(find . -name '*.patch' -printf '%h\n' | sort -u); do
  proj=${dir#./}
  case "$proj" in bionic_translation*) repo="$ROOT/extra/$proj";; *) repo="$ROOT/vendor/$proj";; esac
  [ -d "$repo/.git" ] || { echo "missing vendor/$proj (run tools/sync-aosp.py)"; exit 1; }
  if [ "${1:-}" = "-R" ]; then
    git -C "$repo" am --abort 2>/dev/null || true
    git -C "$repo" reset -q --hard "$(git -C "$repo" rev-list --max-parents=0 HEAD)"
    echo "reset  $proj"; continue
  fi
  for p in "$dir"/*.patch; do
    pid=$(git patch-id --stable < "$p" | cut -d' ' -f1)
    if git -C "$repo" log -p --format=%H "$(git -C "$repo" rev-list --max-parents=0 HEAD)..HEAD" \
         | git patch-id --stable | cut -d' ' -f1 | grep -qxF "$pid"; then
      echo "skip   $proj: $(basename "$p")"; continue
    fi
    echo "apply  $proj: $(basename "$p")"
    git -C "$repo" am -q --3way "$ROOT/patches/$p"
  done
done
