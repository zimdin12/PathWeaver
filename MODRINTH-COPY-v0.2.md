# Modrinth copy — PathWeaver v0.2.2

## Summary (≤256 characters)

Experimental server-side Walk/Swim A* offload with fail-closed compatibility and a ModMenu toggle. Mod-defined mobs and standard content-registry packs stay synchronous by default. No universal safety, MSPT, or vanilla-equivalence claim.

## Release title

PathWeaver 0.2.2 — conservative mob-origin gate and lifecycle quality pass

## Version changelog

PathWeaver 0.2.2 adds a conservative mob-origin gate and closes recompute parity gaps over the correctness/lifecycle baseline. It deliberately remains synchronous when compatibility cannot establish eligibility.

### Prominent on/off toggle

With ModMenu installed, open **Mods → PathWeaver → Config**. The first option is **Enable experimental off-thread pathfinding** with the tooltip:

> Experimental off-thread pathfinding; disable if you see issues

Turn that option off to use synchronous pathfinding. The lower **Synchronous fallback only (panic switch)** also prevents all off-thread dispatch. **Allow async for mod-defined mobs (unsafe)** defaults off and bypasses only the mob-origin gate. These settings persist through Cloth Config.

Without ModMenu, edit `config/pathweaver.json`:

```json
"asyncEnabled": false
```

or:

```json
"syncFallbackOnly": true
```

Existing explicit values are preserved during upgrades. A config already containing `asyncEnabled=false` stays off. Every option has an honest tooltip, Save persists and updates the live config, and worker-pool limits are clearly marked restart-required.

### Correctness and lifecycle rework

- Async routing is limited to four genuine movement/recompute operations. Direct and query-only `createPath` calls stay synchronous and immediate.
- Requests carry a server epoch, process-unique token, entity UUID/removal state, world/dimension, exact navigation/current-path identity, semantic target revision, movement, and bounded result age.
- Changed targets, recompute, navigation stop, shutdown, stale completion, and exceptions terminally balance the exact accepted registration.
- Results are tagged `SUCCESS`, `NO_PATH`, or `FAILED`, so ordinary no-path results do not activate exception cooldown.
- Walk callback replay is exactly one start/done pair; Swim replays none.
- Accepted workers wait behind the main-thread start callback, and deferred movement reports success with
  its exact caller speed (including `0`, negative values, and `NaN`) bound to installation.
- Recompute supersedes accepted pre-change work before vanilla's update guard and preserves that speed
  when it dispatches a replacement.
- Positive repath reuse has complete validity checks, while block-change recomputation always bypasses it and supersedes pending same-target work. The default tolerance remains `0`.

### Fail-closed means synchronous in standard packs

Only exact vanilla Walk and Swim evaluators are candidates. By default, the concrete mob class must also share vanilla `Mob`'s runtime code source, so direct and indirect mod-defined mob subclasses remain synchronous. Fly, Amphibious, evaluator subclasses, unknown ownership, scanner errors, and sensitive foreign mixins remain synchronous.

The standard Fabric content-registry module installs dynamic path-type provider hooks into sensitive pathfinding classes. Those providers do not declare a worker-purity/safety contract. PathWeaver therefore **keeps Walk and Swim synchronous in standard Fabric content-registry packs**, even when `asyncEnabled=true`.

That is intentional. The setting enables an async attempt; compatibility eligibility can reduce actual coverage to zero rather than guess. The origin gate closes the mod-defined subclass override gap, but two experimental surfaces remain: Mixins into vanilla `Entity`/`LivingEntity`/`Mob`, and worker reads of live vanilla mob/world/block state without an immutable snapshot. No blanket pack compatibility or thread-safety guarantee is made.

### No private A* engine

The current eligible worker path still uses a read-only view backed by live chunks and live vanilla mob inputs. Install-time validation rejects obsolete completion but cannot make those reads immutable.

A private immutable snapshot evaluator/A* was designed and cost-measured. A simplified lower-bound Walk surface capture already failed the agreed relative-cost gate; correct cave, detour, and provider coverage would add more capture and maintenance cost. The private engine and its scaling matrix were cancelled rather than forced through.

The only identified future route is an upstream immutable-chunk/provider-purity API. PathWeaver 0.2.2 does not implement or pursue that API.

### What the benchmark actually proved

Four paired real Spark profiles in an isolated server with 160 pathfinding zombies measured server-thread A* offload:

- `WalkNodeEvaluator` inclusive samples: 2613 → 236 ms per run on average (-90.97%)
- `WalkNodeEvaluator` self samples: 94 → 22 ms (-76.60%)
- `PathfindingContext` inclusive: 499 → 76 ms (-84.77%)
- `PathFinder` inclusive: 787 → 0 ms

It did **not** prove a net MSPT win. Mean MSPT averaged 2.927 ms OFF and 3.012 ms ON, with noisy paired results. The supported claim is isolated server-thread pathfinding offload—not universal TPS/MSPT improvement.

Spark evidence (OFF / ON):

1. <https://spark.lucko.me/UhFS8p6c1j> / <https://spark.lucko.me/GkIZYLcI8X>
2. <https://spark.lucko.me/0Y4U6ec6R6> / <https://spark.lucko.me/Ob2Eel57E7>
3. <https://spark.lucko.me/pnBiuYyai8> / <https://spark.lucko.me/gZeudtOgj5>
4. <https://spark.lucko.me/4S3SLe2fTh> / <https://spark.lucko.me/Ln5wXmh0xU> (ON captured first)

Each profile used Spark's 4 ms Java sampler for about 122 seconds after a 30-second warm-up on the same restored world with target and block churn. Raw evidence remains external to the repository.

### Requirements

Minecraft 26.1.x, Fabric Loader 0.19+, Fabric API, Cloth Config, and Java 25. ModMenu is optional but recommended for the in-game toggle. Server-side; vanilla clients can connect.

**This remains experimental. Keep backups and disable async if you do not accept its documented boundary.**
