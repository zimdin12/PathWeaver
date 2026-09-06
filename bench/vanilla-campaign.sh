#!/usr/bin/env bash
# Vanilla baseline, with a CARRIED CONTROL.
#
# The existing off/sync/async numbers were taken on a quiet machine. This runs on a busy one, so a
# vanilla figure taken now is not automatically comparable to them. Rather than assume it is, or ask
# seven agents to stop, this re-measures the "off" arm alongside it. If the fresh off matches the
# earlier off, the machine is comparable and vanilla joins the table. If it drifts, the result is
# known to be incomparable instead of being published as though it were not.
set -u
cd "$(dirname "$0")/.." || exit 1
for round in 1 2 3; do
  for arm in vanilla off; do
    label="$arm-c$round"
    echo "### $label  $(date +%H:%M:%S)"
    bash bench/deep-bench.sh "$label" "$arm" 120 120 || echo "   $label returned $?"
  done
done
echo "vanilla campaign complete $(date +%H:%M:%S)"
