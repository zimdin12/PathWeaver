# Pathwright, read and tested

**Internal. Nothing here is published without Steven's decision, and the load failure below should
go to its author as a bug report before it is ever used as a talking point.**

Pathwright 1.0.3, `sha256 6afd2c84890e3f12a0f01a76d4d7d8f0a454fbf7501c5ae035415a12b1f21cfe`, the
Fabric build advertised for 26.1 / 26.1.1 / 26.1.2, downloaded from Modrinth 2026-09-12. 537
downloads on that file. Author unnamed on the listing, MIT licensed.

This mod was published 2026-05-14, after PathWeaver started. It does very nearly what PathWeaver does.
We built ours believing the space was empty; it was not.

## It does not start on 26.1.2

```
Critical injection failure: @Inject annotation on pathwright$onBlockChanged could not find any
targets matching 'setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/
BlockState;I)Z' in net/minecraft/server/level/ServerLevel
```

`ServerLevel` does not declare that method on 26.1.2; `setBlock` lives on `Level`. The mixin is
`"required": true` with `defaultRequire: 1`, so the failure is fatal and the server never reaches
"Done".

Controlled on a clean server, nothing installed but what each case names:

| mods | result |
|---|---|
| Fabric API only | starts |
| Fabric API + PathWeaver, no Cloth Config | refuses: "requires cloth-config, which is missing" |
| Fabric API + Cloth Config + PathWeaver | starts |
| Fabric API + Cloth Config + Pathwright | **mixin injection failure, does not start** |

The first row is the positive control: the clean server is capable of starting, so "does not start" is
a fact about the mod rather than about the harness. The second and third rows are ours, and they are
in the table because our first attempt also failed to start and it would have been easy to report
only the competitor's failure.

**Scope of the claim.** Tested on 26.1.2 only. The listing also claims 26.1 and 26.1.1, and
`setBlock` may well exist on `ServerLevel` there; a mod that was correct on 26.1 and broken by a
26.1.2 change would produce exactly this. Not tested, so not claimed.

**Consequence for the benchmark.** The head-to-head could not run. There is no performance comparison
between PathWeaver and Pathwright, and there will not be one until it loads.

## What it does, read from the bytecode

Same shape as ours, which is worth saying plainly: `dev.pathwright.async.AsyncPathScheduler`,
`dev.pathwright.cache.PathCache`, `CachedPath`, `lod.PathLODManager`, two mixins.

**The async is not reckless.** The worker calls
`PathFinder.findPath(PathNavigationRegion, Mob, Set, float, int, float)` against a
`PathNavigationRegion`, which is the same snapshot approach PathWeaver uses. They did not put the
live world on a worker thread.

Defaults, from the constructor: caching on, 1024 entries, 200-tick expiry; LOD on beyond 48 blocks
recomputing every 15 ticks; async on with 2 threads.

## The cache key

This is the real technical difference, and it is not a matter of taste.

```
PathCache.makeKey(mob.blockPosition().asLong(), targetPos.asLong(),
                  level.dimension().toString(), 0L)
```

The fourth argument is `lconst_0`, a literal zero. The key is start block, target block, dimension.
**Nothing about the mob is in it**, and caching is on by default.

PathWeaver's key is sixteen fields: dimension, x, y, z, target, `evaluatorClass`, `mobClass`,
`evaluatorFlags`, `maxVisitedNodes`, `multiplierBits`, `stepHeightBits`, `maxFallDistance`,
`widthBits`, `heightBits`, `mobStateFlags`, `malusBits[]`.

What their key cannot distinguish, for two mobs on the same block heading to the same block within
ten seconds: mob type, body width and height, step height, fall tolerance, and every pathfinding malus
(water, lava, danger, doors). A ravager and a zombie share a path; so do a spider that climbs, a
drowned that swims, and a baby zombie that fits under a slab.

**What is proven and what is not.** The key composition is read from their compiled code and is
checkable by anyone with `javap`. That a mob visibly walks into a wall because of it has **not** been
demonstrated, because the mod does not start on the version we can test. Do not state the second as
though it were the first.

## Where they are ahead of us

Saying only the unflattering things would make this document useless.

- **Distance LOD.** Mobs beyond 48 blocks recompute every 15 ticks instead of every tick. We have no
  equivalent, and it is a sensible idea.
- **NeoForge.** They ship it, we do not.
- **No Cloth Config dependency.** They read a plain JSON file. PathWeaver hard-depends on Cloth
  Config for a settings screen that a dedicated server never displays, so every server owner installs
  a GUI library to run a server-side mod. That is a real wart of ours, found while running these
  controls.
- **18 KB against our 377 KB.** Not a virtue on its own, but it is a much smaller thing to audit.

## Where we are ahead

- The cache key above.
- The seven-audit compatibility gate, which refuses to run when the mods it was proved against are
  not the mods present.
- Published measurement. Their page claims "+1-3 TPS in typical worlds" and "+3-8 TPS on mob farms"
  with no benchmark, no methodology and no thread-safety documentation. Ours are in
  `docs/PERFORMANCE-2026-09.md` with the harness, the settings, the controls and a discarded round.

## What to do about it

1. **Report the load failure to the author.** It is a one-line fix on their side and the mod is
   MIT-licensed and evidently maintained by someone doing sincere work. Publishing "the competitor is
   broken" while sitting on the diagnosis would be a shabby way to compete, and the claim decays the
   moment they fix it.
2. **Take the two good ideas.** Distance LOD belongs on our roadmap on its merits. NeoForge closes a
   distribution gap.
3. **Fix the Cloth Config dependency**, so a server owner needs Fabric API and nothing else.
4. **Do not put a comparison on the Modrinth page.** Leading with a competitor's crash reads as
   punching down, and our own measurement is the stronger argument anyway.
