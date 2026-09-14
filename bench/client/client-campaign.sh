#!/usr/bin/env bash
# The preregistered client comparison, docs/evidence/client-lag-2026-09-14/PREREGISTRATION.md, in order.
#   bash bench/client/client-campaign.sh <round-tag>
set -u
cd "$(dirname "$0")/../.."
TAG="${1:?campaign tag, e.g. c1}"
export PW_OUT="$(pwd)/bench/client-runs"
mkdir -p "$PW_OUT"
LOAD="$PW_OUT/$TAG-machine-load.csv"
rm -f "$LOAD.stop"
pwsh -NoProfile -File bench/machine-load.ps1 "$LOAD" 10 &
trap 'touch "$LOAD.stop"' EXIT
run() { echo "=== $TAG-$1  $(date -u +%H:%M:%S)"; bash bench/client/client-run.sh "$TAG-$1" "${1#r?-}"; }
run r1-none; run r1-v080; run r1-v090; run r1-v090def
run r2-v090def; run r2-v090; run r2-v080; run r2-none
echo "=== campaign complete $(date -u +%H:%M:%S)"
