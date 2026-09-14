# Enhanced Cats back in, a second pair: written before running it

Committed 2026-09-14, after the h1 series and before this pair. Exploratory: it follows up a number
H5 reported without a prediction, so it was chosen after seeing that number.

## Why

In h1's single reported round with Enhanced Cats added back, synchronous `PathFinder.findPath` on the
Render thread (spark, `bench/client/frame_by_thread.py`) was 24,720 ms with release 0.9.0 and 5,516 ms
with the candidate at 3d10163. The fixes remove a per-node cost that the Render thread's searches pay
too, but a per-node cost measured at 10-30% of search time cannot by itself make a search 4.5 times
cheaper. One pair cannot tell a real effect from how many villagers happened to be near the cats.

## The pair

Same scenario, jars and player config as h1, order reversed: `h1-e2-cand`, then `h1-e2-v090`.

## The rule

**Repeats** if in this pair the candidate's Render-thread `findPath` time is again below half of
0.9.0's. **Does not repeat** otherwise. Either way both pairs are reported, and no claim about the
Render thread is made from fewer than two pairs agreeing.
