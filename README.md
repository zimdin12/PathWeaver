# PathWeaver

**Async mob pathfinding for Minecraft 26.1.2 (Fabric) — safe by construction.**

PathWeaver moves the expensive part of mob pathfinding — the A\* search — off the main server thread, onto a read-only world snapshot. It targets the single biggest steady-state server cost in a busy world (mob pathfinding: `WalkNodeEvaluator`, `PathFinder`, `PathNavigation`) without changing how mobs behave and without fighting Lithium.

> **The one-line pitch:** other async mods make the game *not crash* on unsafe concurrent access. PathWeaver is safe *by construction* — the worker thread only ever touches an immutable snapshot and a private, freshly-built search, so there is nothing to race on.

## Why this is safe when naive async isn't

Running Minecraft's *entity tick* on multiple threads is unsafe: the tick mutates shared world state, so it races no matter how many locks you add. That's why other async mods disable the vanilla race detector rather than fix the races.

PathWeaver never runs the tick off-thread. It runs **only the A\* search**, and only when two conditions hold:

1. **Immutable inputs.** Vanilla already runs A\* against a `PathNavigationRegion` — a read-only copy of the blocks that excludes entities. PathWeaver builds that region on the main thread and hands the worker an immutable snapshot.
2. **Zero shared search state.** The `PathFinder` and `NodeEvaluator` objects hold per-search scratch state (open-set, node pool, `PathfindingContext`) and are **not** safe to reuse across threads — the subtle trap that corrupts naive implementations. PathWeaver builds a **fresh, isolated** `PathFinder` + `NodeEvaluator` for every search, with the mob's config flags copied exactly. The worker shares no mutable state with the main thread.

The result installs next tick via vanilla's own `moveTo(path, speed)`. Until then the mob keeps its current path, so it never stalls. **The computed path is identical to what vanilla would produce** — same region, same `findPath`, same evaluator config.

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

- **Async today:** land + flying mobs (`WalkNodeEvaluator`/`FlyNodeEvaluator`) — villagers, most animals, zombies, etc.
- **Sync fallback (safe, no benefit):** aquatic mobs (their evaluators take a constructor argument), and any mob touched by an untrusted third-party pathfinding mixin.

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

## Requirements

- Minecraft **26.1.2**, Fabric loader, Java **25**
- [Fabric API](https://modrinth.com/mod/fabric-api), [Cloth Config](https://modrinth.com/mod/cloth-config)
- Server-side (works on dedicated servers, LAN hosts, and singleplayer). Pairs well with Lithium.

## Correctness & verification

- **By construction:** async and sync run the *same* `findPath` on the *same* region with a config-identical evaluator → identical `Path`.
- **Unit tests** cover the safety gate, the foreign-mixin scanner (JSON + ASM), the snapshot cache, the worker pool (isolation, saturation fallback, exception→sync), the result installer (staleness, once-only delivery), repath tolerance, and the equivalence-critical evaluator clone.
- **Soak-tested:** 80 mobs pathfinding continuously produced **700+ off-thread searches with zero exceptions and zero corruption**. (The isolated-finder design was validated by *first* reproducing the race with a shared finder, then eliminating it.)

## License

MIT © 2026 Zimdin
