#!/usr/bin/env bash
# The frozen 0.9.0 harness roster: eight harnesses, this order, all eight required green.
#
# cantReachHarness is NOT here. It is an on-demand reproducer for a gate waived as blocking in 0.6
# (DESIGN.md 12, CHANGELOG 203). It is never counted toward a release, green or not, and a roster
# that lists it is how it quietly becomes a gate again.
#
# WHY THIS DELETES build/resources/gametest FIRST, EVERY TIME
#
# Each harness overwrites the generated fabric.mod.json in place, and one of them deletes the test
# mixin config. A copy task does not remove files a previous run added, so leftover state between
# harnesses is possible in principle.
#
# It has NOT been shown to have happened. An earlier version of this comment said a stock run had
# executed with the audited-routing manifest; the control built to demonstrate that mechanism
# refuted it, because gradle regenerates the stock manifest under --rerun-tasks. That stock failure
# remains unexplained, and this delete is a precaution rather than a fix for a diagnosed fault.
#
# An exit code cannot see a wrong manifest, and neither can a green count. So every run records what
# it actually consumed and the caller checks it against what that harness was supposed to run.
#
# WHEN THE EVIDENCE IS CAPTURED, AND WHY THERE
#
# The classpath and the provider inventory are captured AFTER the harness JVM exits and BEFORE the
# next iteration's delete. That window is the one moment the inputs are still exactly what the run
# used: the resources are generated during the run itself, so they do not exist beforehand, and the
# next thing to touch them is the next harness's delete at the top of this loop.
#
# The inventory is saved as CONTENT, carrying a digest per provider manifest, not as a list of paths.
# One of those providers is a directory the next harness rewrites, so a path list read back later
# would be answering a question about a different state of the tree.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"
mkdir -p "$OUT"

# name : gradle flag : the source manifest that harness is supposed to run
#
# The expectation is DERIVED from that file rather than written out here, because two harnesses share
# a mod id (breaker reuses the stock one) and a list of ids would have called a swapped breaker run
# correct. Content distinguishes them; an id does not.
ROSTER=(
  "stock::src/gametest/resources/fabric.mod.json"
  "auditedTier:-PauditedTierHarness:src/gametest/auditedResources/fabric.mod.json"
  "unsafeTier:-PunsafeTierHarness:src/gametest/unsafeResources/fabric.mod.json"
  "newFamily:-PnewFamilyHarness:src/gametest/newFamilyResources/fabric.mod.json"
  "auditedRouting:-PauditedRoutingHarness:src/gametest/auditedRoutingResources/fabric.mod.json"
  "refused:-PrefusedHarness:src/gametest/refusedResources/fabric.mod.json"
  "breaker:-PbreakerHarness:src/gametest/breakerResources/fabric.mod.json"
  "fabricAggregate:-PfabricAggregateHarness:src/gametest/aggregateResources/fabric.mod.json"
)

: > "$OUT/summary.txt"
for entry in "${ROSTER[@]}"; do
  name="${entry%%:*}"
  rest="${entry#*:}"
  flag="${rest%%:*}"
  expected="${rest#*:}"
  log="$OUT/$name.log"
  started=$(date -u +%H:%M:%S)

  ./gradlew deleteGameTestRunDir --console=plain >/dev/null 2>&1
  rm -rf build/resources/gametest

  if [ -z "$flag" ]; then
    timeout 1800 ./gradlew runGameTest --rerun-tasks --console=plain > "$log" 2>&1
  else
    timeout 1800 ./gradlew runGameTest "$flag" --rerun-tasks --console=plain > "$log" 2>&1
  fi
  code=$?
  ended=$(date -u +%H:%M:%S)

  # ---- the inputs, while they are still the ones this harness used -----------------------------
  cp build/resources/gametest/fabric.mod.json "$OUT/$name.manifest.json" 2>/dev/null
  # stderr is KEPT. Discarding it once produced eight empty classpath files and no reason why, which
  # is the diagnostic being thrown away to keep the output tidy.
  ./gradlew --init-script bench/print-classpath.gradle printGametestRuntimeClasspath -q \
    --console=plain > "$OUT/$name.classpath.raw" 2> "$OUT/$name.classpath.err"
  grep -E "^([A-Za-z]:|/)" "$OUT/$name.classpath.raw" > "$OUT/$name.classpath.txt" 2>/dev/null
  python bench/classpath_providers.py "$OUT/$name.classpath.txt" > "$OUT/$name.providers.txt" 2>&1
  providers=$(tail -1 "$OUT/$name.providers.txt")

  # ---- what it consumed, and whether that is what it was supposed to run ------------------------
  loaded_id=$(grep -oE "pathweaver_gametest[a-z_]*" "$log" | head -1)
  if manifest=$(python bench/manifest_verdict.py "$OUT/$name.manifest.json" "$expected" \
      "${loaded_id:-}" 2>&1); then
    contaminated=0
  else
    contaminated=1
  fi
  manifest=$(printf '%s' "$manifest" | tr '\n' ' ')

  # ---- what the server itself reported ----------------------------------------------------------
  # An exit code is not a test count: a harness that booted, ran nothing and shut down cleanly exits
  # zero, which looks exactly like a green one.
  passed=$(grep -oE 'All [0-9]+ required tests passed' "$log" | grep -oE '[0-9]+' | head -1)
  failed=$(grep -oE '[0-9]+ required tests? failed' "$log" | grep -oE '^[0-9]+' | head -1)
  verdict="NO-COUNT"
  if [ -n "${failed:-}" ]; then
    verdict="FAILED:${failed}"
  elif [ -n "${passed:-}" ] && [ "$passed" -gt 0 ] && [ "$code" -eq 0 ]; then
    verdict="passed:${passed}"
  fi
  [ "$contaminated" -eq 0 ] || verdict="SETUP-INVALID"

  printf '%-16s %-14s exit=%-3s %s  %s  %s-%s\n' \
    "$name" "$verdict" "$code" "$manifest" "$providers" "$started" "$ended" >> "$OUT/summary.txt"
done
cat "$OUT/summary.txt"
