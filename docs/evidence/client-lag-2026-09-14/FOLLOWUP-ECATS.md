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

## Result

**Does not repeat.** Render-thread `findPath`: `h1-e2-cand` 3,396 ms, `h1-e2-v090` 4,944 ms; half of
0.9.0's is 2,472 ms. With the first pair (5,516 against 24,720 ms) the two pairs disagree, so nothing is
claimed about the Render thread. Reported alongside: integrated-server tick mean in the one and horde
phases was lower for the candidate in both pairs (27.2 / 27.2 against 34.3 / 33.1 ms, and 25.4 / 27.1
against 27.7 / 30.7 ms), and frames over 50 ms were 0.3% or less in both runs of this pair. Background
load 33.3 and 33.2%, not flagged. `raw/followup-ecats.txt`.
