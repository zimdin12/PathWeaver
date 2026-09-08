# PathWeaver

### Your server stutters when there are a lot of mobs. This fixes a big part of why.

Every time a mob works out where to walk, Minecraft does that maths on the server thread, the same thread running everything else. A hundred zombies deciding where to go at once is a hundred searches the tick has to wait for, and that is what you feel as a stutter.

PathWeaver moves those searches onto spare CPU cores. The mob gets the same path it would have got. It just does not block the tick while the game works it out.

---

## Getting started

| | |
|---|---|
| **Install on** | The server, or your singleplayer world. Clients need nothing |
| **Needs** | Fabric, Minecraft 26.1.1 / 26.1.2 / 26.2, Java 25 or newer |
| **Also needs** | Fabric API, Cloth Config |
| **Then** | Play. Run `/pathweaver status` to see what it is doing |

Most hosts still default to Java 21, so check yours before you file a bug.

**Worth ten minutes before you install:** [is pathfinding actually your bottleneck?](https://github.com/zimdin12/PathWeaver/blob/master/docs/IS-IT-YOUR-BOTTLENECK.md) This mod only helps a server whose tick time is going into mob path searches, and plenty of struggling servers are slow for some other reason.

> **It ships with its compatibility checking turned off**, which means it runs other mods' pathfinding code on worker threads. Back up worlds you care about, and read [Compatibility](#compatibility) before you commit a world to it.

---

## What you get

| | |
|---|---|
| Mob farms and crowds | Stutters cut roughly in half |
| Server thread | Roughly half the sampled pathfinding work |
| Villages | Villager pathing comes off the tick too |
| Mob behaviour | Same routes in ordinary play. Villagers set off a tick later, and route sharing can hand one mob a route another computed |
| Quiet server | No difference. This does nothing until mobs are actually pathing |

**This is spike reduction, not free TPS.** A server sitting at "20 TPS" still stutters when one tick in a hundred takes 80 ms, and that is the number this moves. Average throughput only rises when pathfinding alone is already blowing the 50 ms tick budget and you have spare cores.

On a machine with two cores or fewer, PathWeaver tells you at world start to leave it off. At four or fewer it warns the gain will be small. It never switches itself off.

---

## The numbers

**1024 zombies in a walled maze, retargeting every 6 ticks.** The only difference between the columns is whether PathWeaver is on.

| | Off | On |
|---|---|---|
| Mean tick | 88.5 to 96.6 ms | **50.0 to 50.3 ms** |
| Worst 1% of ticks | 832 to 958 ms | **367 to 383 ms** |
| Main-thread cost per search | 480 to 500 us | **195 to 202 us** |

Ranges, not single figures, because the baseline swings with whatever else the machine is doing. Every run with PathWeaver on beat every run with it off, with no overlap.

**Each setting measured on its own**, on a 231-jar server with 200 mobs, three rounds per setting, against not having the mod installed at all.

| | Server-thread pathfinding | All threads, sampled | Tick time |
|---|---|---|---|
| Installed, switched off | -2% | -2% | +1% |
| On | -45% | +9% | -10% |
| On, with route sharing | **-46%** | **+6%** | **-10%** |

Total sampled pathfinding across all threads goes **up**. Moving work is not removing it, and the hand-off costs something. What you are buying is a shorter tick, paid for with cores that were sitting idle.

These are **sampled** figures: the profiler takes thread-stack samples every 4 ms and counts how often pathfinding is on the stack, waiting included. That is not a measurement of CPU time, and the numbers above cover navigation and pathfinding broadly rather than the A\* search alone. Earlier versions of this page called them CPU and A\*; both were wrong and are corrected below.

Turning villager pathing off the tick is the largest single win in this release. Server-thread pathfinding halved and tick time fell 27%, and every run with it on beat every run without it.

---

## What changes about your mobs

**Villagers and other brain-driven mobs set off one tick later than they used to.** On the tick their search is dispatched their behaviour is told "no path yet", and the route arrives on the next one. Nothing else differs.

That is a real behaviour change, which is why it is a setting rather than something you get regardless. `brainSinkAsync=false` turns it off. The warden is not covered either way, because its navigation builds a custom pathfinder.

Everything else keeps the path it would have had, with two stated exceptions: a shared route when route sharing is switched on, and the search running against the world as it was a tick or two earlier rather than at the instant the mob asks. Neither changes where a mob is trying to go.

### Route sharing

One mob can reuse a route another mob already computed, but only when every input to the search is identical: same starting position, same target, same size, same terrain costs, same movement flags. A stored route is dropped when a block changes along it while the cache is running.

One honest limit remains: a block changed away from the route can open a shorter way through that a reused route will not take, which vanilla does not notice either.

The other one is fixed here. In 0.8.0 and 0.8.1, switching route sharing or the master switch off and on again inside a route's two-second lifetime could keep a route across changes made while it was off, because PathWeaver stops watching for block changes with the feature while stored routes lived until the server stopped. Both switches now discard what was learned under the old settings, including searches still in flight.

**It ships measuring rather than serving.** How often mobs repeat a search depends on your world, not on this mod. On the default setting PathWeaver fills the cache, counts what sharing would have saved, and hands out nothing, so `/pathweaver status` tells you what it is worth on your server before you spend it. Set `resultCacheMode` to `SERVE` when the number justifies it. Takes effect immediately.

On the benchmark the hit rate was 12 to 16%. **What that is worth is unresolved, and this page previously said otherwise.** The measured difference against the cache switched off was about 3% of sampled pathfinding with the run ranges overlapping, on an arena whose arms did not run identical populations and whose arm order was fixed rather than counterbalanced. That is not enough to establish a saving. The test arena was also 200 mobs walking corridors without stopping, close to the worst case for this feature; mobs that stand still and re-ask are what it helps, and that is not what got measured.

This is the reason it ships measuring rather than serving. `/pathweaver status` counts what serving would have found on YOUR world, which is a better number than anything here.

---

## Compatibility

**The default runs other mods' uninspected pathfinding code on worker threads.**

That is deliberate. The checked tier, `compatibilityTier=AUDITED`, only trusts mods whose bytecode has been audited, and on a real modpack it denies everything and the mod does nothing. Measured on a 221-jar pack: **0 of 187 mob types eligible** at that tier, against 184 of 187 at the default. Shipping the safe-looking tier by default would ship something indistinguishable from broken.

What has actually happened so far: this has run on packs of 200 to 371 mods through hundreds of thousands of searches, with no corruption or crash traced to it. That is an absence of reported problems rather than a proof, and it is also what you would see if the failure were rare or quiet. Try it on a world you can throw away.

If something does look wrong, switch to `compatibilityTier=AUDITED` and report it. Be clear about what that does on a heavy pack: it turns the speed-up off entirely. That is the tier working as designed, not failing. `trustedMods` is the middle option, naming specific mods you have decided about while the scan keeps checking the rest.

### The checked tier depends on which download you have

Each audit is pinned to the exact bytes of the mod and the vanilla classes its proof reads, so a build for one Minecraft version cannot vouch for another. There are two downloads and they differ here:

- **The 26.1.2 download**, on 26.1.1 or 26.1.2: all seven audits verify.
- **The 26.2 download**, on 26.2: all seven audits verify, after Lithium and Diagonal Blocks were re-derived against the builds that ship for 26.2.

Install the download that matches your Minecraft version and `AUDITED` behaves the same on both. Install the 26.1.2 one on 26.2 and the pins refuse, which turns the speed-up off rather than running anything unchecked.

Either way this only matters if you have opted into `AUDITED`. The shipped default consults none of it.

Whatever version you are on, **mods that modify pathfinding are named at world start**, with what PathWeaver decided about each.

---

## Settings

| Setting | Default | What it does | |
|---|---|---|---|
| `enabled` | on | Master switch | |
| `compatibilityTier` | `UNSAFE` | Whether to check other mods before running their code off-thread | restart |
| `brainSinkAsync` | on | Villager-type pathing off the tick, at one tick of delay | |
| `resultCacheMode` | `SHADOW` | Route sharing. `SHADOW` measures, `SERVE` spends it | |
| `repathToleranceBlocks` | 1 | Reuse a mob's current path when its target moved less than this | |
| `poolThreads` | auto | Worker threads. Auto is a quarter of your CPU threads, minimum two | restart |

Editable in game through ModMenu, or in `config/pathweaver.json`. The two marked **restart** are read once at startup; the rest take effect as soon as you save.

---

## Checking it is doing something

`/pathweaver status` reports what is dispatching, what is landing, and what route sharing would save. `/pathweaver mobs` lists which of your mob types are eligible.

Route sharing is reported as two numbers rather than one. **Searches skipped** is searches that did not run, which is the whole saving. **Hits that saved nothing** is real cache hits with no stored route behind them, which is what you get while the cache is measuring. Before 0.9.0 those were added together under the first label, so the figure you would have used to decide about `SERVE` was too large.

Eligible means nothing blocks dispatch for that mob, not that every movement it makes goes off-thread. On a 221-jar pack at the shipped default it is 184 of 187; the three held back navigate with a custom pathfinder rather than the stock one.

How many dispatched searches get used depends on load. Roughly 99% on a quiet server, 96% under normal play, about 82% in the saturated 1024-mob benchmark, where the mod deliberately refuses requests rather than queue ones that would land too late to be worth having.

---

## Corrections

Published claims should not quietly change, so here is what was wrong. The raw benchmark artifacts
they came from are unchanged in the repository.

**Route sharing could keep a route across a settings toggle.** Switching it or the master switch off
and on again inside a route's lifetime could hand out a route computed before terrain changed while
nothing was watching. In 0.8.0 and 0.8.1, reachable from the settings screen with no restart. Fixed
in 0.9.0. Only affects you if you turned route sharing on; the shipped default measures rather than
serves.

**"Searches skipped" was two numbers added together.** Cache hits with no stored route behind them
skip nothing, and they were counted in the same figure as searches that really did not run. If you
read that line in 0.7.0, 0.8.0 or 0.8.1 to decide whether route sharing was worth switching on, the
number was larger than the saving. It is now two lines and only the first is a saving.

**A setting screen inside the 0.8.x jars says a reused route "is never a stale one".** A page edit
cannot reach text compiled into a release. 0.9.0 changes it to what holds: a route is dropped when a
block changes along it, and a change away from the route is not noticed, as in vanilla.

**A permission check that was described but never existed.** `/pathweaver mobs` builds one of every
registered mob type to report which are eligible, which takes about a fifth of a second on a large
pack, on the server thread. It was meant to need operator level: the source comment said so and the
0.7.0 notes said so. It did not, so any player on any server could run it repeatedly. Fixed in 0.8.1.
`/pathweaver status` stays open to everyone, since that is the one the docs tell you to run. **If you
host a multiplayer server on 0.7.0 or 0.8.0, this is the reason to update.**

**"CPU" and "A\*".** The profiler samples thread stacks every 4 ms and counts how often something is
on the stack, waiting included. It does not measure CPU time, and the classifier covers navigation
and pathfinding broadly rather than the A\* search alone. Figures once labelled "total CPU" and
"server-thread A\*" are sampled broad pathfinding, relabelled above. The direction and rough size of
the villager result survive the relabelling. The words did not.

**The route-sharing saving.** An earlier version of this page presented about 3% as a measured gain.
It is not established. The two arms did not run identical mob populations and the arm order was fixed
rather than counterbalanced, so a small difference with overlapping ranges cannot be pinned on the
feature. The 12 to 16% hit rate is real; what serving those hits is worth is unknown.

**What did not change**, because it was checked rather than assumed: the mob-eligibility counts, the
audit and compatibility behaviour, the tick-interval benchmark, and the fact that every run with the
mod on beat every run with it off on that benchmark.

---

## Known limits

The checked tier does not cover Lithium or Diagonal Blocks on 26.2, above. Path quality while blocks are being changed underneath mobs is not measured. Behaviour at a thousand mobs or more is not measured.

Full detail, including what was tried and thrown away, is on [GitHub](https://github.com/zimdin12/PathWeaver): the [README](https://github.com/zimdin12/PathWeaver/blob/master/README.md), the [changelog](https://github.com/zimdin12/PathWeaver/blob/master/CHANGELOG.md) and the [roadmap](https://github.com/zimdin12/PathWeaver/blob/master/ROADMAP.md).
