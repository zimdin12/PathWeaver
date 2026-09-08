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

  if [ -z "$flag" ]; then
    timeout 1800 ./gradlew runGameTest --rerun-tasks --console=plain > "$log" 2>&1
  else
    timeout 1800 ./gradlew runGameTest "$flag" --rerun-tasks --console=plain > "$log" 2>&1
  fi
  code=$?
  ended=$(date -u +%H:%M:%S)

  # What the harness actually consumed, read from the manifest its own run left in place, and
  # compared byte for byte against the file that harness is supposed to run. The stock manifest is
  # generated rather than copied, so its comparison is by mod id; every other harness copies its
  # source verbatim and is compared by digest.
  consumed_file="build/resources/gametest/fabric.mod.json"
  cp "$consumed_file" "$OUT/$name.manifest.json" 2>/dev/null
  consumed_id=$(grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' "$consumed_file" 2>/dev/null     | head -1 | grep -oE '"[^"]+"$' | tr -d '"')
  expected_id=$(grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' "$expected" 2>/dev/null     | head -1 | grep -oE '"[^"]+"$' | tr -d '"')
  consumed_sha=$(sha256sum "$consumed_file" 2>/dev/null | cut -c1-12)
  expected_sha=$(sha256sum "$expected" 2>/dev/null | cut -c1-12)
  manifest="manifest=${consumed_id:-UNREADABLE}/${consumed_sha:-none}"
  if [ "${consumed_id:-x}" != "${expected_id:-y}" ]; then
    manifest="MANIFEST=${consumed_id:-UNREADABLE} WANTED=${expected_id:-UNREADABLE}"
    contaminated=1
  elif [ -n "$flag" ] && [ "${consumed_sha:-x}" != "${expected_sha:-y}" ]; then
    manifest="MANIFEST-BYTES=${consumed_sha} WANTED=${expected_sha}"
    contaminated=1
  else
    contaminated=0
  fi

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

  printf '%-16s %-14s exit=%-3s %s  %s-%s  %s\n' \
    "$name" "$verdict" "$code" "$manifest" "$started" "$ended" "$log" >> "$OUT/summary.txt"
done
cat "$OUT/summary.txt"
