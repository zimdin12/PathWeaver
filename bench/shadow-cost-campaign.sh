#!/usr/bin/env bash
# Does measuring still cost anything now that it stops copying routes it will never hand out?
#
# Two arms only, because that is the whole question. The first campaign measured shadow at 6.6% more
# total pathfinding CPU than async, three runs against three with no overlap, and the cause was a
# deep copy per stored route that shadow mode never used. Re-run the same pair against the fixed
# build; anything else would be measuring a different question with the same machine time.
#
# Labels are prefixed so these runs cannot be averaged in with the five-arm campaign's, which ran on
# the build that had the defect.
set -u
cd "$(dirname "$0")/.." || exit 1
START=$(date +%s)
for round in 1 2 3; do
  for arm in async shadow; do
    echo "### round $round arm $arm  $(date +%H:%M:%S)"
    bash bench/deep-bench.sh "fixed-${arm}-${round}" "$arm" 120 120 || echo "   ${arm}-${round} returned $?"
  done
done
echo "campaign complete $(date +%H:%M:%S), $(( ($(date +%s) - START) / 60 )) minutes"
