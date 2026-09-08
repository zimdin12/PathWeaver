#!/usr/bin/env bash
# Re-classify a completed harness series from its preserved artifacts. Reads only; never re-runs a
# harness, so a series stays the one attempt it was frozen as.
#
# Three claims are kept apart here, because collapsing them is how a receipt overstates itself:
#
#   1. The saved manifest agrees with the manifest that harness was supposed to run. Compared on the
#      fields that decide what runs, by bench/manifest_verdict.py, which is controlled offline.
#   2. The mod id the loader REPORTED matches that saved manifest's id. Agreement, not identity.
#   3. Nothing else on that harness's classpath could have supplied a mod with that id, enumerated
#      from the classpath captured BEFORE that harness launched.
#
# Only all three together establish that the loader read that file. Without (3) the receipt says
# UNVERIFIED rather than implying it, because a later enumeration of a build directory that has been
# rewritten since cannot establish what an already-finished process was able to load.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"

echo "PathWeaver harness series, re-classified from preserved artifacts"
echo "series    $OUT"
echo "commit    $(git rev-parse HEAD)"
echo "roster    blob $(git rev-parse HEAD:bench/harness-roster.sh 2>/dev/null || echo uncommitted)"
echo "verdict   blob $(git rev-parse HEAD:bench/manifest_verdict.py 2>/dev/null || echo uncommitted)"
echo "providers blob $(git rev-parse HEAD:bench/classpath_providers.py 2>/dev/null || echo uncommitted)"
echo "command   ./gradlew runGameTest [flag] --rerun-tasks, one attempt per harness, no reruns"
echo
echo "Per-test identities are NOT recorded: a passing GameTest run reports a batch size and a total,"
echo "and names individual tests only when one fails. Batch size plus the selecting manifest is a"
echo "bounded selector claim, not an observed set."
echo

for entry in \
  "stock:src/gametest/resources/fabric.mod.json" \
  "auditedTier:src/gametest/auditedResources/fabric.mod.json" \
  "unsafeTier:src/gametest/unsafeResources/fabric.mod.json" \
  "newFamily:src/gametest/newFamilyResources/fabric.mod.json" \
  "auditedRouting:src/gametest/auditedRoutingResources/fabric.mod.json" \
  "refused:src/gametest/refusedResources/fabric.mod.json" \
  "breaker:src/gametest/breakerResources/fabric.mod.json" \
  "fabricAggregate:src/gametest/aggregateResources/fabric.mod.json"; do
  h="${entry%%:*}"
  src="${entry#*:}"
  log="$OUT/$h.log"
  loaded=$(grep -oE "pathweaver_gametest[a-z_]*" "$log" 2>/dev/null | head -1)
  batch=$(grep -oE "batch [0-9]+ \([0-9]+ tests\)" "$log" 2>/dev/null | head -1)
  result=$(grep -oE "All [0-9]+ required tests passed|[0-9]+ required tests? failed" "$log" 2>/dev/null | head -1)

  printf '%-16s %s\n' "$h" "$(python bench/manifest_verdict.py "$OUT/$h.manifest.json" "$src" "$loaded" 2>&1)"
  printf '%-16s   %s | %s | loader reported %s\n' "" \
    "${batch:-no batch line}" "${result:-no result line}" "${loaded:-nothing}"

  cp="$OUT/$h.classpath.txt"
  if [ -f "$cp" ]; then
    printf '%-16s   providers at launch: %s\n' "" \
      "$(python bench/classpath_providers.py "$cp" 2>&1 | tail -1)"
  else
    printf '%-16s   providers at launch: NOT ENUMERATED -- loaded-content identity UNVERIFIED\n' ""
  fi
done
