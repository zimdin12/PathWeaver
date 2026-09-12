#!/usr/bin/env bash
# The frozen 0.9.0 harness roster: nine harnesses, this order, all nine required green. The ninth, lod,
# was added when distance LOD was found dropping refreshes instead of delaying them.
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

# A NEW SERIES GETS A NEW DIRECTORY. Never a deleted one.
#
# This exists because of a specific loss. To get a clean run I once ran `rm -rf` on the previous
# series directory, which destroyed the only copies of two harness failure logs: one unexplained
# stock failure and one unattributed refused failure. Neither can ever be re-examined. No later run
# repairs that; the evidence is simply gone.
#
# So reuse fails closed. If the directory exists, this refuses to start and tells the caller to name
# a new one. Refusing to run is recoverable in a way that deleting evidence is not.
if [ -e "$OUT" ]; then
  echo "REFUSING TO START: $OUT already exists." >&2
  echo "A series never overwrites or deletes a previous series directory. Pass a new path:" >&2
  echo "    bash bench/harness-roster.sh build/harness-\$(git rev-parse --short HEAD)-\$(date -u +%H%M%S)" >&2
  exit 2
fi
mkdir -p "$OUT"

# name : gradle flag : the source manifest that harness is supposed to run
#
# The expectation is DERIVED from that file rather than written out here, because two harnesses share
# a mod id (breaker reuses the stock one) and a list of ids would have called a swapped breaker run
# correct. Content distinguishes them; an id does not.
. "$(dirname "$0")/lib/roster.sh"

# The identity this series ran at, written once, so a receipt never has to guess it from the
# checkout it happens to be printed from.
git rev-parse HEAD > "$OUT/series-commit.txt"
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
  # The exit status is READ, not discarded. It used to be: the scanner's verdict line was printed in
  # the summary and governed nothing, so a row reading "NOT ESTABLISHED" still carried the verdict
  # passed:2. A sentence saying the attribution failed, sitting beside a verdict saying the run was
  # clean, is worse than not printing it, because the clean word is the one that gets quoted.
  # WHICH id. This call used to pass none, so the scan fell back to its default prefix
  # "pathweaver_gametest" for all eight harnesses, while seven of them load a longer id
  # (pathweaver_gametest_audited, _unsafe, _new_family and so on). Prefix matching made that look
  # fine: asking about pathweaver_gametest matched whichever family member was on the classpath and
  # returned a clean verdict about an id nobody had asked about.
  #
  # The claim worth making is about the id the loader REPORTED for this harness, so that is what is
  # passed, and it has to be read out of the log before the scan rather than after.
  loaded_id=$(grep -oE "pathweaver_gametest[a-z_]*" "$log" | head -1)
  python bench/classpath_providers.py "$OUT/$name.classpath.txt" "${loaded_id:-pathweaver_gametest}" \
    > "$OUT/$name.providers.txt" 2>&1
  providers_exit=$?
  providers=$(tail -1 "$OUT/$name.providers.txt")
  # A harness whose log names no mod id at all cannot have its provider set resolved against
  # anything, and must not borrow the default and pass.
  if [ -z "${loaded_id:-}" ]; then
    echo "  no mod id appears in this harness log; the provider question has no subject" \
      >> "$OUT/$name.providers.txt"
    providers_exit=1
  fi

  # ---- what it consumed, and whether that is what it was supposed to run ------------------------
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
  # A harness whose provider set could not be resolved still produced a real test result, and that
  # result is kept. What it did NOT produce is the right to say which jar supplied the mod, so the
  # verdict is marked rather than the count being thrown away. UNATTRIBUTED- sorts and greps
  # differently from passed:, which is the point: nothing downstream can count it as clean by
  # matching on the count alone.
  if [ "$providers_exit" -ne 0 ]; then
    verdict="UNATTRIBUTED-${verdict}"
  fi

  printf '%-16s %-14s exit=%-3s %s  %s  %s-%s\n' \
    "$name" "$verdict" "$code" "$manifest" "$providers" "$started" "$ended" >> "$OUT/summary.txt"
done
cat "$OUT/summary.txt"
