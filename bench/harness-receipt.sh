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
# The commit this series RAN at, recorded by the series itself, not the commit checked out now.
# Stamping current HEAD labelled historical evidence with whatever the tree happens to be.
if [ -f "$OUT/series-commit.txt" ]; then
  echo "ran at    $(cat "$OUT/series-commit.txt")"
else
  echo "ran at    UNRECORDED -- this series predates the launch-identity stamp"
fi
echo "read at   $(git rev-parse HEAD)  (the checkout this receipt was printed from)"
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

  # READ THE RECORDING. Do not re-run the scanner.
  #
  # This is the defect the review found: this block used to open the saved PATH LIST and run the
  # scanner over it again, which opens those paths as they are NOW. One of them is a directory the
  # next harness rewrites, so the "historical" verdict was reconstructed from live state and changed
  # when the live state changed. The content-bound inventory the roster writes was never read.
  #
  # The inventory carries a digest per provider manifest precisely so it can be quoted rather than
  # recomputed. If it is absent, that is reported as absent; it is not re-derived.
  inv="$OUT/$h.providers.txt"
  if [ -f "$inv" ]; then
    printf '%-16s   providers at launch (from the recorded inventory): %s\n' "" "$(tail -1 "$inv")"
    printf '%-16s     recorded inventory: %s [sha256 %s]\n' "" "$inv" \
      "$(sha256sum "$inv" | cut -d' ' -f1)"
  else
    printf '%-16s   providers at launch: NO RECORDED INVENTORY -- loaded-content identity UNVERIFIED\n' ""
    printf '%-16s     not re-derived: a scan run now would describe the tree now, not that launch\n' ""
  fi
done
