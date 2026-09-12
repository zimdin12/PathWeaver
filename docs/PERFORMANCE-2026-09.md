# What PathWeaver is actually worth, measured

First performance measurement of this project taken on a server that was not idle. Every number the
0.8.0 page published came off a server 87% parked waiting for the next tick, at 5-6 ms against a
50 ms budget. Those numbers compare arms to each other and answer nothing a struggling server owner
asked. These replace them.

Run 2026-09-12 on the 219-mod dedicated server, MC 26.1.2, 32 logical cores.

## Method

`bench/ladder.sh` boots once and climbs a population ladder, reading spark's tick distribution at
every rung: 100, 500, 1000, 2500, 5000, 10000 zombies in a 120x120 walled arena with pillars, all
pathing at one survival fake player that teleports between corners to force the whole population to
re-path at once.

Three arms, each a jar rather than a setting: **off** holds the jar out of `mods/` entirely, because
a disabled mod still routes every `createPath` through its mixin wrapper and that prices the feature
being off rather than the mod being absent. Then **0.8.0** and **0.9.0**, same arena, same seed.

The ladder is also the instrument's own control. If tick durations do not rise as the population
climbs, the harness is not reading the server's work and no comparison between arms means anything.

**Round 1 of three is discarded and the reason is not established.** Its `off` arm reported 232.8 ms
median at 10000 against 192.7 and 194.4 in the two later rounds, and its `0.9.0` arm read high at
every middle rung. The anomaly hits two arms, so it is not arm-specific, and the machine's background
load measured later was about half a core out of 32, which does not explain it. Rounds 2 and 3 agree
with each other to about 2% and everything below rests on that agreement, not on a diagnosis of
round 1.

Reporting it at all matters: round 1 alone said 0.9.0 was a regression, and it was not.

## Median tick, rounds 2 and 3 averaged (ms, 10 s window)

| zombies | off | 0.8.0 | 0.9.0 | mod vs off |
|---:|---:|---:|---:|---:|
| 100 | 2.8 | 2.7 | 2.5 | ~5% |
| 500 | 7.4 | 6.65 | 6.7 | 10% |
| 1000 | 13.2 | 12.45 | 12.35 | 6% |
| 2500 | 34.0 | 31.0 | 31.1 | 9% |
| 5000 | 77.6 | 72.5 | 73.4 | 6% |
| 10000 | 193.6 | 181.7 | 182.8 | 6% |

**0.9.0 does not regress against 0.8.0.** They match within noise at every rung, and their dispatch
counts match too (33,824 and 33,561 against 33,680 and 33,752 at the top rung).

## Why it is 6%, and why that is the ceiling

A separate profiling pair at 5000 zombies, 90 s of spark sampling per arm:

| | off | on (0.9.0) |
|---|---:|---:|
| tickServer sampled | 90,920 ms | 89,832 ms |
| pathfinding on the server thread | 5,560 ms (**6.12%**) | 1,396 ms (**1.55%**) |
| MSPT | 88.10 | 83.41 |

Pathfinding is about 6% of server tick cost under this load. PathWeaver moves about three quarters of
it off the server thread. The measured tick improvement, 5-6%, is what that predicts.

So the mod is already near its own ceiling. At most about 1.5 points of tick remain available to any
further pathfinding work, and that is the entire remaining prize in this direction.

## Where the other 94% goes

Server thread, mod off, 5000 zombies, as a share of `tickServer`. These nest, so they do not sum.

| subtree | share |
|---|---:|
| `EntityTickList.forEach` (all entity ticking) | 81.4% |
| `LivingEntity.aiStep` | 59.0% |
| `Mob.serverAiStep` | 20.2% |
| **`LivingEntity.pushEntities` (entity collision)** | **17.5%** |
| `ServerFunctionManager.execute` (datapack tick functions) | 15.0% |
| `GoalSelector.tick` | 15.6% |
| `LivingEntity.travel` | 15.0% |
| pathfinding | 6.1% |

Two things stand out.

**Entity collision is about three times the prize pathfinding was**, and it is already Lithium
territory: the whole 17% runs through `WorldHelper.getPushableEntities` and
`EntitySection.lithium$collectPushableEntities`. Beating an optimised path is harder than beating an
unoptimised one, and worth knowing before anyone starts.

**Datapack tick functions cost this pack more than twice what pathfinding does.** That is this
server's own content, not the arena: animal_feeding_trough, the animalgarden packs, mr_haul and the
rest auto-load. It is the cheapest performance win available here and it needs no mod at all, only a
decision about which packs earn their tick.

## What this does not establish

- One arena, one mob type. Zombies all chasing a single target is the best case for route sharing,
  not a typical server.
- The population is dense by construction: 10000 mobs in 120x120 makes collision cost more
  prominent than it would be on a spread-out server.
- Tick numbers are spark's 10 s window at the end of each rung, chosen so the window lands on a
  re-path burst. The 1 m column in the raw rows carries startup spikes and is not used here.
- Two rounds, not twenty. Differences smaller than about 3% between arms are not resolvable.
- `off` removes the jar, so the off arm also lacks the mixin wrapper. That is deliberate, and it
  means these figures price the mod's presence rather than a config toggle.

## The honest summary

PathWeaver removes about three quarters of pathfinding cost from the server thread, which is worth
6-10% of tick across two orders of magnitude of population. It is consistent, it never cost anything
in a clean round, and 0.9.0 holds what 0.8.0 won.

It does not rescue a collapsed server. At 10000 zombies every arm sits near 180-195 ms against a
50 ms budget; taking 6% off an unplayable number leaves it unplayable. The benefit does not grow with
load, which is the shape you would see if pathfinding were the dominant cost and it is not.
