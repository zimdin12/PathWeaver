# "The moment I spawn a cat or a monster my game lags": written before measuring it

Committed 2026-09-14, before any run of this campaign. Four smoke runs came first; they built and
debugged the instrument and are not part of the series (three were voided by harness defects, one
completed and is described below as what prompted the design).

## The report

Steven installed the release 0.9.0 jar in his 26.1.2 client pack (317 mods) and played singleplayer.
Spawning a cat or a monster made the game lag badly. PathWeaver is the only mod that changed.

## What was already true before measuring

- Nothing in the 0.9.0 evidence ran in the client pack with a player's existing config. Every
  benchmark was a dedicated server; every boot started from no config file.
- His config is not the default: `poolThreads` 30 (default 0, which resolves to 8 on this machine) and
  `maxInFlight` 2560 (default 256). Both are accepted values.
- His session log: 92,422 searches dispatched in about 90 s, 3,035 installed, 83,379 discarded as
  "target changed". A 0.8.0 session on 2026-09-06 shows the same proportions at maxInFlight 256.
- The completed smoke run, 0.9.0 with his config: 277,602 dispatched in about 5 minutes, 87.6%
  superseded, integrated-server tick mean about 25 ms before anything was spawned. A voided smoke run in
  which no command reached the game rose from 26 to 35 ms over the same period, so the rise is not
  known to be caused by the spawn.
- A process named `dwm.exe` that is not Windows' desktop window manager holds about 8 of 32 logical
  cores continuously. It is recorded, not stopped.

## The scenario

`bench/client/client-run.sh`: the real client pack, a fresh copy of the world he played (`PW-061-test`,
an MCA village), standing where he stood. 60 s settle, then three 40-45 s phases marked in the log:
**base** (nothing spawned), **one** (a cat and a husk), **horde** (ten more husks). A measurement-only
probe mod (`bench/frameprobe`) logs frame times and integrated-server tick times every 5 s; spark
profiles every thread from the start of **one** to the end of **horde**. The probe is present in every
arm.

## Arms and order

| arm | PathWeaver | config |
|---|---|---|
| none | absent | |
| v080 | 0.8.0 as published | his |
| v090 | 0.9.0 release | his |
| v090def | 0.9.0 release | none, shipped defaults |

R1 none, v080, v090, v090def; R2 v090def, v090, v080, none. `bench/client/client-campaign.sh`.

## Measures

From `bench/client/summarize.py`, per phase, median across 5 s windows: FPS, frame p50 and p95, share of
frames over 50 ms, tick mean, p50 and p95. From the profiles: server-thread time by component, and CPU
time of the render thread, the server thread and PathWeaver's workers.

## Predictions, and what falsifies each

**K0, instrument.** `none`'s two rounds agree: base-phase tick mean within 15%. If they do not, the
scenario is too noisy to attribute anything and no C verdict is given.

**C1, is PathWeaver the cause.** v090 is worse than none if, in **both** rounds, in the one or horde
phase, tick mean is at least 10% higher **or** FPS at least 10% lower. Predicted: yes, it is worse.

**C2, is it new in 0.9.0.** Same rule, v090 against v080. Predicted: no; the churn pattern predates 0.9.0.

**C3, is it his settings.** Same rule, v090 against v090def. Predicted: v090 is worse than v090def,
because 30 workers compete with the render and server threads for the cores the other process leaves.

**Q5, machine.** `bench/machine_load.py`; a run whose background moved more than 8 points is flagged.

Whatever the verdicts, the profiles are read for every component that costs server-thread time, and each
cost found is reported, whether or not a prediction concerned it.

## What this cannot say

One world, one machine with a foreign process on it, two rounds. It can attribute the lag in this
scenario; it cannot put a number on anyone else's.
