#!/usr/bin/env bash
# Can the harness receipt refuse? Drives the real bench/harness-receipt.sh against copies of a real
# series, damaged one claim at a time.
#
# The receipt used to print a page and exit 0 whatever it said, including rows reading UNVERIFIED, so
# nothing downstream could tell a certified series from an uncertified one and the page was cited as
# though it certified. Now its exit status carries the claim, which means the status has to be shown
# capable of being 1 for each reason separately, and 0 on a series that is genuinely fine.
#
# The damage is applied to COPIES. The series itself is never touched: a series is one frozen attempt
# and re-running or editing it destroys the only record of what happened.
#
#     bash bench/receipt-control.sh [series-directory]
set -u
cd "$(dirname "$0")/.."
SERIES="${1:-build/harness-0.9.0}"
SCRATCH="build/receipt-control"

if [ ! -d "$SERIES" ]; then
  echo "No series at $SERIES to control against. Name one." >&2
  exit 2
fi

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"
cp -r "$SERIES" "$SCRATCH/good"

failures=0
check() {
  local name="$1" want="$2" dir="$3"
  bash bench/harness-receipt.sh "$dir" > "$SCRATCH/$(basename "$dir").out" 2>&1
  local got=$?
  if [ "$got" -eq "$want" ]; then
    printf '  PASS  %s (exit %s)\n' "$name" "$got"
  else
    printf '  FAIL  %s (wanted exit %s, got %s)\n' "$name" "$want" "$got"
    failures=$((failures + 1))
  fi
}

# The positive control, and it is not optional: a receipt that refused everything would satisfy every
# case below while certifying nothing, which is the failure mode this whole file exists to catch.
check "an intact series is certified" 0 "$SCRATCH/good"

cp -r "$SCRATCH/good" "$SCRATCH/no-inventory"
rm -f "$SCRATCH/no-inventory/stock.providers.txt"
check "a missing recorded inventory is refused" 1 "$SCRATCH/no-inventory"

cp -r "$SCRATCH/good" "$SCRATCH/bad-provider"
sed -i 's/single candidate provider for the harness id/NOT ESTABLISHED/' \
  "$SCRATCH/bad-provider/stock.providers.txt"
check "an inventory that established nothing is refused" 1 "$SCRATCH/bad-provider"

# A log with no result line has no failure line in it either. That is the run that must not pass, and
# it is why the result is read for a PASS rather than for the absence of a failure.
cp -r "$SCRATCH/good" "$SCRATCH/no-result"
grep -v "required tests passed" "$SCRATCH/good/stock.log" > "$SCRATCH/no-result/stock.log"
check "a log reporting no result at all is refused" 1 "$SCRATCH/no-result"

cp -r "$SCRATCH/good" "$SCRATCH/bad-manifest"
sed -i 's/"pathweaver_gametest"/"pathweaver_gametest_audited"/' \
  "$SCRATCH/bad-manifest/stock.manifest.json"
check "a manifest that disagrees with the harness is refused" 1 "$SCRATCH/bad-manifest"

mkdir -p "$SCRATCH/empty"
check "a directory that is not a series is refused, not blamed" 1 "$SCRATCH/empty"

# The last harness the roster defines, whatever it is. The receipt once kept a private list that
# stopped one short of the roster, and a series missing the harness it did not know about passed.
. bench/lib/roster.sh
last="${ROSTER[${#ROSTER[@]}-1]%%:*}"
cp -r "$SCRATCH/good" "$SCRATCH/no-last-harness"
rm -f "$SCRATCH/no-last-harness/$last.log"
check "a series missing the roster's last harness ($last) is refused" 1 "$SCRATCH/no-last-harness"

echo
if [ "$failures" -eq 0 ]; then
  echo "  all control cases behaved as required"
  exit 0
fi
echo "  $failures CONTROL CASES FAILED"
exit 1
