#!/usr/bin/env bash
# Run a Wayland client headless under cage and screenshot it with grim.
#   tools/shot.sh <out.png> <command> [args...]
#   tools/shot.sh <out.png> --runner -- <test-runner> [args...]
# Uses WLR_BACKENDS=headless and defaults to pixman. Set WLR_RENDERER=gles2
# when testing clients that present EGL buffers. In --runner mode the command
# is a test runner that owns its application sessions and its own result.
# SHOT_COMPOSITOR=labwc selects a stacking desktop instead of Cage's kiosk.
# SHOT_LABWC_CONFIG optionally supplies an isolated compositor configuration.
# SHOT_LABWC_BINARY selects a protocol fixture binary for labwc test variants.
set -euo pipefail
OUT=$(realpath -m "$1"); shift
RUNNER_MODE=
if [[ ${1:-} == --runner ]]; then
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
# Retain the complete Android log; the launcher's default shows warnings only.
export AZK_LOG_LEVEL=${AZK_LOG_LEVEL:-v}
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
unset DISPLAY
WARMUP=${SHOT_WARMUP:-3}
COMPOSITOR_DIR=
export SHOT_RUNNER_MODE=$RUNNER_MODE
trap 'if [[ -n $COMPOSITOR_DIR ]]; then rm -rf -- "$COMPOSITOR_DIR"; fi' EXIT
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
  "$@" &
  APP=$!
  sleep '"$WARMUP"'
  grim '"$OUT"' || echo "grim failed" >&2
  kill $APP 2>/dev/null
  wait $APP 2>/dev/null
' sh "$@"
