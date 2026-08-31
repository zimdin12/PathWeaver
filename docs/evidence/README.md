# Retained evidence

`pathweaver-on.sparkprofile` / `pathweaver-off.sparkprofile` — spark profiles behind the
"Profiled on a real modpack" table in the project README. The 221-jar pack, 220 mixed mobs retargeted
every 6 ticks, 45 seconds each, `compatibilityTier=UNSAFE`, the only variable between them being the
master switch.

(These profiles were once described here as a "222-mod pack" while the README called the identical
pair a 221-jar pack. One pack, one count: 221 third-party jars in `mods/`. The published 0.4.0 and
0.5.x changelog entries still say 222 because that is what those releases measured and said at the
time, and a shipped entry is a record rather than a draft.)

Server-thread time in pathfinding: **12.38% off, 4.92% on.**

## What these profiles do not show

Re-read in August 2026, from the files themselves rather than from memory of how they were taken:

- **No brain-mob pathfinding at all.** One `Brain` frame totalling 4 ms across both files, and zero
  time in `MoveToTargetSink`. The load was goal-driven and flying navigation, bees prominent. So this
  pair is not a baseline for `brainSinkAsync`, which gates only `MoveToTargetSink`. Instrument
  control for that probe: `Bee` returns 14 and 18 distinct frames, `ZZZ_absent` returns 0.
- **The load driver no longer exists.** `dev.pathweaver.spark.SparkDriverProbe.retarget` accounts for
  3,604 of the 5,572 ms attributed to pathfinding in the off arm, called from a tick-end hook. That
  class is in neither the current source tree nor the shipped jar (0 hits, against 106 `dev/pathweaver`
  entries in the same listing, which is the control). These numbers cannot be reproduced from this
  repository.
- **One thread was sampled.** `thread_dumper { type: SPECIFIC }`. The worker threads that receive the
  moved work were never sampled in either arm, so the pair cannot distinguish moving work from
  removing it, and cannot show what the async path costs.
- **The denominator is mostly idle.** 33.1 s of 45 s parked in the on arm, 30.4 s in the off arm.
  Share-of-thread therefore understates the share of real work about fourfold, and both arms held
  20 TPS (912 and 900 ticks), so the supportable claim is headroom rather than throughput.

Measured against tick time, the same files give **15.47 ms/tick off, 12.52 ms/tick on**, and
pathfinding falls from 40.3% to 19.8% of `tickServer`. Those figures are better ones to quote.

## Reading them

Saved locally rather than uploaded. Open at <https://spark.lucko.me/> via *Load from file*, or run
`python bench/spark_summary.py "off=docs/evidence/pathweaver-off.sparkprofile"`. The call tree is
flattened: each node has `children_refs` indices into the thread node pool rather than nested objects.

These two files are also the parser regression test. `bench/spark_summary.py` must reproduce 12.38%
and 4.92% on them; a parser that cannot reproduce a number someone already checked is not an
instrument yet.

## Other artifacts

`config-screen-general.png` — the General category of the settings screen, captured by
`ClientSingleplayerGameTest` on a real client at the current build. Regenerate it with
`./gradlew runClientGameTest` and copy from `build/run/clientGameTest/screenshots/`.

Keep it in step with the language file. It is the artifact that shows labels are not truncated, so a
stale copy is worse than none: it went out of date the moment 0.5.0 renamed the tier values, and for
two days it showed "Audited (default)" for a build whose default is Unsafe.
