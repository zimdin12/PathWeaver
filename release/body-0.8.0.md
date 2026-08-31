# PathWeaver

### Your server lags when there are lots of mobs. This fixes a big part of why.

Every time a mob works out where to walk, Minecraft does that maths **on the server thread**, the same thread running everything else. A hundred zombies deciding where to go at once is a hundred searches the tick has to wait for, and that is what you feel as a stutter.

PathWeaver moves those searches onto **spare CPU cores** instead. Same paths, same mob behaviour, just not blocking the tick.

**Install it on the server, or in your singleplayer world. Clients need nothing.** Fabric, Minecraft **26.1.1, 26.1.2 or 26.2**, **Java 25 or newer**. Most hosts still default to 21, so check. Needs Fabric API and Cloth Config.

26.1.1 and 26.1.2 share one file; 26.2 is a separate build. As of 0.8.0 the checked tier (`compatibilityTier=AUDITED`) works on **all three**. Its per-mod exemptions are pinned to the exact bytecode they were derived from, and until 0.8.0 they were pinned only to 26.1.2 bytes, so on 26.2 that tier refused everything and switched the mod off. All four audits are re-derived against what 26.2 actually ships.

**It ships with its compatibility checking turned off.** Back up worlds you care about, and read [the warning below](#read-this-before-installing) before you commit a world to it.

---

## What you actually get

|  |  |
|---|---|
| **Mob farm and crowd stutters** | Cut roughly in half |
| **Crowds of mobs pathing at once** | Server thread does about 60% less pathfinding work |
| **Mob behaviour** | Unchanged. The paths are identical, they just arrive off-thread |
| **Quiet server** | No measurable difference. This does nothing until mobs are actually pathing |

**It is spike reduction, not free TPS.** A server sitting at "20 TPS" still stutters when one tick in a hundred takes 80 ms. That is the number this moves. Average throughput only rises when pathfinding alone is already blowing the 50 ms tick budget and you have spare cores.

---

## Benchmarks

**1024 zombies in a walled maze, all retargeting every 6 ticks.** Shipped limits, Lithium loaded, tier set to `AUDITED`, which on a lean pack is what the default gives you anyway. The only difference between the two arms is the master switch.

|  | Vanilla | PathWeaver |  |
|---|---|---|---|
| **Mean tick** | 88.5 to 96.6 ms | **50.0 to 50.3 ms** | down 43 to 48% |
| **Worst 1% of ticks** | 832 to 958 ms | **367 to 383 ms** | down 55 to 61% |
| **Effective TPS**\* | 10.4 to 11.3 | **20.0** | about 2x |
| **Main-thread cost per search** | 480 to 500 us | **195 to 202 us** | down about 60% |

Every async run beat every sync run. No overlap between the two sets.

\* Effective TPS is *derived* from mean tick interval, and 20.0 is the pacing ceiling rather than a measurement of headroom, so "tick time halved" and "tick rate doubled" are one fact stated twice. The independent signals here are the p99 and the main-thread cost.

### On a real 221-jar pack, profiled with spark

220 mixed mobs, zombies and skeletons and spiders and bees and drowned, retargeting every 6 ticks. Two 45-second profiles.

|  | Server-thread time spent pathfinding |
|---|---|
| **PathWeaver off** | 5,572 ms of 45,000, or **12.38%** |
| **PathWeaver on** | 2,216 ms of 45,000, or **4.92%** |

**Pathfinding's share of the server thread fell by 60%.** 7,262 searches dispatched, 96.3% installed.

Two limits on that pair, because they are worth knowing. Only the server thread was sampled, so it shows the work leaving the tick but not what the worker threads then spend on it: moving work is not the same as removing it, and this measurement cannot tell you which happened. And the denominator is the whole sampled thread, most of which is idle at that load, so the share of *real* work is several times larger than 12.38% suggests.

### Read the spread, not the headline

Every async run across three separate sweeps landed between **50.0 and 50.3 ms**, so the mod is the stable half. The *vanilla* baseline swings from 87 to 108 ms with ambient machine load, so almost all the variation in that percentage comes from the baseline rather than from the mod. One flattering pair would have let us print "66% faster". We are quoting the range instead.

---

## Read this before installing

**PathWeaver ships with its compatibility checking turned off.** Out of the box it runs other mods' uninspected pathfinding code on worker threads. **Back up worlds you care about.**

That is deliberate, and the reasoning is in the open. The checked tier (`AUDITED`) only honours individual bytecode audits, and on a real modpack that means it denies everything and the mod does nothing, measured at **0 of 187 eligible mob types** on a 221-jar pack. Shipping that as the default would ship something indistinguishable from broken. That tier is **frozen** as of 0.6.1: it stays as a conservative escape hatch, and it is not going to get better. The runtime failure breaker is what replaced it.

**What has actually happened so far:** across development this has run on packs of 200 to 371 mods through hundreds of thousands of dispatched searches, with no corruption or crash traced to it. That is an absence of reported problems, not a proof. It is exactly what you would also see if the failure were rare, or quiet, or not looked for hard enough. Several real defects *were* found in that time, by code review and bytecode audit, never by something visibly going wrong in a world.

**So decide for yourself, on a copy.** Run it on a world you can throw away. If mobs path normally and nothing looks off after a few sessions, keep the default. If anything does look off, switch to `compatibilityTier=AUDITED` and say so. That report is evidence this project cannot generate on its own.

Be clear about what that switch does on a heavy pack: it turns the speed-up **off entirely**. That is the right move if something looks wrong, and it is the tier working as designed rather than failing. `trustedMods` is the middle option, where you name the specific mods you have decided about and the scan keeps checking the rest. It is not a way back to full coverage either: on the reference pack, trusting all nine blockers still only reaches **86 of 187**, because the remainder are mob classes added by mods, which need `allowModdedMobAsync=true`, a second unsafe opt-in. The [README](https://github.com/zimdin12/PathWeaver/blob/master/README.md) sets out both.

---

## What's new in 0.8.0

**Villagers path off-thread.** Brain mobs, which is villagers, piglins, axolotls, frogs, allays,
camels and about twenty other AI packages, never call `moveTo`. Every path they walk went through
one method that asks for a route and reads the answer on the next line, so the mod could not see
them at all. They were not being refused; they were invisible. `brainSinkAsync` covers that route
and is on by default.

Measured on the 222-jar pack, three pairs of 60-second profiles, every thread sampled:

|  | on | off |
|---|---|---|
| **Pathfinding on the server thread** | **139 ms** | **325 ms** |
| Pathfinding on worker threads | 227 ms | 0 ms |
| Total pathfinding, all threads | 365 ms | 325 ms |
| MSPT | 5.13 ms | 5.25 ms |

**57% of brain-mob pathfinding comes off the tick**, and every run with it on was below every run
with it off (`120, 148, 148` against `288, 288, 400` ms).

It costs about **12% more CPU in total** to do that. Moving work is not removing it: the snapshot,
hand-off and install are real, and they show up here because every thread was sampled rather than
only the one guaranteed to look better. MSPT barely moved and its ranges overlap, because that server
sat at 5 ms against a 50 ms budget. The honest claim is headroom, not throughput.

**The checked tier works on 26.2.** `compatibilityTier=AUDITED` previously did nothing at all there:
every audit pinned 26.1.2 artifacts, so all four refused and all six movement families ran on the
server thread. All four are re-derived now. One of them, `rabbit-pathfinding-fix` 1.4.0, had changed
mechanism rather than drifted, moving a method from an `@Inject` to a `@ModifyConstant`.

Doing that turned up two real defects. The audit enumerators silently skipped any handler whose
annotation they did not recognise, so an artifact could carry an extra modification the "modifies
exactly two methods" count never saw. And the scanner kept its own copy of an audited artifact's
identity, so moving the pin and not the copy denied every family on a build that declares that
artifact audited. Both failed closed. Both were still wrong.

## What was in 0.7.0

**Mostly defects. Around twenty of them**, found by four parallel read-only hunts over the async, mixin, gate and config packages, then by an adversarial review of the whole diff.

Four you might actually notice:

- **Mobs no longer freeze for up to a second after the world changes under them.** Vanilla nulls a mob's path and asks for a new one. Because the answer arrived a tick later, it recorded a recompute that never produced a path, and both of its retry routes then stayed shut for twenty ticks on a mob standing still.
- **A goal can no longer be told a mob is reachable using a route to somewhere it abandoned.** While a search is in flight, `path` and `targetPos` name different destinations, and vanilla's own path-reuse check treats them as a pair.
- **`AUDITED` works on 26.1.1**, not only 26.1.2, because the audits gate on pinned bytes rather than on a version label.
- **Workers skip searches nobody wants any more**, instead of computing them and throwing the result away.

**Six ways it could fail open, all live in 0.6.1.** A negative `workerFailureLimit` landed on the documented "never switch anything off" value, disabling the family-shutdown safety net while the settings screen showed something legal. A config written before the tier setting existed inherited today's permissive default, with no log line. The denial set in `SafetyGate` was public and mutable, so anything on the classpath could clear denials that no tier is allowed to waive. A mixin config registered after startup was invisible to the scan, which was the one place where absent evidence produced ALLOW. One jar with unusual capitalisation in its manifest could make the mod permanently inert. Setting `trustedMods` to null made the settings screen unopenable.

**Reporting.** `/pathweaver mobs` called mobs eligible that dispatch would have refused. The status line said breaker-stopped families were "running anyway" while contradicting a line three rows above it. Both sites printed the raw denial set size, showing `1` while five of six families were refused. Requests left over from a previous session were divided into this session's dispatch total.

This release is correctness work. No performance change was measured for it, and none is claimed.

---

## Earlier releases

**PathWeaver notices when something goes wrong, instead of trying to predict it.** If path searches start crashing on worker threads, three times in a minute by default, that whole mob family goes back to normal server-thread pathfinding for the rest of the session, and the log names the family, the exception and, where it can, the mod responsible. Falling back to vanilla is always safe, so this cannot make anything worse than not having the mod installed.

Two settings control it, both under General, and on a healthy pack it produces no output at all. It catches crashes, not silence: a search that returns a *wrong* path without crashing is not detected by this.

0.6.0 before it made spiders path off-thread, stopped the diagnostics contradicting each other, and stopped setup failures being silent.

---

## How many of your mobs it covers

Run **`/pathweaver mobs`** to see this for your own pack, and **`/pathweaver status`** for what the mod is currently doing. On a 221-jar pack at the shipped default: **184 of 187 mob types eligible**.

How many of those searches actually get installed depends entirely on load, so here are all three rather than the flattering one: **99%** in a light validation run, **96%** under the spark profile above, and **about 82%** in the saturated 1024-mob benchmark, where admission deliberately refuses about half of all requests rather than queue them.

**Eligible is not the same as covered.** It means nothing blocks dispatch for that mob, not that every movement it makes goes off-thread. Brain-driven movement, which is villagers, piglins, axolotls, frogs, allays and the warden, calls the search directly and stays synchronous by design. That is next on the roadmap, not in this release.

The three held back entirely navigate with a `PathFinder` subclass rather than the stock one, which dispatch declines: the warden, whose subclass **vanilla itself** builds, and two spiders, on *this* pack, where a mod replaces spider navigation wholesale.

## It will tell you if your machine is too small for it

PathWeaver does not make pathfinding cheaper. It moves the same work onto another thread and adds a little of its own on the way, so the trade only pays when a core is free.

**2 cores or fewer:** it recommends `enabled=false` at world start. **4 or fewer:** it warns that the benefit will be small. Nothing is switched off automatically.

## Testing

402 unit tests, five in-game harnesses, four in-game server harnesses, a client harness driving a real singleplayer world, and verification on a real 221-jar modded server across four configurations. With the world held still, **all six evaluator families produced node-for-node identical paths to a synchronous oracle**, one scenario per family, which is evidence rather than proof. Flying is the exception worth naming: a worker draws its start candidate from thread-confined randomness, so it is not guaranteed to match by construction. It happened to.

The 0.6 and 0.7 lines have been through twenty-odd rounds of independent code review. Later rounds executed mutations against the test suite rather than reading the code, which repeatedly found defects that reading had missed, including live bugs in the headline features of both releases. The [changelog](https://github.com/zimdin12/PathWeaver/blob/master/CHANGELOG.md) and the [roadmap](https://github.com/zimdin12/PathWeaver/blob/master/ROADMAP.md) record what was rejected and reverted as well as what shipped: an entire compatibility-gate rewrite was built, measured, reviewed and **thrown away** because the review found it loosened a safety gate on an analysis that was wrong in four independent ways.

**What is still unproven** is listed in full in the [README](https://github.com/zimdin12/PathWeaver/blob/master/README.md): realistic mob counts, mixed workloads, path quality while blocks are changing, and behaviour at a thousand mobs are all unmeasured.
