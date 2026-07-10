# Changelog

## 0.1.0 — Initial release (2026-07-10)

First public release. Async mob pathfinding for Minecraft 26.1.x (Fabric), **safe by construction**.

### Features
- **Async pathfinding** — the A\* search runs on a bounded worker pool off the server thread. Only mobs whose `NodeEvaluator` is an exact-match allowlisted vanilla class are eligible (`WalkNodeEvaluator`, `FlyNodeEvaluator`, `SwimNodeEvaluator`); everything else uses unchanged vanilla synchronous pathfinding.
- **Conservative repath elision** — reuse a live path when a requested target is within a small tolerance of the current one (vanilla only reuses on an exact block match). Default tolerance `1`; `0` = vanilla behaviour.

### Safety
- Each off-thread search runs on a **freshly-isolated `PathFinder` + `NodeEvaluator`** (no shared per-search scratch state) and a **per-thread path-type cache** — so the worker never writes shared main-thread pathfinding state.
- Entity `onPathfindingStart/Done` callbacks and the step-height attribute are resolved on the **main thread**, never off-thread.
- **Exact-class default-deny gate** plus a startup scan of every other mod's mixins: any mod that mixes into the vanilla pathfinding classes forces the affected mob family back to synchronous pathing. Fabric API and Lithium are trusted; unknown third-party pathfinding mixins are not.
- Any guard hit, pool saturation, or worker failure **degrades cleanly to vanilla synchronous pathfinding** — never a crash, never a stuck mob.

### Notes
- Server-side (dedicated servers, LAN hosts, singleplayer). Pairs well with Lithium.
- Requires Fabric API, Cloth Config, Minecraft 26.1.x, Java 25.
