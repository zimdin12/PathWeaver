# Path request patterns in a 314-jar pack (September 2026)

## Why this exists

Enhanced Cats calls `PathNavigation.moveTo(...)` for every villager near every cat, every tick. In an
earlier PathWeaver run with it installed, 88% of server path requests came from that mod and 88.5% of
async searches were thrown away because a newer request for the same mob replaced them. Those two
figures come from that earlier telemetry and were not re-measured here.

Before designing a general fix for request storms in 0.10 (repeated requests, especially to moving
targets, every tick), we need to know how common the pattern is. This note measures that with a static
bytecode scan of the real pack. It does not design the fix.

## Short answer

- **37 of the 314 top-level jars** contain at least one path-request call site (242 sites). 36 of
  them call it from outside a navigation implementation (213 sites). The 37th, stormiespiders, only
  has calls inside its own navigator classes.
- **34 mods** have a call site the scan links to a tick-driven method (206 of 213 sites). 30 of
  those rely only on direct or same-class links.
- **21 mods** request a path to an **Entity** (a moving target) from code that runs every tick
  (99 sites). 20 of those rely only on direct or same-class links.
- **16 mods** have an every-tick Entity-target site where the same method neither checks navigation
  state nor shows a throttle hint.
- **One detected site** (enderzoology) makes an every-tick request inside an iterator loop over a
  list, the shape that made Enhanced Cats extreme. The loop heuristic does not see `forEach` or stream
  loops, so this is a lower bound on fan-out sites, not a finding that fan-out is rare.
- **The mod count overstates how many independent codebases do this.** 11 of the 21 every-tick
  Entity-target mods are one author's family: 10 `animalgarden_*` species mods plus `aquarius_libs`,
  each with its own copy of goals named like vanilla's (`ModFollowOwnerGoal`, `ModMeleeAttackGoal`,
  `ModTemptGoal`). Counted by codebase, it is 11.
- **Vanilla has the same call shape.** Minecraft 26.1.2 itself has 24 every-tick Entity-target sites, 19 with no
  throttle hint (for example `TemptGoal`, `OcelotAttackGoal`, the ranged attack goals and the spear
  behaviours). So a general fix would apply to vanilla mobs as well as to mods.

The shape of the pack: Entity-target request sites in `Goal.tick` are common, and nearly all look
like vanilla's per-mob follow and attack goals. These are candidate sites. "Every tick" here means the
call sits in tick-driven code, not that a search runs every tick; an Entity argument does not mean the
target moves; and nothing here shows a storm at runtime. Which sites actually storm needs runtime
counts joined by request. See the last section.

## Method

The scanner is `bench/pack/path_request_scan.py`. It uses only the Python standard library and has its
own class-file parser. It reads `mods/*.jar`, recurses into nested jars under `META-INF/jars/`, and
uses the Minecraft 26.1.2 client jar for the class hierarchy and as a baseline. It writes only the CSV.

### What counts as a call site

| kind | rule |
|---|---|
| `navigation` | `invokevirtual`, `invokespecial` or `invokeinterface` of `moveTo`, `createPath` or `recomputePath` whose owner is in the navigation family. The family is derived rather than listed: any class whose superclass chain reaches `PathNavigation`, in vanilla or in any scanned jar. A mixin class counts as the classes it targets, because a mixin calling its own `createPath` has itself as the owner. |
| `behavior_utils` | `invokestatic BehaviorUtils.setWalkAndLookTargetMemories`. |
| `walk_target` | a `new WalkTarget(...)` in a method that also calls `Brain.setMemory*` or `MemoryAccessor.set*`. This is a heuristic. |

`target_kind` comes from the first target parameter of the descriptor (`entity`, `coords`,
`blockpos`, `blockpos_set`, `vec3`, `path`, `tracker`). `BehaviorUtils`' first parameter is the mob
itself and is skipped. `moveTo(Path, double)` follows a path that was already computed, so it is
marked `requests_search=False`. Sites inside a navigation implementation (`inside_navigation`) are the
navigator itself, not a caller, and are left out of the caller counts.

The CSV repeats the containing class and method on every row. That includes the method descriptor and
the bytecode offset, so each row names one exact instruction.

### Tick-driven

A method is directly tick-driven when it is one of:

| declaring type (or mixin target) | methods |
|---|---|
| `Entity` subclass | `tick`, `aiStep`, `serverAiStep`, `customServerAiStep` |
| `Goal` subclass | `canUse`, `canContinueToUse`, `start`, `tick` |
| `Behavior` subclass | `tick`, `checkExtraStartConditions`, `canStillUse` |
| `PathNavigation` subclass | `tick` |

A mixin handler also counts when a mixin annotation's `method` selector names one of those methods.
A synthetic `lambda$tick$0` counts as its enclosing method.

Links are then followed and labelled by evidence level, stored in the `tick_evidence` column:

- **direct.** The method itself matches the rule above.
- **same_class.** Reached through calls inside the same class, taken to a fixed point. This is how
  Enhanced Cats is found: `onTick` is `@Inject(method = "tick")` and calls `BabyvillagerAnnoysCat`,
  which calls `getfish`.
- **cross_class.** A bounded reverse search, four levels deep, for callers in other classes. A call to
  `(owner O, name, desc)` reaches method `C.name+desc` when `O == C`, when `O` is a subtype of `C`, or
  when `C` is a subtype of `O`. That last case, downward dispatch through a `java/` or `net/minecraft/`
  type, is accepted only from a caller in the same jar as `C`. Without that limit, one mod's goal calling
  `PathNavigation.moveTo` linked every navigator override in the pack. A lambda is linked to the method
  that creates it, and the reason text says `lambda created in`. That tells you where the lambda was
  made, not how often it runs. This level over-approximates, which is why the summary gives counts
  with and without it.

`cadence` is `on_start` when the root of the chain is `Goal.start` (once per activation), and
`every_tick` otherwise. `canUse` runs on every evaluation while the goal is idle, so it counts as
every tick.

### Two weak hints (columns, not conclusions)

- **`throttle_hint`.** The same method calls `adjustedTickDelay` or `reducedTickDelay`, or uses integer
  `%`. It catches vanilla `FollowOwnerGoal`'s `timeToRecalcPath`. It misses hand-rolled counters,
  game-time comparisons (vanilla `MeleeAttackGoal.canUse` throttles on `lastCanUseCheck` and shows no
  hint) and throttles that live in the caller. Treat its absence as "unknown", not as "unthrottled".
- **`in_iterator_loop`.** The call's offset lies inside a backward-`goto` loop whose body calls
  `Iterator.next` or `List.get`. This is the fan-out shape. It does not see `list.forEach(lambda)` or
  streams.
- **`method_checks_nav_state`.** The same method calls `isDone`, `isInProgress`, `isStuck`, `getPath`,
  `getTargetPos` or `stop` on the navigation. A method that checks state may still request every tick.

## Controls

Each control was run in the same session as the results below.

| control | expected | observed | result |
|---|---|---|---|
| **Positive: Enhanced Cats** (`mods/.disabled/EnhancedCats-26.1.x-1.0.1-variantfix.jar`), `Mixins/CatEntityMixin` | the `moveTo` calls javap shows, with Entity targets, reached from the `tick` injection | 8 sites in `BabyvillagerAnnoysCat` (2), `getfish`, `cathuntsfish`, `RainyDay`, `IsAfraidOfStorm` (2), `RainyDayUntamed`. That is exactly the 8 `javap -c -p` lists for the class. 5 of them are `moveTo(Entity, double)`. All 8 are `every_tick` via `onTick <- mixin@Entity.tick`. Separately, javap confirms `onTick` calls every one of those helpers. Jar-wide: 23 sites, 12 Entity targets. | PASS |
| **Negative jar: Sodium** (`mods/sodium-fabric-0.8.12+mc26.1.2.jar`, 9 nested jars) | zero | The scanner reports 0 sites and 0 rejected calls across 1,259 classes. An independent probe (extract everything, `grep -r -a -l` over `.class` files) finds 0 files containing `ai/navigation/`, `createPath`, `recomputePath`, `setWalkAndLookTargetMemories` or `ai/memory/WalkTarget`. It finds one file containing `moveTo`, and that is the string `moveToTheEnd` in Fabric API's `ModPackResourcesUtil`. The same probe on Enhanced Cats finds 19 and 12 files. The probe's first version returned 0 on Enhanced Cats too (unzip's pattern matching failed), so that version was discarded. | PASS |
| **Negative owner: Axiom** (`mods/Axiom-5.4.2-for-MC26.1.jar`) | a `moveTo` on an unrelated owner is not counted | 0 sites and 17 rejected calls, all `com/moulberry/axiom/gizmo/Gizmo.moveTo(BlockPos)V`. javap confirms the owner and signature. Across the pack, 83 request-named calls were rejected, and every rejected owner resolved in the hierarchy: Axiom `Gizmo`, Create schematics and factory panels, e4mc/netty QUIC, owo UI components, Warband goals' private `moveTo(BlockPos)` helpers (their inner navigation calls are counted), stormiespiders' `CustomPathFinder`, and yumi's file system. | PASS |
| **Decoder vs javap**, every prefiltered class in the pack | identical invokes | 341 classes: 349 request-named invokes agree, 0 only in the decoder, 0 only in javap. Runs with `--javap`. The first run of this check reported 23 mismatches because its own javap parser was broken, which also showed that the check can fail. | PASS |
| **Prefilter drops nothing**: all classes of mca, warband, ecologics, stormiespiders, carpet, better-dogs and sodium | the same invokes with and without the byte prefilter | 3,628 classes unfiltered and 89 prefiltered: 96 invokes in both, 0 mismatches. The prefilter is also sound by construction, because a method reference to `moveTo` needs the literal UTF-8 bytes in the constant pool. | PASS |
| **Loop hint** | Enhanced Cats' fan-out sites flagged; vanilla `FollowOwnerGoal` not flagged | `BabyvillagerAnnoysCat`, `getfish` and `cathuntsfish` flagged. `IsAfraidOfStorm` and `RainyDay` not flagged. Vanilla `FollowOwnerGoal.tick` and `TemptGoal.navigateTowards` not flagged. | PASS |
| **Throttle hint** | Enhanced Cats none; vanilla `FollowOwnerGoal` yes | as expected. It misses vanilla `MeleeAttackGoal.canUse`'s game-time throttle, as documented above. | PASS, weak |

The controls caught these bugs while the scanner was being built: carpet's mixin into `PathNavigation`
was missed until a mixin's own class counted as its target; every `setWalkAndLookTargetMemories(mob,
BlockPos)` was labelled an Entity target until the first parameter was skipped; and the cross-class
search linked every navigator to an unrelated goal until downward dispatch through platform types was
limited to the same jar.

## Pack numbers

314 top-level jars in `mods/` (615 jar units including nested jars, 75,993 classes, 0 parse errors),
plus the disabled Enhanced Cats jar as a control. Pack figures exclude the control.

| measure | sites | mods |
|---|--:|--:|
| any call site | 242 | 37 |
| caller sites (outside navigation implementations) | 213 | 36 |
| tick-driven | 206 | 34 |
| tick-driven, direct or same-class links only | 183 | 30 |
| Entity target (moving) | 110 | 21 |
| every tick + Entity target | 99 | 21 |
| every tick + Entity target, direct or same-class only | | 20 |
| every tick + Entity target, no nav-state check and no throttle hint | | 16 |
| every tick inside an iterator loop | 1 | 1 |
| `Goal.start` only (once per activation) | 51 | |
| `moveTo(Path)`: follows a precomputed path, starts no search | 21 | |
| no tick link found | 7 | |

The 7 unlinked caller sites: carpet `Villager_aiMixin.canReachHome`, herdinstinct
`HerdPanicUtil.updatePanicPath`, mca `Relationship.onTragedy`, `EnterBuildingTask` and
`PatrolVillageTask` start lambdas, and warband `IllagerGrudgeSystem.directVengeancePursuit` and
`AntiFarmDirector.inspect`. The scan found no tick-driven caller for any of them. How they are actually
reached (events, commands, Fabric tick callbacks or anything else) was not checked.

Vanilla 26.1.2 baseline, scanned with the same rules (no cross-class pass): 159 sites, 88 tick-driven,
34 Entity targets, 24 every-tick Entity targets (19 with no throttle hint), and 1 every-tick site in an
iterator loop (`Raider$ObtainRaidLeaderBannerGoal.canUse`). Outside the tick rules, vanilla
`ServerLevel.sendBlockUpdated` calls `recomputePath` in a loop over navigating mobs.

## Per mod

Sorted by tick-driven Entity-target sites. Column definitions are in Method above.

| mod id | sites | in navigation impl | caller sites | tick-driven | entity target | tick-driven + entity | every-tick + entity | ...with no nav-state check and no throttle hint | every-tick in iterator loop | tick links that are cross-class |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| vanilla-outsider-better-dogs | 21 | 0 | 21 | 21 | 12 | 12 | 10 | 7 | 0 | 0 |
| animalgarden_seaotter | 14 | 0 | 14 | 14 | 11 | 11 | 11 | 5 | 0 | 0 |
| enhanced-cats (control, disabled) | 23 | 0 | 23 | 22 | 12 | 11 | 9 | 5 | 4 | 0 |
| animalgarden_spottedhyena | 11 | 0 | 11 | 11 | 9 | 9 | 9 | 5 | 0 | 0 |
| warband | 17 | 0 | 17 | 15 | 10 | 8 | 4 | 3 | 0 | 1 |
| animalgarden_fennecfox | 13 | 0 | 13 | 13 | 7 | 7 | 7 | 2 | 0 | 0 |
| animalgarden_sugarglider | 17 | 5 | 12 | 12 | 7 | 7 | 7 | 3 | 0 | 1 |
| aquarius_libs | 12 | 0 | 12 | 12 | 7 | 7 | 7 | 2 | 0 | 0 |
| animalgarden_commonraven | 12 | 0 | 12 | 12 | 6 | 6 | 6 | 4 | 0 | 7 |
| animalgarden_westerngorilla | 11 | 0 | 11 | 11 | 6 | 6 | 6 | 2 | 0 | 0 |
| animalgarden_redpanda | 7 | 0 | 7 | 7 | 6 | 6 | 6 | 3 | 0 | 0 |
| animalgarden_whiterhinoceros | 7 | 0 | 7 | 7 | 5 | 5 | 5 | 2 | 0 | 0 |
| mca | 13 | 0 | 13 | 10 | 4 | 4 | 4 | 3 | 0 | 4 |
| enderzoology | 5 | 0 | 5 | 5 | 4 | 4 | 4 | 1 | 1 | 1 |
| ecologics | 9 | 4 | 5 | 5 | 4 | 4 | 2 | 2 | 0 | 0 |
| animalgarden_alligatorgar | 5 | 0 | 5 | 5 | 3 | 3 | 3 | 2 | 0 | 0 |
| yungscavebiomes | 11 | 0 | 11 | 11 | 3 | 3 | 2 | 0 | 0 | 0 |
| dasik-library | 2 | 0 | 2 | 2 | 2 | 2 | 2 | 0 | 0 | 2 |
| icys-better-horses | 4 | 0 | 4 | 4 | 1 | 1 | 1 | 1 | 0 | 0 |
| animalgarden_owl | 2 | 0 | 2 | 2 | 1 | 1 | 1 | 0 | 0 | 0 |
| eternalnether | 1 | 0 | 1 | 1 | 1 | 1 | 1 | 0 | 0 | 0 |
| magicdrive | 1 | 0 | 1 | 1 | 1 | 1 | 1 | 0 | 0 | 0 |
| salts_animal_farm | 8 | 0 | 8 | 8 | 0 | 0 | 0 | 0 | 0 | 0 |
| animalgarden_bullshark | 2 | 0 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| animalgarden_crocodile | 2 | 0 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 1 |
| animalgarden_harpseal | 2 | 0 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 1 |
| animalgarden_lion | 2 | 0 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 2 |
| animalgarden_narwhal | 2 | 0 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 1 |
| animal_feeding_trough | 1 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| animalgarden_hippopotamus | 1 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| animalgarden_manatee | 1 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| animalgarden_snowleopard | 5 | 4 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| civil | 1 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| pathweaver | 2 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| rabbit-pathfinding-fix | 1 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| carpet | 5 | 4 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| herdinstinct | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| stormiespiders | 11 | 11 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Top patterns

Caller sites in the pack, grouped by request, target and the root of the tick chain (control excluded):

| request | target | reached from | sites | mods |
|---|---|---|--:|--:|
| moveTo | entity | Goal.tick | 67 | 20 |
| createPath | entity | Goal.canUse | 25 | 11 |
| moveTo | coords | Goal.start | 25 | 17 |
| moveTo | coords | Goal.tick | 24 | 10 |
| moveTo | path | Goal.start | 17 | 12 |
| moveTo | coords | Entity.aiStep | 11 | 8 |
| moveTo | entity | Goal.start | 9 | 4 |
| createPath | coords | Goal.canUse | 4 | 4 |
| createPath | entity | Entity.aiStep | 3 | 1 |

28 further sites fall in combinations with fewer than 3 sites each (brain behaviours, mixins, lambdas).

1. **`moveTo(Entity)` from `Goal.tick`: 67 sites, 20 mods.** This is the follow, chase and tempt
   shape. Vanilla does the same in `FollowOwnerGoal`, `TemptGoal` and `MeleeAttackGoal`. Some sites
   show a throttle hint, as vanilla's `FollowOwnerGoal` does, and many show none. Better Dogs alone has 10 every-tick
   Entity-target sites (`WolfFetchGoal`, `SmallFightGoal`, `WildWolfPackWarGoal`, `BabyBiteBackGoal`
   and others), 7 of them with neither a nav-state check nor a throttle hint. It is the closest
   analogue to Enhanced Cats in the pack, but it has no loops.
2. **`createPath(Entity)` from `Goal.canUse`: 25 sites, 11 mods.** These are copies of vanilla
   `MeleeAttackGoal.canUse` (the `ModMeleeAttackGoal`, `DefaultMeleeAttackGoal` family). Vanilla
   throttles it to once per 20 ticks by game time (`lastCanUseCheck` against
   `COOLDOWN_BETWEEN_CAN_USE_CHECKS = 20`, confirmed with javap on 26.1.2). The scan cannot tell whether
   the copies kept that.
3. **Fire once and follow: `Goal.start` with `moveTo(coords)` or `moveTo(Path)`, 42 sites.** These are
   not storms.
4. **Fan-out loops: 1 pack site.** `enderzoology DireWolf$SearchForItemsGoal.canUse` calls
   `createPath(Entity)` inside a loop and shows a throttle hint. Enhanced Cats is the only jar with
   every-tick `moveTo` fan-out over nearby entities.
5. **Brain (`Behavior`) requests are rare in mods.** Only mca and animal_feeding_trough write walk
   targets or use `BehaviorUtils`, 10 sites between them. Vanilla's `MoveToTargetSink` turns those
   memory writes into path requests, so they are not direct requests.

## What this scan cannot see

- **Runtime frequency.** A call site is not an event. Nothing here says how often a site fires, for how
  many mobs, or whether the mod's mobs spawn at all in a given world. Enhanced Cats' 88% share came from
  runtime telemetry, and a static count cannot rank mods that way. Confirming a storm needs
  PathWeaver's per-caller request counters on a live server.
- **Cheap gates.** Whether `canUse` returns early before the request (a random chance, a cooldown field,
  a distance check) is invisible, and so are throttles that are not `adjustedTickDelay` or `%`. Every
  "every tick" here means "on a method the game calls every tick", not "requests every tick".
- **Whether the target actually moves.** An `Entity` target can be a sitting cat or a stationary item.
  The scan only knows the parameter type.
- **Calls it cannot follow.** Reflection, `MethodHandle`s, calls from Fabric API event callbacks
  (`ServerTickEvents` lambdas), command handlers, networking, scheduled tasks, and Kotlin or other
  non-javac shapes where the names differ. Cross-class links are capped at four levels. Mixin handlers
  are recognised only through the `method` selector of the injection annotation.
- **Fan-out through lambdas and streams.** `in_iterator_loop` sees javac-style iterator and index loops
  only. A `forEach(v -> v.getNavigation().moveTo(...))` is not flagged.
- **Brain-driven movement in full.** Declarative behaviours built with `BehaviorBuilder` set
  `WALK_TARGET` inside trigger lambdas. The `walk_target` heuristic needs `new WalkTarget` and a memory
  setter in the same method, so lambdas that receive a prebuilt target are missed. Walk-target writes
  also only become searches through `MoveToTargetSink`, which has its own repath rules.
- **Vanilla's own AI in the pack totals.** Vanilla is reported separately as a baseline. It is not
  added to the mod counts, and cross-class linking was not run on it.
- **Mods not in this pack**, and code another mod injects into a vanilla goal without calling the
  request methods itself. For example, a mixin that raises a goal's recalc frequency changes storm
  behaviour without adding a call site.
- **Codebase independence.** The per-mod counts treat each jar separately. The `animalgarden_*` family
  shares one author's copied goal code, as noted above.

## Reproduce

From the game directory. Scan, tables and CSV:

```
python modding/PathWeaver/bench/pack/path_request_scan.py --mods mods --vanilla versions/26.1.2/26.1.2.jar \
  --extra mods/.disabled/EnhancedCats-26.1.x-1.0.1-variantfix.jar \
  --csv modding/PathWeaver/docs/research/path-request-patterns-2026-09.csv --rejected 200
```

The decoder check (exit status 1 on any mismatch). Add `--javap-all-classes` together with `--only
<jar>` to check the prefilter:

```
python modding/PathWeaver/bench/pack/path_request_scan.py --mods mods --vanilla versions/26.1.2/26.1.2.jar \
  --extra mods/.disabled/EnhancedCats-26.1.x-1.0.1-variantfix.jar --javap <path to JDK 25 javap>
```

A full scan takes about 30 seconds. The javap check over prefiltered classes takes about 8 seconds.
Raw per-site rows are in `path-request-patterns-2026-09.csv`.
