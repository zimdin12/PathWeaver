#!/usr/bin/env bash
# The preregistered client comparison of both hot-path fixes,
# docs/evidence/client-lag-2026-09-14/HOTPATH-PREREGISTRATION.md, in order.
#   PW_CAND=<candidate jar> PW_ENHANCED_CATS=<jar> PW_BASE_WORLD=... PW_V090=... bash bench/client/hotpath-campaign.sh
set -u
cd "$(dirname "$0")/../.."
TAG=h1
: "${PW_CAND:?candidate jar}" "${PW_ENHANCED_CATS:?Enhanced Cats jar, added back for the reported round}"
export PW_OUT="$(pwd)/bench/client-runs"
mkdir -p "$PW_OUT"
LOAD="$PW_OUT/$TAG-machine-load.csv"
rm -f "$LOAD.stop"
pwsh -NoProfile -File bench/machine-load.ps1 "$LOAD" 10 &
trap 'touch "$LOAD.stop"' EXIT
run() { echo "=== $TAG-$1  $(date -u +%H:%M:%S)"; bash bench/client/client-run.sh "$TAG-$1" "${1##*-}"; }
run r1-none; run r1-v090; run r1-cand
run r2-cand; run r2-v090; run r2-none
run r3-v090; run r3-none; run r3-cand
PW_ADD_MODS="$PW_ENHANCED_CATS" run e-v090
PW_ADD_MODS="$PW_ENHANCED_CATS" run e-cand
echo "=== campaign complete $(date -u +%H:%M:%S)"
