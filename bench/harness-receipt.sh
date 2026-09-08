#!/usr/bin/env bash
# Re-classify a completed harness series from its preserved artifacts. Reads only; never re-runs a
# harness, so a series stays the one attempt it was frozen as.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"
echo "PathWeaver harness series, re-classified from preserved artifacts"
echo "commit    $(git rev-parse HEAD)"
echo "roster    blob $(git rev-parse HEAD:bench/harness-roster.sh 2>/dev/null || echo uncommitted)"
echo "verdict   blob $(git rev-parse HEAD:bench/manifest_verdict.py 2>/dev/null || echo uncommitted)"
echo "command   ./gradlew runGameTest [flag] --rerun-tasks, one attempt per harness, no reruns"
echo
# Who else could have supplied the mod the loader reported. Without this, "the file declares id X
# and the loader reported id X" is agreement between two things and not evidence that the loader read
# that file: another entry declaring the same id produces the same log line.
CP="build/gametest-classpath.txt"
if [ -f "$CP" ]; then
  echo "Providers on the harness runtime classpath:"
  python bench/classpath_providers.py "$CP" 2>&1 | sed 's/^/  /'
  echo "  The id listed is whichever manifest sits in that directory at enumeration time. What the"
  echo "  enumeration establishes is the PROVIDER: one classpath entry can supply a pathweaver_gametest"
  echo "  id, it is build/resources/gametest, and no two entries on the classpath share any id. Every"
  echo "  harness in the series is served from that same directory, so no other entry could have"
  echo "  supplied the mod the loader reported for any of them."
  echo
else
  echo "Providers on the harness runtime classpath: NOT ENUMERATED for this series."
  echo "  Without it, loaded-content identity is UNVERIFIED: the receipt below shows the saved file"
  echo "  agrees with the intended manifest and that its id matches the id the loader reported, which"
  echo "  is not the same as showing the loader read those bytes."
  echo
fi
echo "Per-test identities are NOT recorded: a passing GameTest run reports a batch size and a total,"
echo "and names individual tests only when one fails. The batch size and the manifest that selects"
echo "the batch are what bound the set here."
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
  h="${entry%%:*}"; src="${entry#*:}"
  log="$OUT/$h.log"
  loaded=$(grep -oE "pathweaver_gametest[a-z_]*" "$log" 2>/dev/null | head -1)
  batch=$(grep -oE "batch [0-9]+ \([0-9]+ tests\)" "$log" 2>/dev/null | head -1)
  result=$(grep -oE "All [0-9]+ required tests passed|[0-9]+ required tests? failed" "$log" 2>/dev/null | head -1)
  printf '%-16s %s\n' "$h" "$(python bench/manifest_verdict.py "$OUT/$h.manifest.json" "$src" "$loaded" 2>&1)"
  printf '%-16s   %s | %s | loader reported %s\n' "" "${batch:-no batch line}" "${result:-no result line}" "${loaded:-nothing}"
done
