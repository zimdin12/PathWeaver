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
#
# EXIT STATUS. This script used to print a page and exit 0 whatever it had just printed, including
# rows reading UNVERIFIED. A receipt whose status is 0 no matter what it says cannot be used by
# anything except a human who reads every line, and it was being cited as though it certified the
# series. It now exits 0 only when every harness in the roster cleared all three claims above, and 1
# otherwise, with the reason printed.
set -u
cd "$(dirname "$0")/.."
OUT="${1:-build/harness-0.9.0}"
unestablished=0
rows=0

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

# The roster the series ran, from the one definition both scripts read. See bench/lib/roster.sh.
. "$(dirname "$0")/lib/roster.sh"
for entry in "${ROSTER[@]}"; do
  h="${entry%%:*}"
  src="${entry##*:}"
  log="$OUT/$h.log"
  loaded=$(grep -oE "pathweaver_gametest[a-z_]*" "$log" 2>/dev/null | head -1)
  batch=$(grep -oE "batch [0-9]+ \([0-9]+ tests\)" "$log" 2>/dev/null | head -1)
  result=$(grep -oE "All [0-9]+ required tests passed|[0-9]+ required tests? failed" "$log" 2>/dev/null | head -1)

  if [ -f "$log" ]; then
    rows=$((rows + 1))
  else
    printf '%-16s NO LOG in this series directory\n' "$h"
    unestablished=$((unestablished + 1))
    continue
  fi
  if manifest_says=$(python bench/manifest_verdict.py "$OUT/$h.manifest.json" "$src" "$loaded" 2>&1); then
    manifest_ok=1
  else
    manifest_ok=0
  fi
  printf '%-16s %s\n' "$h" "$manifest_says"
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
  providers_ok=0
  if [ -f "$inv" ]; then
    recorded=$(tail -1 "$inv")
    printf '%-16s   providers at launch (from the recorded inventory): %s\n' "" "$recorded"
    printf '%-16s     recorded inventory: %s [sha256 %s]\n' "" "$inv" \
      "$(sha256sum "$inv" | cut -d' ' -f1)"
    case "$recorded" in
      *"single candidate provider"*) providers_ok=1 ;;
    esac
  else
    printf '%-16s   providers at launch: NO RECORDED INVENTORY -- loaded-content identity UNVERIFIED\n' ""
    printf '%-16s     not re-derived: a scan run now would describe the tree now, not that launch\n' ""
  fi

  # The result line is read for a PASS, never for the absence of a failure. A log with no result line
  # at all has no failure in it either, and that is exactly the run that must not count.
  case "${result:-}" in
    "All "*" required tests passed") result_ok=1 ;;
    *) result_ok=0 ;;
  esac

  if [ "$manifest_ok" -eq 1 ] && [ "$providers_ok" -eq 1 ] && [ "$result_ok" -eq 1 ]; then
    printf '%-16s   ESTABLISHED\n' ""
  else
    unestablished=$((unestablished + 1))
    printf '%-16s   NOT ESTABLISHED (manifest=%s providers=%s result=%s)\n' "" \
      "$manifest_ok" "$providers_ok" "$result_ok"
  fi
done

echo
if [ "$rows" -eq 0 ]; then
  echo "REFUSING TO CERTIFY: not one harness in the roster left a log in $OUT."
  echo "That is not a failed series, it is not a series; naming a wrong directory must not read"
  echo "as evidence about a run."
  exit 1
fi
if [ "$unestablished" -ne 0 ]; then
  echo "$unestablished of $rows harness rows are NOT ESTABLISHED; this receipt certifies nothing"
  exit 1
fi
echo "all $rows harness rows established: manifest agrees, single recorded provider, tests passed"
exit 0
