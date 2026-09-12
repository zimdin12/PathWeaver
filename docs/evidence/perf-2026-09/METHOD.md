# How the 2026-09-12 numbers were measured

Enough to re-run it and get comparable figures, or to argue that it measured the wrong thing.

## What was run

| | |
|---|---|
| harness commit | `661e9e9` (`bench/ladder.sh`, `bench/saturate.sh`) |
| jar under test, current | `pathweaver-0.9.0+26.1.2`, built at `68dff75` |
| jar under test, previous | `pathweaver-0.8.0+26.1.2`, as published 2026-09-06 |
| Minecraft | 26.1.2, Fabric loader 0.19.3, dedicated server |
| JDK | Adoptium 25, `-Xmx12G -Xms4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled` |
| host | 32 logical cores; measured background load ~0.5 core |
| profiler | spark 1.10.172, `spark health` per rung, `spark profiler --thread * --not-combined` for the pair |

The server is a full 219-mod pack, not a clean install. That makes the denominator realistic and the
figures specific: the datapack share below is this pack's content, and a different pack has a
different one.

## Arms

Three, each a **jar** rather than a setting:

- **off** — every `pathweaver-*.jar` held out of `mods/` for the run and put back afterwards. A
  disabled mod still routes every `createPath` through its mixin wrapper, so `enabled=false` prices
  the feature being off rather than the mod being absent.
- **0.8.0** and **0.9.0** — that jar, alone, installed for the run.

Mod settings, written fresh each run:

```json
{"configVersion":3,"enabled":<per arm>,"compatibilityTier":"UNSAFE",
 "brainSinkAsync":true,"resultCacheMode":"SHADOW"}
```

`SHADOW` measures the route cache without serving from it. Both mod arms confirmed in-log as
measuring rather than serving, so neither arm got a cache benefit the other lacked.

## Server settings

Written into `server.properties` per run and restored from a pristine copy afterwards:

```
level-name=pw-bench          pause-when-empty-seconds=0   spawn-monsters=false
spawn-animals=false          spawn-npcs=false             view-distance=10
simulation-distance=10       difficulty=easy              level-seed=20260831
sync-chunk-writes=false      max-tick-time=-1
```

Gamerules: `doMobSpawning false`, `randomTickSpeed 0`, `doDaylightCycle false`, `doWeatherCycle
false`, `doFireTick false`, `doMobLoot false`, `doImmediateRespawn true`, `maxEntityCramming 0`,
`time set midnight`. The world is deleted and regenerated per run.

## Arena

Flat stone floor at y=200 spanning 120x120, walled, three blocks of headroom, in otherwise empty sky
so worldgen cannot differ between arms. Six full-height pillars at x = -40, -24, -8, 8, 24, 40 with
2-wide gaps at z = -2..2, so a route is a search rather than a straight line.

Population is zombies with `follow_range=128` and `PersistenceRequired`, laid down on nine lanes from
a fixed seed, so the same ladder places mobs identically in every arm.

The target is one Carpet fake player in **survival** with resistance and regeneration 255. Survival
matters: a spectator is not a valid target for a hostile mob, and the swarm would have had nothing to
path towards while every control still passed. It teleports between the four corners every 11 s,
which makes the whole population re-path at once.

## What is read, and from where

`spark health` after each rung, taking the **10 second** window. The 1 minute column is recorded in
the raw rows but not used: it carries startup spikes, visible as a 3000+ ms max at the first rung of
every run.

The median is reported rather than the mean. A mean on a saturated server hides the spike behaviour
the mod is sold against.

## Controls, and what each one refuses

Built into `bench/ladder.sh`; a run that trips one is VOID rather than reported:

- the fake player never joined, so nothing had a target
- the target died, so part of the window measured mobs with nothing to chase
- fewer than two rungs completed, so nothing can show whether the harness responds to load
- a rung produced no tick distribution, which is the measurement
- the off arm still loaded pathweaver, or a mod arm's jar never loaded
- a mod arm dispatched nothing across the whole ladder

**The ladder is itself the instrument control.** If tick durations do not rise with population, the
harness is not reading the server's work and no comparison between arms means anything. They rise
from 2.8 ms at 100 mobs to ~193 ms at 10000.

The climb stops when the next rung will not fit the time cap and says which rung it reached, so a
truncated ladder cannot be mistaken for a complete one.

## Rounds

Three, each arm run once per round, ordered off then 0.8.0 then 0.9.0, interleaved by round rather
than grouped by arm so machine drift cannot masquerade as an arm difference.

**Round 1 is discarded; the reason is not established.** Files `ladder-*.rungs.txt` are that round and
are kept so the discard can be checked. Its `off` arm read 232.8 ms at the top rung against 192.7 and
194.4 later, and its `0.9.0` arm read high at the middle rungs. Two arms affected, so not
arm-specific. Background load measured afterwards was ~0.5 of 32 cores, which does not explain it.

Conclusions rest on rounds 2 and 3 agreeing with each other to about 2%, not on any diagnosis of
round 1. Round 1 alone would have reported 0.9.0 as a regression, and it is not one.

## The profiling pair

Separate from the ladder: `bench/saturate.sh <label> <on|off> 5000 30 90`, a fixed 5000-mob
population, 30 s settle, 90 s of sampling with the player moved every 15 s. Read with
`bench/spark_summary.py` for the pathfinding share and `bench/profile_costs.py` for everything else.

Attribution note that cost a wrong answer once: spark's call tree is flattened, with a node pool in
`children` and the shape in `children_refs`. Walking `children` directly double counts the tree. Both
tools sum the roots against the thread total and refuse to print if they disagree.

## Reproducing

```
PW_SERVER=/path/to/server PW_JAVA=/path/to/java.exe \
  bash bench/ladder.sh <label> <off|/path/to/jar> 100 500 1000 2500 5000 10000
```

`BUDGET` and `RESERVE` are seconds and override the 300/45 defaults; the runs above used
`BUDGET=600 RESERVE=90`. No path in these scripts is specific to the machine that ran them.

## Known limits

One arena, one mob type, a deliberately dense population, 10 second windows, two usable rounds.
Zombies all chasing a single target is the best case for route sharing rather than a typical server.
Differences smaller than about 3% between arms are not resolvable here.
