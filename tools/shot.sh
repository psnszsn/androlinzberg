#!/usr/bin/env bash
# Run a Wayland client headless under cage and screenshot it with grim.
#   tools/shot.sh <out.png> <command> [args...]
#   tools/shot.sh <out.png> --test <test-executable> -- <command> [args...]
#   tools/shot.sh <out.png> --runner -- <test-runner> [args...]
# Uses WLR_BACKENDS=headless and defaults to pixman. Set WLR_RENDERER=gles2
# when testing clients that present EGL buffers. In --test mode the harness
# exposes AZK_TEST_SOCKET, runs the test after the socket appears, and writes
# <out>.a11y.json, <out>.log, and <out>.steps.jsonl beside the screenshot.
# SHOT_ISOLATED_DATA=1 gives --test a temporary Android data/cache directory.
# SHOT_COMPOSITOR=labwc selects a stacking desktop instead of Cage's kiosk.
# SHOT_LABWC_CONFIG optionally supplies an isolated compositor configuration.
# SHOT_LABWC_BINARY selects a protocol fixture binary for labwc test variants.
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
COMPOSITOR_DIR=
export SHOT_RUNNER_MODE=$RUNNER_MODE
if [[ -n $TEST_EXECUTABLE ]]; then
  CONTROL_DIR=$(mktemp -d "$XDG_RUNTIME_DIR/azk-shot.XXXXXX")
  if [[ ${SHOT_ISOLATED_DATA:-0} == 1 ]]; then
    export ARTZIG_DATA_DIR="$CONTROL_DIR/android-data"
    export ARTZIG_RUNTIME_CACHE_DIR="$CONTROL_DIR/runtime-cache"
    export DOWNLOAD_CACHE="$ARTZIG_DATA_DIR/cache"
    export EXTERNAL_STORAGE="$ARTZIG_DATA_DIR/external"
  fi
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
trap 'if [[ -n $CONTROL_DIR ]]; then rm -rf -- "$CONTROL_DIR"; fi; if [[ -n $COMPOSITOR_DIR ]]; then rm -rf -- "$COMPOSITOR_DIR"; fi' EXIT
run_compositor() {
  case ${SHOT_COMPOSITOR:-cage} in
    cage) cage -- "$@" ;;
    labwc)
      COMPOSITOR_DIR=$(mktemp -d "$XDG_RUNTIME_DIR/azk-compositor.XXXXXX")
      mkdir "$COMPOSITOR_DIR/config"
      if [[ -n ${SHOT_LABWC_CONFIG:-} ]]; then
        cp -- "$SHOT_LABWC_CONFIG" "$COMPOSITOR_DIR/config/rc.xml"
      fi
      export SHOT_SESSION_STATUS="$COMPOSITOR_DIR/status"
      # labwc --session accepts shell text. Write an argv-preserving script
      # using Bash quoting, and retain the client's status independently of the
      # compositor's successful shutdown status.
      printf '#!/usr/bin/env bash\n' > "$COMPOSITOR_DIR/session"
      printf '%q ' "$@" >> "$COMPOSITOR_DIR/session"
      cat >> "$COMPOSITOR_DIR/session" <<'SESSION'

result=$?
printf '%s\n' "$result" > "$SHOT_SESSION_STATUS"
exit "$result"
SESSION
      chmod +x "$COMPOSITOR_DIR/session"
      local session_command compositor_status=0
      printf -v session_command '%q' "$COMPOSITOR_DIR/session"
      # The exec preserves the PID exported to this compositor's own session.
      # Fault tests pin it with pidfd and verify their ancestry before signalling.
      bash -c 'export SHOT_COMPOSITOR_PID=$$; exec "$@"' -- \
        "${SHOT_LABWC_BINARY:-labwc}" --config-dir "$COMPOSITOR_DIR/config" --session "$session_command" || compositor_status=$?
      if [[ ${SHOT_EXPECT_COMPOSITOR_LOSS:-0} == 1 && $compositor_status == 137 ]]; then
        # The test runner survives the killed compositor and checks retirement
        # before returning its result. Bound this wait even if the runner fails.
        local attempts=0
        while [[ ! -f $SHOT_SESSION_STATUS && $attempts -lt 600 ]]; do
          sleep 0.05
          attempts=$((attempts + 1))
        done
      fi
      if [[ ! -f $SHOT_SESSION_STATUS ]]; then
        echo "shot.sh: labwc session exited without a client result ($compositor_status)" >&2
        return 1
      fi
      local session_status
      read -r session_status < "$SHOT_SESSION_STATUS"
      [[ $session_status =~ ^[0-9]+$ ]] || return 1
      if [[ $session_status == 0 && $compositor_status != 0 ]]; then
        if [[ ${SHOT_EXPECT_COMPOSITOR_LOSS:-0} == 1 && $compositor_status == 137 ]]; then
          return 0
        fi
        return "$compositor_status"
      fi
      return "$session_status"
      ;;
    *) echo "shot.sh: unknown compositor: $SHOT_COMPOSITOR" >&2; return 2 ;;
  esac
}
run_compositor sh -c '
  if [ -n "${SHOT_RUNNER_MODE:-}" ]; then
    # labwc starts its session without a stdin. A runner that spawns children
    # with pipes needs descriptors 0-2 occupied, or a pipe end lands on one.
    "$@" </dev/null
    exit $?
  fi
  if [ -n "${SHOT_TEST_EXECUTABLE:-}" ]; then
    # Cage puts its single fullscreen task at the sole headless output origin.
    # Discover its actual dimensions inside the compositor, before the presenter
    # reads the test-only placement. Stacking/multi-output runners supply theirs.
    if [ "${SHOT_COMPOSITOR:-cage}" = cage ] &&
       [ "${WLR_HEADLESS_OUTPUTS:-1}" = 1 ] &&
       [ -z "${AZK_TEST_WINDOW_PLACEMENT:-}" ]; then
      PLACEMENT_IMAGE="$AZK_TEST_SOCKET.placement.ppm"
      grim -t ppm "$PLACEMENT_IMAGE" || exit 1
      { read -r MAGIC; read -r WIDTH HEIGHT; } < "$PLACEMENT_IMAGE"
      rm -f "$PLACEMENT_IMAGE"
      [ "$MAGIC" = P6 ] || exit 1
      export AZK_TEST_WINDOW_PLACEMENT="0,0,$WIDTH,$HEIGHT"
    fi
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
