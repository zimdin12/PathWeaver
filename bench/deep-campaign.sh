#!/usr/bin/env bash
# Two interleaved rounds of all three arms. Interleaved, so a machine that drifts warmer or busier
# over the campaign cannot show up as an effect of the setting.
set -u
cd "$(dirname "$0")/.." || exit 1
for round in 1 2 3; do
  for arm in off sync async; do
    echo "### round $round arm $arm  $(date +%H:%M:%S)"
    bash bench/deep-bench.sh "${arm}-${round}" "$arm" 120 120 || echo "   ${arm}-${round} returned $?"
  done
done
echo "campaign complete $(date +%H:%M:%S)"
