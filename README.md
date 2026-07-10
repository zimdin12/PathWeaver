# PathWeaver

**Async mob pathfinding for Minecraft 26.1.2 (Fabric) — safe by construction.**

> ⚠️ **Alpha / work in progress.** This is an early release. The design is careful and it has been unit-tested and soak-tested, but it has **not** been thoroughly tested across many packs, mods, and long real-world sessions yet. Run it with async enabled at your own discretion, keep backups, and please report anything odd. Expect changes.

PathWeaver moves the expensive part of mob pathfinding — the A\* search — off the main server thread, onto a read-only world snapshot. It targets the single biggest steady-state server cost in a busy world (mob pathfinding: `WalkNodeEvaluator`, `PathFinder`, `PathNavigation`) without changing how mobs behave and without fighting Lithium.

> **The one-line pitch:** other async mods make the game *not crash* on unsafe concurrent access. PathWeaver is safe *by construction* — the worker thread runs a private, freshly-built search with its own per-thread path-type cache, so it never writes shared main-thread state. (It reads block data through a read-only region view; those are reads only, bounded by a staleness check, and any error degrades cleanly to synchronous pathfinding.)

## Why this is safe when naive async isn't

Running Minecraft's *entity tick* on multiple threads is unsafe: the tick mutates shared world state, so it races no matter how many locks you add. That's why other async mods disable the vanilla race detector rather than fix the races.

PathWeaver never runs the tick off-thread. It runs **only the A\* search**, and only when these conditions hold:

1. **Immutable inputs.** Vanilla already runs A\* against a `PathNavigationRegion` — a read-only copy of the blocks that excludes entities. PathWeaver builds that region on the main thread and hands the worker an immutable snapshot.
2. **Zero shared search state.** The `PathFinder` and `NodeEvaluator` objects hold per-search scratch state (open-set, node pool, `PathfindingContext`) and are **not** safe to reuse across threads — the subtle trap that corrupts naive implementations. PathWeaver builds a **fresh, isolated** `PathFinder` + `NodeEvaluator` for every search, with the mob's config flags copied exactly.
3. **Isolated `PathTypeCache`.** Vanilla's `PathfindingContext` constructor grabs the *shared* `ServerLevel.getPathTypeCache()` and writes into it during the search — a hidden shared-state write that ignores the read-only region. PathWeaver mixes into that constructor and, **only on a worker thread**, substitutes a fresh per-search `PathTypeCache`, so an off-thread search recomputes path types into thread-confined storage and never touches the cache that synchronous mobs use. On the main thread the real shared cache is returned, unchanged.
4. **Main-thread entity callbacks.** `WalkNodeEvaluator`/`FlyNodeEvaluator` call `mob.onPathfindingStart()`/`onPathfindingDone()` and lazily resolve the mob's step-height attribute. PathWeaver skips those calls on the worker thread and fires the balanced start/done pair on the **main thread** (at dispatch, and at install-or-discard), and force-resolves the attribute at dispatch so nothing is written off-thread.

The result installs next tick via vanilla's own `moveTo(path, speed)`, followed by a faithful replay of vanilla `createPath`'s tail (`targetPos`, `reachRange`, `resetStuckTimeout`) so async mobs repath around new obstacles exactly like sync ones. Until the result lands the mob keeps its current path, so it never stalls. **The computed path is identical to what vanilla would produce** — same region, same `findPath`, same evaluator config.

### The one honest caveat (block/entity reads)

Vanilla `findPath` still *reads* the live mob's position, hitbox and pathfinding-malus map during the search. These are **reads, not writes**, and they are bounded by the staleness check at install (if the mob moved past `stalenessMoveThreshold` since dispatch, the result is discarded and it re-requests). PathWeaver does **not** take a full mob-state snapshot — the one genuine *write* hazard (the `PathTypeCache`) is eliminated as above; the residual live reads are deliberately left un-snapshotted rather than grow a large, fragile mixin surface.

## What it does NOT do (by design)

- **No async entity ticking** — that's the unsafe thing; PathWeaver deliberately avoids it.
- **No async collision.**
- **No faster node evaluation** — [Lithium](https://modrinth.com/mod/lithium) already optimizes that (`PathNodeCache`), and PathWeaver is built to *coordinate* with Lithium, not duplicate or conflict with it.

## Two features (both toggleable)

- **Async pathfinding** — the A\* search runs on a bounded worker pool. Only mobs whose evaluator is an exact-match allowlisted vanilla class are eligible; everything else uses unchanged vanilla sync pathing.
- **Conservative repath elision** — vanilla only reuses a live path when the target block matches *exactly*, so a target drifting one block forces a full recompute. PathWeaver widens that to a small tolerance (default 1; `0` = vanilla behaviour). Lithium already handles the block-change repath trigger; this only addresses the goal-driven cadence.

## Safety gate

Async is enabled only for mobs whose `NodeEvaluator` is **exactly** `WalkNodeEvaluator`, `SwimNodeEvaluator`, `AmphibiousNodeEvaluator`, or `FlyNodeEvaluator` — never a subclass (`instanceof` would wrongly wave through mod evaluators that read live state). Default-deny.

On top of that, at startup PathWeaver scans every other mod's mixin configs: if a mod mixes *into* one of those vanilla classes (keeping its identity), the affected mob family is forced back to sync. Fabric API's own snapshot-based pathfinding mixin is trusted; unknown third-party mixins are not.

- **Async today:** land + flying + fish-style aquatic mobs (`WalkNodeEvaluator`, `FlyNodeEvaluator`, `SwimNodeEvaluator`) — villagers, most animals, zombies, squid/fish, etc.
- **Sync fallback (safe, no benefit):** `AmphibiousNodeEvaluator` mobs (axolotl, frog, turtle-type) — their evaluator *writes* the live mob's water pathfinding-malus during the search, which can't be done safely off-thread — and any mob touched by an untrusted third-party pathfinding mixin.

## Configuration

`config/pathweaver.json` (in-game GUI via ModMenu). Key options:

| Option | Default | Meaning |
|---|---|---|
| `asyncEnabled` | `true` | Master switch for async pathfinding |
| `repathElisionEnabled` | `true` | Conservative repath elision (Feature B) |
| `poolThreads` | `0` (auto) | Worker threads; `0` = `max(1, cores/4)`, deliberately conservative so it never starves the main/render threads |
| `maxInFlight` | `256` | Cap on concurrent searches; over the cap → sync |
| `syncFallbackOnly` | `false` | Panic switch — never dispatch async |
| `distanceThrottleEnabled` | `false` | Opt-in; makes distant mobs path less often (they get visibly dumber) |
| `repathToleranceBlocks` | `1` | Feature B tolerance; `0` = vanilla exact-match |
| `stalenessMoveThreshold` | `4.0` | Blocks the mob may drift between dispatch and install before the async result is discarded and re-requested |

## Requirements

- Minecraft **26.1.2**, Fabric loader, Java **25**
- [Fabric API](https://modrinth.com/mod/fabric-api), [Cloth Config](https://modrinth.com/mod/cloth-config)
- Server-side (works on dedicated servers, LAN hosts, and singleplayer). Pairs well with Lithium.

## Correctness & verification

- **By construction:** async and sync run the *same* `findPath` on the *same* region with a config-identical evaluator, and the worker touches only thread-confined state (fresh finder + evaluator + isolated `PathTypeCache`) → identical `Path`, nothing shared to race on.
- **Unit tests** cover the safety gate, the foreign-mixin scanner (JSON + ASM), the worker pool (isolation, saturation fallback, exception→sync, lifecycle reset on restart), the result installer (staleness, once-only delivery, failed-vs-stale routing), the post-failure sync cooldown, repath tolerance, and the equivalence-critical evaluator clone.
- **Soak-tested:** 80 mobs pathfinding continuously on a dev server produced **835 off-thread dispatches / 834 installs / 0 discards, with zero exceptions and zero mixin-injection failures**.

## License

MIT © 2026 Zimdin
