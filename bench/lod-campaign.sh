#!/usr/bin/env bash
# The preregistered distance-LOD campaign, docs/evidence/lod-2026-09/PREREGISTRATION.md, in its order.
#
#   bash bench/lod-campaign.sh <jar>
#
# Two rounds, the second reversed within each instrument, because a fixed arm order cannot separate the
# arm from its position in the run. A voided run is kept, and repeated once under a new label; a second
# void stops the campaign rather than retrying until something passes.
set -u
cd "$(dirname "$0")/.."
JAR="${1:?jar under test}"
export PW_OUT="${PW_OUT:-$(pwd)/bench/lod}"
mkdir -p "$PW_OUT"
# The machine is recorded for the whole campaign, so a slow run can be put down to the clock.
LOAD="$PW_OUT/machine-load.csv"
rm -f "$LOAD.stop"
pwsh -NoProfile -File bench/machine-load.ps1 "$LOAD" 10 &
trap 'touch "$LOAD.stop"' EXIT

run() {  # label async lod churn
  local label="$1"
  echo "=== $label  async=$2 lod=$3 churn=$4  $(date -u +%H:%M:%S)"
  bash bench/lod-bench.sh "$label" "$JAR" "$2" "$3" "$4"
  if [ ! -f "$PW_OUT/$label.row.txt" ]; then
    echo "  $label produced no row; repeating once as $label-retry"
    bash bench/lod-bench.sh "$label-retry" "$JAR" "$2" "$3" "$4"
    [ -f "$PW_OUT/$label-retry.row.txt" ] || { echo "CAMPAIGN STOPPED: $label voided twice"; exit 3; }
  fi
}

# Round 1
run r1-A1 false off on
run r1-A2 false on  on
run r1-A3 false off off
run r1-A4 false on  off
run r1-B1 true  off on
run r1-B2 true  on  on
# Round 2, reversed within each instrument
run r2-A2 false on  on
run r2-A1 false off on
run r2-A4 false on  off
run r2-A3 false off off
run r2-B2 true  on  on
run r2-B1 true  off on
echo "=== campaign complete $(date -u +%H:%M:%S)"
