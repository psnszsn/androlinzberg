#!/usr/bin/env bash
# End-to-end smoke test for the built-in ViewHost demo. Run through shot.sh.
set -euo pipefail
TOOLS_DIR=$(dirname "$(realpath "$0")")
"$TOOLS_DIR/azk-test" wait ready >/dev/null
"$TOOLS_DIR/azk-test" step click --text "TAP ME" --settle idle >/dev/null
"$TOOLS_DIR/azk-test" wait exists --text "Button clicked! Input dispatch works." >/dev/null
