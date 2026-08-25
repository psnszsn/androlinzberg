#!/usr/bin/env bash
# Run a Wayland client headless under cage and screenshot it with grim.
#   tools/shot.sh <out.png> <command> [args...]
# Uses WLR_BACKENDS=headless and defaults to pixman. Set WLR_RENDERER=gles2
# when testing clients that present EGL buffers. grim runs after a short warmup.
set -euo pipefail
OUT=$(realpath -m "$1"); shift
export WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1
export WLR_RENDERER=${WLR_RENDERER:-pixman}
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
unset DISPLAY
WARMUP=${SHOT_WARMUP:-3}
exec cage -- sh -c '
  "$@" &
  APP=$!
  sleep '"$WARMUP"'
  grim '"$OUT"' || echo "grim failed" >&2
  kill $APP 2>/dev/null
  wait $APP 2>/dev/null
  exit 0
' sh "$@"
