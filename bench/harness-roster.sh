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
# mixin config. Nothing puts them back: a copy task does not remove files a previous run added, and
# --rerun-tasks re-runs the copy without restoring what was overwritten. So a harness inherits
# whichever manifest the previous one left, and runs a different mod id, a different test set and a
# different tier from the one its name claims. A run of this roster did exactly that: the STOCK
# harness executed with the audited-routing manifest.
#
# An exit code cannot see that. Neither can a green count. So every run records the manifest the
# harness actually consumed, and the caller checks it against what that harness was supposed to be.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"
mkdir -p "$OUT"

# name : gradle flag : the source manifest that harness is supposed to run
#
# The expectation is DERIVED from that file rather than written out here, because two harnesses share
# a mod id (breaker reuses the stock one) and a list of ids would have called a contaminated breaker
# run correct. Bytes distinguish them; an id does not.
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

  # Both kinds of leftover state, before every harness.
  ./gradlew deleteGameTestRunDir --console=plain >/dev/null 2>&1
  rm -rf build/resources/gametest

  # The classpath this harness is about to launch with, captured BEFORE it launches and kept
  # beside its log. Enumerating it afterwards describes a build directory that has since been
  # rewritten, which cannot establish what the process that already ran was able to load.
  ./gradlew --init-script bench/print-classpath.gradle printGametestRuntimeClasspath -q \n    --console=plain 2>/dev/null | grep -E "^([A-Za-z]:|/)" > "$OUT/$name.classpath.txt"

  if [ -z "$flag" ]; then
    timeout 1800 ./gradlew runGameTest --rerun-tasks --console=plain > "$log" 2>&1
  else
    timeout 1800 ./gradlew runGameTest "$flag" --rerun-tasks --console=plain > "$log" 2>&1
  fi
  code=$?
  ended=$(date -u +%H:%M:%S)

  # What the harness actually consumed, and whether it is the manifest that harness is supposed to
  # run. The comparison lives in bench/manifest_verdict.py so it can be exercised on crafted inputs
  # without a server; bench/manifest_verdict_control.py does that, including two cases where the mod
  # id matches and the content does not, which is the case an id comparison cannot see.
  #
  # The loaded id ties the file on disk to the process: it is the mod Fabric reported loading.
  consumed_file="build/resources/gametest/fabric.mod.json"
  cp "$consumed_file" "$OUT/$name.manifest.json" 2>/dev/null
  loaded_id=$(grep -oE "pathweaver_gametest[a-z_]*" "$log" | head -1)
  if manifest=$(python bench/manifest_verdict.py "$consumed_file" "$expected" "${loaded_id:-}" 2>&1); then
    contaminated=0
  else
    contaminated=1
  fi
  manifest=$(printf '%s' "$manifest" | tr '
' ' ')

  # An exit code is not a test count. A harness that booted, ran nothing and shut down cleanly exits
  # zero, which looks exactly like a green one. Read what the server itself reported.
  passed=$(grep -oE 'All [0-9]+ required tests passed' "$log" | grep -oE '[0-9]+' | head -1)
  failed=$(grep -oE '[0-9]+ required tests? failed' "$log" | grep -oE '^[0-9]+' | head -1)
  verdict="NO-COUNT"
  if [ -n "${failed:-}" ]; then
    verdict="FAILED:${failed}"
  elif [ -n "${passed:-}" ] && [ "$passed" -gt 0 ] && [ "$code" -eq 0 ]; then
    verdict="passed:${passed}"
  fi
  [ "$contaminated" -eq 0 ] || verdict="SETUP-INVALID"

  # Could this harness's own classpath have supplied that mod from anywhere else? Enumerated
  # from the file captured before this harness launched, not from the tree as it stands now.
  providers=$(python bench/classpath_providers.py "$OUT/$name.classpath.txt" 2>&1 | tail -1)

  printf '%-16s %-14s exit=%-3s %s  %s  %s-%s  %s\n' \
    "$name" "$verdict" "$code" "$manifest" "$providers" "$started" "$ended" "$log" \
    >> "$OUT/summary.txt"
done
cat "$OUT/summary.txt"
