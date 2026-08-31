# Benchmark harness

The 0.6.1 numbers on the project README were measured by hand and no scenario was kept, so they can
neither be reproduced nor re-measured. `docs/evidence/README.md` describes the setup in prose. That
is not enough to run it again. This directory is the fix.

## What it does

`server-bench.sh` runs the dedicated 26.1.2 pack headless on a throwaway superflat world, summons a
fixed mob population from a fixed seed, lets it settle, and takes a spark profile of the server
thread. The only thing that differs between two runs is the setting under test.

```
bash bench/server-bench.sh <label> <outdir> <brainSinkAsync true|false> full <settle-s> <sample-s>
```

It backs up `server.properties` first and restores it on exit, including on failure.

The population is 120 villagers and 100 mixed animals over a 60x60 flat area, seeded at 20260831 so
both arms get an identical layout. Villagers are the point: they are brain mobs, and
`MoveToTargetSink` is the route the brain sink acts on.

## Reading the result

```
python bench/spark_summary.py "label=path/to/x.sparkprofile" ...
```

Profiles are saved locally, never uploaded. An upload publishes the pack's composition to a third
party, which is not ours to do.

**The parser is validated against a known answer.** Run it on the two retained 0.6.1 profiles in
`docs/evidence/` and it reports 4.92% and 12.38%, which are exactly the figures published for those
files. A parser that cannot reproduce a number someone already checked is not an instrument yet.

Two things it gets right that the obvious reading gets wrong, both of which produced wrong numbers
first:

- spark's call tree is flattened. A `ThreadNode` holds a pool of nodes in `children` and the tree
  shape in `children_refs`. Treating the pool as direct children double counts everything.
- Attribution is by outermost matching frame, once. Most A* cost sits in block lookups *below* the
  pathfinder frames, so self time misses it; adding a nested match to its own ancestor counts it
  twice.

The self-check refuses to print a figure when the roots do not sum to the thread total.
