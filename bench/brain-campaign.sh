#!/usr/bin/env bash
# Does offloading the brain-mob movement sink still pay, on the build that ships?
#
# brainSinkAsync was measured once, for 0.7.0, on the old defaults and before the route cache
# existed. Every arm of the route-cache campaign had it ON, so that campaign says nothing about it:
# a setting held constant across every arm is not a setting the campaign measured.
#
# Two arms, the cache OFF in both, so the only thing that moves is the sink.
#   sync   brainSinkAsync=false   what 0.7.0 did: everything except brain mobs
#   async  brainSinkAsync=true    what 0.8.0 ships
set -u
cd "$(dirname "$0")/.." || exit 1
START=$(date +%s)
for round in 1 2 3; do
  for arm in sync async; do
    echo "### round $round arm $arm  $(date +%H:%M:%S)"
    bash bench/deep-bench.sh "brain-${arm}-${round}" "$arm" 120 120 || echo "   ${arm}-${round} returned $?"
  done
done
echo "campaign complete $(date +%H:%M:%S), $(( ($(date +%s) - START) / 60 )) minutes"
