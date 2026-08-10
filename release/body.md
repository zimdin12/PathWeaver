# PathWeaver

### Your server lags when there are lots of mobs. This fixes a big part of why.

Every time a mob works out where to walk, Minecraft does that maths **on the server thread** — the same thread running everything else. A hundred zombies deciding where to go at once is a hundred searches the tick has to wait for, and that is what you feel as a stutter.

PathWeaver moves those searches onto **spare CPU cores** instead. Same paths, same mob behaviour, just not blocking the tick.

**Install it on the server (or in your singleplayer world). Clients need nothing.** Fabric, Minecraft 26.1.2.

---

## What you actually get

|  |  |
|---|---|
| 🐌 **Mob farm and crowd stutters** | Cut roughly in half |
| 🏰 **Busy builds, lots of animals** | Server thread does ~60% less pathfinding work |
| 🧟 **Mob behaviour** | Unchanged — the paths are identical, they just arrive off-thread |
| 💤 **Quiet server** | No difference. This does nothing until mobs are actually pathing |

**It is spike reduction, not free TPS.** A server sitting at "20 TPS" still stutters when one tick in a hundred takes 800 ms. That is the number this moves. Average throughput only rises when pathfinding alone is already blowing the 50 ms tick budget *and* you have spare cores.

---

## Benchmarks

**1024 zombies in a walled maze, all retargeting every 6 ticks.** Stock settings, Lithium loaded. The only difference between the two arms is the master switch.

|  | Vanilla | PathWeaver |  |
|---|---|---|---|
| **Mean tick** | 88.5–96.6 ms | **50.0–50.3 ms** | ▼ 43–48% |
| **Worst 1% of ticks** | 832–958 ms | **367–383 ms** | ▼ 55–61% |
| **Effective TPS** | 10.4–11.3 | **20.0** | ▲ ~2× |
| **Main-thread cost per search** | 480–500 µs | **195–202 µs** | ▼ ~60% |

Every async run beat every sync run. No overlap between the two sets.

### On a real 221-jar pack, profiled with spark

220 mixed mobs — zombies, skeletons, spiders, bees, drowned — retargeting every 6 ticks. Two 45-second profiles.

|  | Server-thread time spent pathfinding |
|---|---|
| **PathWeaver off** | 5,572 ms of 45,000 — **12.38%** |
| **PathWeaver on** | 2,216 ms of 45,000 — **4.92%** |

**Pathfinding's share of the server thread fell by 60%.** 7,262 searches dispatched, 96.3% installed.

### Read the spread, not the headline

Every async run across three separate sweeps landed between **50.0 and 50.3 ms** — the mod is the stable half. The *vanilla* baseline swings from 87 to 108 ms with ambient machine load, so almost all the variation in that percentage comes from the baseline rather than from the mod. One flattering pair would have let us print "66% faster". We are quoting the range instead.

---

## ⚠ Read this before installing

**PathWeaver ships with its compatibility checking turned off.** Out of the box it runs other mods' uninspected pathfinding code on worker threads. **Back up worlds you care about.**

That is deliberate, and the reasoning is in the open. The checked tier (`AUDITED`) only honours individual bytecode audits, and on a real modpack that means it denies everything and the mod does nothing — measured at **0 of 187 eligible mob types** on a 221-jar pack. Shipping that as the default would ship something indistinguishable from broken.

**What has actually happened so far:** across development this has run on packs of 200–370 mods through hundreds of thousands of dispatched searches, with no corruption or crash traced to it. That is an absence of reported problems, not a proof — it is exactly what you would also see if the failure were rare, or quiet, or not looked for hard enough. Several real defects *were* found in that time, by code review and bytecode audit, never by something visibly going wrong in a world.

**So decide for yourself, on a copy.** Run it on a world you can throw away. If mobs path normally and nothing looks off after a few sessions, keep the default. If anything does look off, switch to `compatibilityTier=AUDITED` and say so — that report is evidence this project cannot generate on its own.

---

## What's new in 0.6.1

**PathWeaver now notices when something goes wrong, instead of trying to predict it.**

If path searches start crashing on worker threads — **three times in a minute, by default** — that
whole mob family goes back to normal server-thread pathfinding for the rest of the session, and the
log names the family, the exception and, where it can, the mod responsible. One isolated crash does
not switch anything off; it is logged and counted. Falling back to vanilla is always safe, so this
cannot make anything worse than not having the mod installed.

- Two new settings: **switch a family off after N failed searches** (default 3) and **counted within
  this many ticks** (default 1200 = one minute). Set the first to 0 to keep speeding mobs up no matter
  what — failures are still logged either way.
- Existing configs are untouched. Both settings default in; nothing you have set changes.
- On a healthy pack this produces **no output at all**. The reference 221-jar pack ran 731 searches
  with zero failures. That is the correct result: it is a smoke detector.

**Being honest about what it catches:** crashes, not silence. A search that returns a *wrong* path
without crashing is not detected by this, and the README says so alongside everything else that is
still unproven.

No performance change: benchmarked against 0.6.0 across ten interleaved runs, mean tick 50.001 ms vs
50.004 ms.

---

## What was in 0.6.0

- **Spiders path off-thread.** `WallClimberNavigation` overrides `moveTo` without calling `super`, so the dispatch marker never ran for it — every spider chasing a player resolved its path on the server thread, while `/pathweaver mobs` counted them as eligible.
- **The diagnostics stopped contradicting each other.** The startup banner could announce "ACTIVE: all 6 movement families" while five of the six were being refused on every tick.
- **Setup failures are no longer silent.** A failure before a request registered produced no log line, no counter and no outcome — the mod could do nothing indefinitely while reporting itself healthy.
- **The startup log now explains why the unsafe default is the default**, and what to do about it.

No performance change against 0.5.3: mean tick time within 0.00% across ten interleaved runs.

---

## How many of your mobs it covers

Run **`/pathweaver mobs`** to see this for your own pack. On a 221-jar pack at the shipped default: **184 of 187 mob types eligible**, ~99% of searches installing.

**Eligible is not the same as covered.** It means nothing blocks dispatch for that mob — not that every movement it makes goes off-thread. Brain-driven movement (villagers, piglins, axolotls, allays, the warden) calls the search directly and stays synchronous by design. That is next on the roadmap, not in this release.

The three held back entirely navigate with a `PathFinder` subclass rather than the stock one, which dispatch declines: the warden, whose subclass **vanilla itself** builds, and two spiders — on *this* pack, where a mod replaces spider navigation wholesale.

## It will tell you if your machine is too small for it

PathWeaver does not make pathfinding cheaper. It moves the same work onto another thread and adds a little of its own on the way, so the trade only pays when a core is free.

**2 cores or fewer:** it recommends `enabled=false` at world start. **4 or fewer:** it warns that the benefit will be small. Nothing is switched off automatically.

## Testing

358 unit tests, 4 in-game game tests, 4 in-game server harnesses, a client harness driving a real singleplayer world, and verification on a real 221-jar modded server across four configurations. With the world held still, **all six evaluator families produced node-for-node identical paths to a synchronous oracle** — one scenario per family, which is evidence rather than proof.

The 0.6 line has now been through **thirteen rounds of independent code review** — eleven for 0.6.0 and two more for 0.6.1. Later rounds executed mutations against the test suite rather than reading the code, which repeatedly found defects that reading had missed — including a live bug in this release's own headline feature. The [changelog](https://github.com/zimdin12/PathWeaver/blob/master/CHANGELOG.md) records what was rejected and reverted as well as what shipped: an entire compatibility-gate rewrite was built, measured, reviewed and **thrown away** because the review found it loosened a safety gate on an analysis that was wrong in four independent ways.

**What is still unproven** is listed in full in the [README](https://github.com/zimdin12/PathWeaver/blob/master/README.md): realistic mob counts, mixed workloads, path quality while blocks are changing, and behaviour at a thousand mobs are all unmeasured.
