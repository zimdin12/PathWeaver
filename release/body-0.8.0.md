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

> **It ships with its compatibility checking turned off**, which means it runs other mods' pathfinding code on worker threads. Back up worlds you care about, and read [Compatibility](#compatibility) before you commit a world to it.

---

## What you get

| | |
|---|---|
| Mob farms and crowds | Stutters cut roughly in half |
| Server thread | About 45% less pathfinding work |
| Villages | Villager pathing comes off the tick too |
| Mob behaviour | Paths are identical. One change: villagers set off a tick later |
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

| | Server-thread pathfinding | Total CPU, all threads | Tick time |
|---|---|---|---|
| Installed, switched off | -2% | -2% | +1% |
| On | -45% | +9% | -10% |
| On, with route sharing | **-46%** | **+6%** | **-10%** |

Total CPU across all threads goes **up**. Moving work is not removing it, and the hand-off costs something. What you are buying is a shorter tick, paid for with cores that were sitting idle.

Turning villager pathing off the tick is the largest single win in this release. Server-thread pathfinding halved and tick time fell 27%, and every run with it on beat every run without it.

---

## What changes about your mobs

**Villagers and other brain-driven mobs set off one tick later than they used to.** On the tick their search is dispatched their behaviour is told "no path yet", and the route arrives on the next one. Nothing else differs.

That is a real behaviour change, which is why it is a setting rather than something you get regardless. `brainSinkAsync=false` turns it off. The warden is not covered either way, because its navigation builds a custom pathfinder.

Everything else keeps exactly the path it would have had.

### Route sharing

One mob can reuse a route another mob already computed, but only when every input to the search is identical: same starting position, same target, same size, same terrain costs, same movement flags. A stored route is dropped the moment a block changes along it. A reused route is the same route that mob's own search would have produced.

**It ships measuring rather than serving.** How often mobs repeat a search depends on your world, not on this mod. On the default setting PathWeaver fills the cache, counts what sharing would have saved, and hands out nothing, so `/pathweaver status` tells you what it is worth on your server before you spend it. Set `resultCacheMode` to `SERVE` when the number justifies it. Takes effect immediately.

On the benchmark the hit rate was 12 to 16%. Being straight about the size of it: against the cache being switched off rather than absent, sharing bought about 3% of total pathfinding CPU, and those run ranges overlapped. The test arena was 200 mobs walking corridors without stopping, close to the worst case for this. Mobs that stand still and re-ask are what it helps, and that is not what got measured.

---

## Compatibility

**The default runs other mods' uninspected pathfinding code on worker threads.**

That is deliberate. The checked tier, `compatibilityTier=AUDITED`, only trusts mods whose bytecode has been audited, and on a real modpack it denies everything and the mod does nothing. Measured on a 221-jar pack: **0 of 187 mob types eligible** at that tier, against 184 of 187 at the default. Shipping the safe-looking tier by default would ship something indistinguishable from broken.

What has actually happened so far: this has run on packs of 200 to 371 mods through hundreds of thousands of searches, with no corruption or crash traced to it. That is an absence of reported problems rather than a proof, and it is also what you would see if the failure were rare or quiet. Try it on a world you can throw away.

If something does look wrong, switch to `compatibilityTier=AUDITED` and report it. Be clear about what that does on a heavy pack: it turns the speed-up off entirely. That is the tier working as designed, not failing. `trustedMods` is the middle option, naming specific mods you have decided about while the scan keeps checking the rest.

### On 26.2, the checked tier is not usable yet

Five of the seven audits verify on 26.2. Lithium and Diagonal Blocks do not, because there they are different builds from the ones the audits were derived from. Lithium alone puts all six movement families back on the server thread, and most performance packs ship Lithium.

The default tier is unaffected. If something looks wrong on 26.2, set `enabled=false` rather than reaching for `AUDITED`. The outcome is the same and only one of them is honest about it.

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

Eligible means nothing blocks dispatch for that mob, not that every movement it makes goes off-thread. On a 221-jar pack at the shipped default it is 184 of 187; the three held back navigate with a custom pathfinder rather than the stock one.

How many dispatched searches get used depends on load. Roughly 99% on a quiet server, 96% under normal play, about 82% in the saturated 1024-mob benchmark, where the mod deliberately refuses requests rather than queue ones that would land too late to be worth having.

---

## Known limits

The checked tier does not cover Lithium or Diagonal Blocks on 26.2, above. Path quality while blocks are being changed underneath mobs is not measured. Behaviour at a thousand mobs or more is not measured.

Full detail, including what was tried and thrown away, is on [GitHub](https://github.com/zimdin12/PathWeaver): the [README](https://github.com/zimdin12/PathWeaver/blob/master/README.md), the [changelog](https://github.com/zimdin12/PathWeaver/blob/master/CHANGELOG.md) and the [roadmap](https://github.com/zimdin12/PathWeaver/blob/master/ROADMAP.md).
