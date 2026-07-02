# PathWeaver — Design Spec

**Date:** 2026-07-02
**Status:** Design approved, pending final user review before implementation plan.
**Target:** Minecraft 26.1.2, Fabric loader, Java 25. Server-side, no rendering.
**Tagline:** *Async mob pathfinding that cannot corrupt your world — because it only ever touches a read-only snapshot.*

---

## 1. Problem & Goal

Mob pathfinding (`WalkNodeEvaluator`, `PathFinder`, `PathNavigation`) is the #1 steady-state server hotspot in a dense modded world (spark: `WalkNodeEvaluator` 794 samples; mean MSPT 17–24 ms in a 40-villager village). The expensive part — the A* search — runs on the main server thread every time a mob repaths.

**Goal:** Move the A* *search* off the main thread onto a read-only world snapshot, safely, and reduce how often mobs repath at all — without changing observable mob behavior and without duplicating or conflicting with Lithium.

**Non-goals (explicit, for credibility):**
- NOT async entity ticking (unsafe by construction — races on live world state; this is what the `Async` mod does and why it silences the vanilla race detector).
- NOT async collision.
- NOT faster node/neighbor evaluation — **Lithium already owns that** (`PathNodeCache`, `BlockStateBaseMixin`). We coordinate with it.

## 2. Core Safety Claim

We never run the tick off-thread. We run **only** the A* search, **only** against `PathNavigationRegion` (already a read-only block copy that excludes entities: `getEntityCollisions → List.of()`), **only** for mobs whose `NodeEvaluator` is proven to read nothing but that snapshot. There is no shared mutable state on the worker thread, so there is nothing to race on. **Safe by construction, not by locking.**

## 3. Features

PathWeaver ships two independently-toggleable features:

### Feature A — Async A* on a shared, right-sized snapshot
Move `PathFinder.findPath` off-thread. Backbone = a per-tick, right-sized, immutable `PathNavigationRegion` shared across nearby mobs. Snapshot-sharing + radius-right-sizing is behavior-neutral and is a main-thread win *even before* async.

### Feature B — Conservative repath elision (behavior-preserving)
Vanilla short-circuits a repath only on *exact* target-block equality (`createPath` ~line 153), so a target moving 1 block forces a full A*. Lithium optimizes the *block-change* trigger but not this *goal-driven cadence*. Widen the short-circuit to a small tolerance, and repath on a block change only if the changed block lies on the *remaining* path. (This is the Mobtimizations 1.20.1 approach.)

## 4. Architecture — 6 components

1. **`PathRequestInterceptor`** — mixin on `PathNavigation.createPath` (main thread). Runs the `SafetyGate` check; if eligible, obtains/builds the shared snapshot, snapshots the mob's malus + capability flags (`canOpenDoors`/`canFloat`/hitbox/`FOLLOW_RANGE`), builds a `PathRequest`, submits it, and returns the mob's *current* path unchanged. If ineligible → vanilla sync path, unchanged.

2. **`SafetyGate`** — **exact-class allowlist** of `NodeEvaluator`: `WalkNodeEvaluator`, `SwimNodeEvaluator`, `AmphibiousNodeEvaluator`, `FlyNodeEvaluator`. `evaluator.getClass() ==` (NOT `instanceof` — `AdvancedWalkNodeProcessor extends WalkNodeEvaluator`, so `instanceof` would wrongly pass stormiespiders). Default-deny. **Plus startup foreign-mixin detection:** scan loaded mixin configs for any *other* jar targeting `WalkNodeEvaluator`/`FlyNodeEvaluator`/`PathFinder`; if found (e.g. salts_animal_farm), force the affected mob families sync (the one hole class-allowlisting can't see, since a mixin keeps the class identity).

3. **`SnapshotProvider`** — builds/caches the right-sized immutable `PathNavigationRegion` per (region, tick). Radius = `min(vanilla radius, actual maxPathLength + margin)`, never below path length. Cache keyed so nearby mobs in the same tick reuse one region. Cleared at tick end. **Worker-thread rule:** the region must carry its own snapshot-local `PathType` cache or per-request map — workers must NOT read `ServerLevel.PathTypeCache` (main-thread-mutated via `invalidate` on block change) and must rely on Lithium's immutable per-`BlockState` precompute where present.

4. **`PathWorkerPool`** — bounded `ThreadPoolExecutor`, size = config default `max(1, cores/4)`, capped to never starve render/main threads. Runs `findPath` on the snapshot only. Pure compute. Worker exception → that request falls back to sync, logged once.

5. **`ResultInstaller`** — drained on the main thread at entity-tick start. Installs completed `Path`s. **Staleness check:** if the mob moved beyond a threshold or the target changed since dispatch, discard and let it re-request. Never blocks — if no result yet, the mob keeps its current path.

6. **`PathWeaverConfig`** (cloth-config) — feature-A toggle, feature-B toggle, pool size, max in-flight requests, per-dimension toggle, "sync-fallback-only" panic switch, optional distance-scaled throttle (opt-in, off by default — it makes far mobs visibly dumber), debug counters (async'd vs synced, avg latency).

## 5. Data Flow

```
Mob repaths → Interceptor (main): gate → snapshot flags → PathRequest → queue
                                    │ (ineligible) → vanilla sync path
   worker thread: A* on shared immutable snapshot → completed Path → result queue
   next tick start (main): Installer: staleness check → assign Path | discard+rerequest
```
**Latency = 1 tick (50 ms)** request→install. Imperceptible; mob keeps old path meanwhile, never freezes.

## 6. Correctness & Failure Handling

- **Snapshot immutability is the whole game.** Only audited evaluator classes allowed; any that reads live state is off the list.
- **Determinism:** async A* on the same snapshot yields the identical `Path` as sync. Asserted in tests; any divergence is a bug, not a knob.
- **Worker exception → sync fallback**, logged once. Never silent, never crash, never block main thread.
- **Lithium coordination:** mixin `@At`/priority set to compose with Lithium's `WalkNodeEvaluatorMixin`/`BlockStateBaseMixin`/`inactive_navigations`; never duplicate node-eval caching.
- **Feature B fidelity:** tolerance short-circuit must not skip a repath that vanilla would have taken when the path is genuinely invalidated (changed block on remaining path always repaths).

## 7. Mob Coverage (from safety audit)

- **ASYNC-SAFE (default win):** vanilla mobs, MCA villagers (inherit stock `GroundPathNavigation`+`WalkNodeEvaluator`), Animal Garden (26 species), Ecologics, EnderZoology, bees, cats, dogs, horses, warband, herdinstinct, fleeinganimals, smbs, IllagerInvasion.
- **MUST-STAY-SYNC (gate denies automatically):** stormiespiders (`AdvancedWalkNodeProcessor` reads live level + collision), salts_animal_farm (mixins into vanilla `WalkNodeEvaluator.getPathType`, reads live rain/entities/static map).

## 8. Testing (the publish-grade bar)

- **Equivalence tests (headline):** corpus of start/target/terrain fixtures; assert `asyncPath == syncPath`. This is the safety proof.
- **Gate tests:** modded/unsafe evaluator → routed to sync; foreign-mixin detection → affected family forced sync.
- **Repath-elision fidelity tests:** tolerance never skips a genuinely-required repath.
- **Soak/stress:** N mobs, dense scenario, 1 h on LAN world; assert zero desync/CME/corruption + measure MSPT delta.

## 9. Build & Publish

- Fabric Loom, no-remap, `fabric.loom.disableObfuscation=true`, JDK 25, Gradle 9.5 (the pack's 26.1.2 build recipe). Deps: fabric-api, cloth-config.
- Repo split common/fabric for a later NeoForge port.
- Modrinth + GitHub, MIT. README leads with the safety argument + equivalence-test proof and an explicit "what this does NOT do" section (no async ticking/collision — that honesty is the credibility).

## 10. Estimated Effort

Prototype (Feature A, vanilla+MCA, sync fallback, no config polish): ~6–10 h. Full publishable (both features, config, gate hardening, test suite, docs): ~15–30 h.

## 11. Expected Payoff

~4–8 ms off mean MSPT in dense village/high-mob scenes (moves ~25–40% of AI cost off-thread), on top of existing throttles/ZGC/Lithium. Feature B additionally cuts full-A* recomputes for chasing mobs. Meaningful for holding 20 TPS; not transformative.
