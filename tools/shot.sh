#!/usr/bin/env bash
# Run a Wayland client headless under cage and screenshot it with grim.
#   tools/shot.sh <out.png> <command> [args...]
#   tools/shot.sh <out.png> --test <test-executable> -- <command> [args...]
#   tools/shot.sh <out.png> --runner -- <test-runner> [args...]
# Uses WLR_BACKENDS=headless and defaults to pixman. Set WLR_RENDERER=gles2
# when testing clients that present EGL buffers. In --test mode the harness
# exposes AZK_TEST_SOCKET, runs the test after the socket appears, and writes
# <out>.a11y.json, <out>.log, and <out>.steps.jsonl beside the screenshot.
set -euo pipefail
OUT=$(realpath -m "$1"); shift
TEST_EXECUTABLE=
RUNNER_MODE=
if [[ ${1:-} == --test ]]; then
  TEST_EXECUTABLE=$(realpath "$2"); shift 2
  [[ ${1:-} == -- ]] || { echo "shot.sh: expected -- after test executable" >&2; exit 2; }
  shift
elif [[ ${1:-} == --runner ]]; then
  RUNNER_MODE=1; shift
  [[ ${1:-} == -- ]] || { echo "shot.sh: expected -- after --runner" >&2; exit 2; }
  shift
fi
[[ $# -gt 0 ]] || { echo "shot.sh: missing application command" >&2; exit 2; }
mkdir -p "$(dirname "$OUT")"
export WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1
export WLR_RENDERER=${WLR_RENDERER:-pixman}
# Integration sessions use wlr-output-management to make LaunchSize the
# compositor's real output size rather than an ignored xdg_toplevel hint.
export AZK_HEADLESS_OUTPUT=${AZK_HEADLESS_OUTPUT:-HEADLESS-1}
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
unset DISPLAY
WARMUP=${SHOT_WARMUP:-3}
CONTROL_DIR=
export SHOT_RUNNER_MODE=$RUNNER_MODE
if [[ -n $TEST_EXECUTABLE ]]; then
  CONTROL_DIR=$(mktemp -d "$XDG_RUNTIME_DIR/azk-shot.XXXXXX")
  export AZK_TEST_SOCKET="$CONTROL_DIR/control.sock"
  export SHOT_TEST_EXECUTABLE=$TEST_EXECUTABLE
  STEM=${OUT%.*}
  export SHOT_A11Y_OUT=${SHOT_A11Y_OUT:-"$STEM.a11y.json"}
  export SHOT_LOG_OUT=${SHOT_LOG_OUT:-"$STEM.log"}
  export SHOT_STEP_OUT=${SHOT_STEP_OUT:-"$STEM.steps.jsonl"}
  export SHOT_FAILURE_OUT=${SHOT_FAILURE_OUT:-"$STEM.failure.json"}
  export AZK_TEST_RAW_LOG=$SHOT_LOG_OUT
  export AZK_TEST_STEP_LOG=$SHOT_STEP_OUT
  : > "$SHOT_LOG_OUT"
  : > "$SHOT_STEP_OUT"
  rm -f -- "$SHOT_FAILURE_OUT"
fi
trap 'if [[ -n $CONTROL_DIR ]]; then rm -rf -- "$CONTROL_DIR"; fi' EXIT
cage -- sh -c '
  if [ -n "${SHOT_RUNNER_MODE:-}" ]; then
    "$@"
    exit $?
  fi
  if [ -n "${SHOT_TEST_EXECUTABLE:-}" ]; then
    "$@" >"$SHOT_LOG_OUT" 2>&1 &
  else
    "$@" &
  fi
  APP=$!
  TEST_STATUS=0
  if [ -n "${SHOT_TEST_EXECUTABLE:-}" ]; then
    COUNT=0
    while [ ! -S "$AZK_TEST_SOCKET" ] && kill -0 "$APP" 2>/dev/null && [ "$COUNT" -lt 200 ]; do
      sleep 0.05
      COUNT=$((COUNT + 1))
    done
    if [ ! -S "$AZK_TEST_SOCKET" ]; then
      echo "shot.sh: test-control socket did not become ready" >&2
      printf "%s\n" "{\"error\":\"test-control socket did not become ready\"}" > "$SHOT_FAILURE_OUT"
      tail -n 80 "$SHOT_LOG_OUT" >&2 || true
      TEST_STATUS=1
    else
      "$SHOT_TEST_EXECUTABLE" || TEST_STATUS=$?
      '"$(dirname "$(realpath "$0")")"'/azk-test observe --tree > "$SHOT_A11Y_OUT" || true
      if [ "$TEST_STATUS" -ne 0 ]; then
        if ! '"$(dirname "$(realpath "$0")")"'/azk-test diagnose > "$SHOT_FAILURE_OUT"; then
          printf "%s\n" "{\"error\":\"final diagnostics unavailable; see process log and step records\"}" > "$SHOT_FAILURE_OUT"
        fi
        echo "shot.sh: test failed; diagnostics: $SHOT_FAILURE_OUT" >&2
        tail -n 80 "$SHOT_LOG_OUT" >&2 || true
      fi
      sleep "${SHOT_SETTLE:-0.2}"
    fi
  else
    sleep '"$WARMUP"'
  fi
  grim '"$OUT"' || echo "grim failed" >&2
  kill $APP 2>/dev/null
  wait $APP 2>/dev/null
  exit "$TEST_STATUS"
' sh "$@"
