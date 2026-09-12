#!/usr/bin/env bash
# The preregistered regression check, docs/evidence/regression-2026-09-13/PREREGISTRATION.md, in order.
#   bash bench/regression-campaign.sh <0.8.0 jar> <0.9.0 jar>
set -u
cd "$(dirname "$0")/.."
V080="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
V090="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
export BUDGET=600 RESERVE=90 PW_OUT="${PW_OUT:-$(pwd)/bench/regression}"
mkdir -p "$PW_OUT"
# The machine is recorded for the whole campaign, so a slow run can be put down to the clock.
LOAD="$PW_OUT/machine-load.csv"
rm -f "$LOAD.stop"
pwsh -NoProfile -File bench/machine-load.ps1 "$LOAD" 10 &
trap 'touch "$LOAD.stop"' EXIT
RUNGS="100 500 1000 2500 5000 10000"
run() { echo "=== $1  $(date -u +%H:%M:%S)"; bash bench/ladder.sh "$1" "$2" $RUNGS; }
run reg-r1-off  off
run reg-r1-v080 "$V080"
run reg-r1-v090 "$V090"
run reg-r2-v090 "$V090"
run reg-r2-v080 "$V080"
run reg-r2-off  off
echo "=== regression campaign complete $(date -u +%H:%M:%S)"
