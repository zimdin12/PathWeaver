#!/usr/bin/env bash
# What the shared route cache does on a real pack, and whether anything else moved.
#
# Five arms, interleaved, three rounds. Interleaved so a machine that drifts warmer or busier over
# ninety minutes cannot show up as an effect of a setting.
#
#   vanilla  the jar is not installed          what the pack costs without this mod at all
#   off      installed, enabled=false          what the feature costs to have switched off
#   async    0.8.0 with the cache OFF          the control the two cache arms are measured against
#   shadow   0.8.0 as it ships                 how often mobs repeat a search, and what asking costs
#   serve    0.8.0 with the cache serving      what spending those hits is actually worth
#
# shadow and serve are separate arms because they answer questions neither can answer for the other.
# One arm would leave "the cache found four thousand reusable routes" and "the cache saved nothing"
# reported by the same number.
set -u
cd "$(dirname "$0")/.." || exit 1
START=$(date +%s)
for round in 1 2 3; do
  for arm in vanilla off async shadow serve; do
    echo "### round $round arm $arm  $(date +%H:%M:%S)"
    bash bench/deep-bench.sh "${arm}-${round}" "$arm" 120 120 || echo "   ${arm}-${round} returned $?"
  done
done
echo "campaign complete $(date +%H:%M:%S), $(( ($(date +%s) - START) / 60 )) minutes"
