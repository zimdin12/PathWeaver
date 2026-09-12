# Raw ladder rows, 2026-09-12

One file per run, written by `bench/ladder.sh` as each rung completed. Kept whether the run agreed
with the others or not: `ladder-*.txt` is round 1, which docs/PERFORMANCE-2026-09.md discards as
unexplained, and it is here so the discard can be checked rather than taken on trust.

Columns: rung, zombies requested, total non-player entities measured, cumulative dispatches, elapsed
seconds, and spark's tick distribution as min/med/95%ile/max over the last 10 s, then the same over
the last 1 m.

The measured total runs about 30-50 above the rung. Those are item entities present before any
summon: a baseline probe counted 36, of which 35 were items and every other type probed was zero.
Items do not path, so the rung is the pathing population and the total is not.

The `.sparkprofile` files and full server logs are NOT here. They live under bench/deep/, which is
gitignored, so they exist only on the machine that produced them.
