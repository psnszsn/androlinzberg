#!/usr/bin/env bash
set -euo pipefail
cp -- "$1" "$3"
jar --update --no-compress --file "$3" -C "$2" .
