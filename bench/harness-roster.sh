#!/usr/bin/env bash
# The frozen 0.9.0 harness roster. One line per harness, run in this order, all eight required green.
#
# cantReachHarness is NOT here. It is an on-demand reproducer for a flaky gate that was waived as
# blocking in 0.6 (DESIGN.md 12, CHANGELOG 203). It is never counted toward a release, green or not,
# and adding it here would be how it quietly becomes a gate again.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"
mkdir -p "$OUT"

ROSTER=(
  "stock:"
  "auditedTier:-PauditedTierHarness"
  "unsafeTier:-PunsafeTierHarness"
  "newFamily:-PnewFamilyHarness"
  "auditedRouting:-PauditedRoutingHarness"
  "refused:-PrefusedHarness"
  "breaker:-PbreakerHarness"
  "fabricAggregate:-PfabricAggregateHarness"
)

: > "$OUT/summary.txt"
for entry in "${ROSTER[@]}"; do
  name="${entry%%:*}"
  flag="${entry#*:}"
  log="$OUT/$name.log"
  started=$(date -u +%H:%M:%S)
  # Fresh run directory each time: the audited harness used to leave its pinned config behind and
  # the next harness inherited it.
  ./gradlew deleteGameTestRunDir --console=plain >/dev/null 2>&1
  if [ -z "$flag" ]; then
    timeout 1800 ./gradlew runGameTest --rerun-tasks --console=plain > "$log" 2>&1
  else
    timeout 1800 ./gradlew runGameTest "$flag" --rerun-tasks --console=plain > "$log" 2>&1
  fi
  code=$?
  ended=$(date -u +%H:%M:%S)
  printf '%-16s exit=%-3s %s-%s  %s\n' "$name" "$code" "$started" "$ended" "$log" >> "$OUT/summary.txt"
done
cat "$OUT/summary.txt"
