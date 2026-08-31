#!/usr/bin/env bash
# Repeat the A/B. One run per arm is a number, not a measurement.
set -u
cd "$(dirname "$0")/.." || exit 1
for i in "$@"; do
  bash bench/server-bench.sh "brainsink-on-$i"  "" true  full 90 60 || echo "on-$i returned $?"
  bash bench/server-bench.sh "brainsink-off-$i" "" false full 90 60 || echo "off-$i returned $?"
done
echo "pairs complete"
