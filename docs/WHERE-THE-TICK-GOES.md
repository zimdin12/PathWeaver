# Where the tick goes, and what could be done about it

Traced from the 2026-09-12 profiles at 5000 zombies, mod off. Method in
`docs/evidence/perf-2026-09/METHOD.md`; the summary of what PathWeaver is worth is in
`docs/PERFORMANCE-2026-09.md`.

This exists to stop the next piece of work being chosen from a guess. Every share below was read from
a profile, and each candidate is judged on what the profile says rather than on what sounds slow.

Shares are of `tickServer` and they **nest**, so they do not sum to 100.

| subtree | share of tick |
|---|---:|
| all entity ticking | 81.4% |
| `LivingEntity.aiStep` | 59.0% |
| `Mob.serverAiStep` | 20.2% |
| entity spatial queries (`EntitySectionStorage.forEachAccessibleNonEmptySection`) | 19.1% |
| `LivingEntity.pushEntities` (collision) | 17.5% |
| `GoalSelector.tick` | 15.6% |
| `LivingEntity.travel` | 15.0% |
| datapack tick functions | 15.0% |
| pathfinding | 6.1% |

## `GoalSelector.tick` — 15.6%

**What it is.** Every tick, for every mob, the goal scheduler walks that mob's goal list and asks each
goal whether it should start (`canUse`) or keep running (`canContinueToUse`), resolves conflicts
between goals competing for the same control flags, and starts or stops goals accordingly. It is the
mob's decision layer: not the AI doing work, the AI deciding which work to do.

**What it is made of:**

| inside GoalSelector | share of it | of tick |
|---|---:|---:|
| `WrappedGoal.canUse` | 36.0% | 5.6% |
| ↳ `NearestAttackableTargetGoal.findTarget` → `getEntitiesOfClass` | 13.7% | 2.1% |
| `WrappedGoal.start` | 32.8% | 5.1% |
| flag bookkeeping (`goalContainsAnyFlags`, `getFlags`) | 6.3% | 1.0% |

**Can a mod optimise it?** Partly, and the two halves are very different.

`start` at 5.1% is mostly goals kicking off work, path computation included. That is already
PathWeaver's territory and is counted inside the 6.1% pathfinding figure. Little new there.

`canUse` at 5.6% is the real candidate, and inside it the target scan is the concrete thing: each
hostile mob asks "is there a target near me?" by fetching entities of a class from the world, every
tick, independently. With a large population that is thousands of overlapping spatial queries per
tick computing nearly the same answer.

Two approaches, both real:

- **Stagger the scans.** Run each mob's target search every N ticks instead of every tick, offset by
  entity id so the cost spreads. Cheap to build. The cost is behavioural: mobs notice you up to N
  ticks later. At N=4 that is 200 ms of extra reaction time, which is perceptible to a player who is
  looking for it.
- **Share the scan.** One spatial query per section per tick, reused by every mob asking a compatible
  question. No behaviour change if the answer is identical, which is the hard part: target conditions
  differ per mob (follow range, line of sight, invisibility, team).

Realistic prize: 1-3% of tick. Worth doing, not transformative, and the safe version is the harder
one.

## `LivingEntity.travel` — 15.0%

**What it is.** The movement step. Applies gravity, friction, fluid effects and the mob's current
movement input to produce a velocity, then moves the entity. 97.8% of it is `travelInAir`, which is
the ordinary not-in-fluid case.

**What stands out inside it:** `getBlockPosBelowThatAffectsMyMovement` → `Entity.getOnPos` at 11.2%
of travel, about 1.7% of tick. That is each entity working out which block it is standing on, every
tick, including block state fetches through the palette.

**Can a mod optimise it?** Less than it looks.

The block-below lookup is the only obviously redundant part, and it is cacheable per entity while the
entity has not moved between blocks. Lithium already does work in this area. Prize is 1-2% of tick,
low behavioural risk, and it is fiddly: the cache has to be invalidated on block changes, which is
the same class of problem PathWeaver's route cache already solves and got wrong once.

The rest of `travel` is physics that genuinely has to run per entity per tick. It is not a candidate
for the PathWeaver treatment: pathfinding could be moved off-thread because a search is a pure read
of a snapshot, but `travel` **writes** — it sets position and velocity, and it resolves collision
against other entities that are themselves being moved. Running it in parallel is the shared-mutable-
state problem in its purest form.

**Verdict: low priority.** Small prize, real risk, already partly optimised.

## Datapack tick functions — 15.0%

**Can they be made async?** No. And that is the wrong question, because the profile says most of the
cost is not what people assume.

Why not async: a datapack function runs arbitrary commands, and commands mutate arbitrary world
state — `setblock`, `summon`, `data merge`, scoreboard writes. There is no type-level restriction on
what a command touches. This is the same hazard that got whole-brain-tick offloading rejected for this
project, in a worse form: brain ticks at least mutate a known set of things, and a command does not.

But look at what the 15% actually is:

| inside datapack function execution | share of it |
|---|---:|
| entity selector evaluation (`EntityArgument.getOptionalEntities` → `EntitySelector.findEntities` → `ServerLevel.getEntities`) | **~57%** |
| scoreboard reads (`ExecuteCommand.checkScore`) | ~5% |

Roughly **57% of the datapack cost is `@e[...]` selectors walking the entity list.** That is a pure
read. It changes nothing, it just finds things, and it is the part that scales with how many entities
are in the world — which is exactly why it is so visible at 5000 mobs.

So there are three routes, in descending order of sense:

1. **Audit which packs have tick functions and whether they earn it.** Costs nothing, needs no mod,
   and on this pack it is the single largest available win. Some of these are decorative packs
   running a selector every tick forever.
2. **Index the selectors.** A mod that accelerates `@e[type=...,distance=...]` with a type and
   spatial index, returning an identical set, is a pure optimisation with no behaviour change. This
   is the honest "next mod" candidate from this profile.
3. **Rate-limit function execution.** Run a tick function every N ticks. Effective and trivial, but
   it changes pack behaviour, and unlike mob reaction time the consequences are unknowable from the
   outside: you cannot tell from the mod side whether a pack's function is idempotent.

## Entity collision (`pushEntities`) — 17.5%

Not asked about, but it is the largest single item after entity ticking itself and it deserves a line
so the ranking is not misleading.

Every living entity asks the world for entities it overlaps and pushes them apart. At 17.5% it is
about three times what pathfinding costs. The whole of it already runs through Lithium:
`WorldHelper.getPushableEntities` and `EntitySection.lithium$collectPushableEntities` are in the
stack. So the largest prize on the board is the one already attacked by the best-known optimisation
mod in the ecosystem, and beating optimised code is a different proposition from beating unoptimised
code.

It is also inflated by this arena: 10000 mobs in 120x120 is denser than a real server. Treat 17.5% as
an upper bound for this shape of load rather than a general figure.

## Ranking, if the next thing is chosen from this profile

1. **Datapack selector indexing** — ~8% of tick on this pack sits in selector scans, it is a pure
   read, and no behaviour changes. Best prize-to-risk on the board.
2. **Shared or staggered target scans** — 1-3%, moderate risk, well-trodden ground.
3. **Block-below caching in `travel`** — 1-2%, low risk, small.
4. **Anything further in pathfinding** — about 1.5% remains after what PathWeaver already takes. This
   direction is close to finished.

And the free one, which is not a mod: on this pack, deciding which datapacks deserve a tick function
is worth more than items 2, 3 and 4 combined.
