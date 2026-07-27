# PathWeaver

**Experimental server-side mod for Minecraft 26.1.2 (Fabric). Moves vanilla mob path searches off the server thread.**

**Read this first: PathWeaver refuses to run wherever another mod modifies pathfinding code and that mod has not been individually audited.** Stock Fabric API and Lithium have both been audited, so a typical performance pack can now use it — but plenty of packs will still find it inert. See [Will it actually do anything?](#will-it-actually-do-anything) before installing.

See the [version-exact compatibility matrix](COMPATIBILITY.md) for audited verdicts, artifact hashes, and the evidence boundary. Future or modified artifacts fail closed.

## What it does

Minecraft runs mob A* path searches on the server thread. PathWeaver runs eligible ones on a small worker pool instead, so a server that is falling behind because of pathfinding can keep up.

It only touches the exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator` searches, and only for mobs whose class comes from vanilla. Everything else — flying mobs, amphibious mobs, evaluator subclasses, mod-defined mob classes — stays synchronous. It does not move entity ticking, collision, or AI goals off-thread.

## Will it actually do anything?

At startup PathWeaver scans every loaded mod for mixins into pathfinding code. If a mod modifies that code and has not been audited, PathWeaver disables itself for the affected movement family and everything runs vanilla-synchronous.

Failing closed is better than running unaudited code on a worker thread and corrupting a world. The consequence is that PathWeaver only does something once the mods in your pack that touch pathfinding have each been individually audited. Two of the biggest have been:

- **The Fabric API that PathWeaver itself requires.** It bundles `fabric-content-registries-v0`, which injects into a method the walk search calls for every block. That alone used to deny Walk on *every* stock install. It is now allowed while no mod has registered a land path-type provider — the condition that makes the injection inert — and denies permanently the moment one registers, including cancelling a search already in flight.
- **Lithium**, which ships in most performance modpacks, and **Diagonal Blocks** (Diagonal Fences/Walls/Windows). Their pathfinding mixins are pinned by hash and verified at startup against a bytecode proof that nothing a worker executes writes shared state. Allowing them requires setting `compatibilityTier` to `AUDITED`; see below for the trade.

Mods that just mark a block dangerous — "mobs should avoid my spikes" — are handled generically and need no audit at all, because their rule can be precomputed and frozen before any worker sees it. The exception is a rule that inspects the surrounding world (Farmer's Delight's lit stove is one); those still switch Walk back to synchronous.

Still denied at the time of writing: Carpet, and any mod not in [the compatibility matrix](COMPATIBILITY.md).

Check your server log for:

```
Foreign-mixin scan complete: scanned=…, failed=…, deniedFamilies=…
```

`deniedFamilies=0` means PathWeaver is active. Any other value means it is partly or wholly inactive, and the preceding lines name each mod responsible.

## What we measured

**The short version: PathWeaver's benefit is fewer tick spikes, not a higher average TPS.**

Average tick rate is not what players notice — a server sitting at "20 TPS" still stutters when one tick in a hundred takes 80 ms. That is what PathWeaver reduces. It raises *throughput* only in the narrower case where path-searching alone already pushes the server past its 50 ms budget and spare CPU cores exist.

### Measured on the configuration you would actually get

This is the one benchmark that used **no harness intervention at all**: stock Fabric API, Lithium loaded, `compatibilityTier=AUDITED`, shipped limits (`maxInFlight=256`, `poolThreads=0`, repath reuse on). The gate opened on its own. The only difference between arms is the master switch. 1024 zombies in a walled maze, all retargeted every 6 ticks; two pairs, interleaved and order-reversed.

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 82.4 / 96.5 ms | **48.8 / 50.0 ms** |
| Tick interval, p99 | 769 / 1096 ms | **343 / 362 ms** |
| Worst tick | 879 / 1180 ms | **358 / 391 ms** |
| Effective tick rate | 10.4 / 12.1 TPS | **20.0 TPS** |

Mean tick time fell **44.8%** (89.4 → 49.4 ms), with no overlap — every async run beat every synchronous run. Main-thread cost per request fell from ~441–516 µs to ~164–203 µs.

**The caveat that matters: at the shipped in-flight limit, 14.8% of searches were discarded** (95,842 installed of 112,457 dispatched). Under this much load roughly one search in seven is thrown away and recomputed later. It still wins decisively, but that is the default doing real work at saturation, not a tuned value.

This is still a synthetic burst with all other mob AI stripped out. It shows what happens when pathfinding alone overloads the server; it is not a measurement of ordinary play.

### Earlier figures, measured with the gate forced open

The figures below predate the audited exemptions and were produced **with the compatibility gate manually cleared by a test harness**, on a 16-core / 32-thread desktop CPU with 8 worker threads. Measurements were reproduced by a second engineer using an audited derivative of the same corrected harness, with fresh worlds and reversed-order paired runs. That is a check on the runs and the analysis, not an independently written timer.

### Saturated burst — where it helps

1024 zombies in a walled maze, **all retargeted simultaneously every 6 ticks** (~172 long-range searches per tick):

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 83–87 ms | 50.0 ms |
| Tick interval, p95 | 738–766 ms | 135–139 ms |
| Effective tick rate | 11.4–12.0 TPS | 20 TPS |

Paired reductions of **40.0% and 42.8%**. This is genuine overload relief — but it is an extreme synthetic burst, not ordinary play.

### Staggered schedule — no TPS gain, but the spikes go away

Same 1024 mobs, but each retargeted once per 20 ticks so requests arrive at a steady ~51 per tick instead of all at once. This spreads the load more like a real server does, but it is still a synthetic schedule with all other mob AI stripped out — not a measurement of ordinary play:

| | Synchronous | With PathWeaver |
|---|---|---|
| Effective tick rate | 20 TPS | 20 TPS — **no gain** |
| Tick interval, p95 | 59–67 ms | **52.9 ms** |
| Tick interval, p99 | 77–80 ms | **54.6 ms** |

Throughput is unchanged, because the server was already keeping up on average. What changes is the tail: the worst 1% of ticks drop from 77–80 ms — comfortably over the 50 ms budget, which is what a player perceives as a hitch — to 54.6 ms, essentially at budget.

If your complaint is "it says 20 TPS but it still stutters near the village," that tail is the thing you are feeling, and it is the thing this reduces.

At 256 mobs, and at 1024 mobs with slower retargeting, the pattern repeated: identical throughput, smaller spikes. At 512 mobs results were unstable — three runs showed no difference, one showed a large one.

### How to read these numbers

- **The metric is wall-clock interval between ticks, not CPU time.** It includes the server's own pacing and catch-up.
- **"50.0 ms" is the pacing ceiling, not CPU consumption.** The server sleeps to hold 20 TPS. We can show it stopped overrunning; we cannot show how much headroom it gained.
- **Even when it helps, it is not smooth** — 5% of ticks still exceeded 135 ms in the burst test.
- Main-thread cost of the `moveTo` call itself fell from ~430 µs to ~53 µs. That figure covers only the request call. Installing the finished path and running its callbacks also happen on the main thread and were **not** measured, so the end-to-end main-thread cost per request is unknown — do not read the ratio above as the total saving.
- Fewer or contended cores will not behave like this test.

The figures in this subsection used a cleared gate, a much larger in-flight limit than the default, and repath reuse disabled. The shipped-configuration measurement at the top of this section does not.

## Who this will not help

- **Anyone whose server is not already exceeding 50 ms per tick because of pathfinding.** Below that threshold PathWeaver cannot raise TPS — the server is already keeping up. Under a staggered schedule of 1024 pathfinding mobs we measured **no throughput gain at all**. On an unloaded server a single paired sample measured mean MSPT 2.927 ms without and 3.012 ms with. That difference is well inside our observed run-to-run variance and was not repeated, so treat it as "no measurable benefit", not as a proven cost.
- **Anyone overloaded by something other than mob pathfinding** — chunk generation, redstone, entity ticking, block entities. PathWeaver moves A* and nothing else.
- **Anyone on a small host.** The worker pool defaults to `cores / 4`. On a 2–4 vCPU server that is one worker, competing with the server thread for the same CPU. Our numbers came from 8 workers on 32 idle cores.
- **Anyone running an unaudited mod that touches pathfinding.** The audited list is short and version-exact; anything outside it keeps the affected mobs synchronous.

## Turning it off

With ModMenu installed: **Mods → PathWeaver → Config**. The first option is the master switch; turning it off sends all new path requests through vanilla synchronous pathfinding and disables repath reuse. Work already accepted drains safely; later routing is vanilla-synchronous.

You can also edit `config/pathweaver.json`. **The exact keys differ between versions — open your own file and edit what is there rather than copying an example from anywhere.** A malformed or unreadable config falls back to synchronous behaviour until a valid one is saved. Worker-thread and in-flight limits apply after a restart.

`compatibilityTier` decides how much risk to accept from mods that modify pathfinding:

- **`STRICT`** (default) only runs off-thread where a worker provably cannot observe the other mod's change at all.
- **`AUDITED`** additionally allows mods whose bytecode has been checked to perform no shared-state writes on the search path. Right now that means Lithium, and it is what most performance packs need. The honest trade: these cannot corrupt your world from a worker, but they add live block reads, so a search running while the world changes can return a worse path. Path quality under live mutation has not been measured.
- **`ALL`** ignores the scan completely. This runs unaudited third-party code on a worker thread, which is the exact thing the scan exists to prevent. Failures are not limited to bad paths. Keep backups.

`allowModdedMobAsync` is an advanced, genuinely unsafe override. It bypasses only the vanilla-origin mob check; every other gate still applies. Do not enable it unless you accept running unaudited mod code on a worker thread.

## What is unproven

- **Only the saturated-burst case has been measured at shipped defaults.** Realistic mob counts, mixed workloads and ordinary play remain unmeasured.
- **No benefit measured at realistic mob counts or with mixed workloads.** The benchmark was almost entirely pathfinding, with all other mob AI stripped out.
- **Path correctness is proven only in a static world.** Five Walk and Swim cases produced node-for-node identical paths to a synchronous oracle with the world held still, plus a 128-mob soak.
- **Under live block changes we found no failures but did not check path quality.** Across 66,144 searches in three mod sets there was no crash, no search failure and no worker-pool failure. We did **not** compare the paths produced while blocks were changing, so stale or wrong paths during world mutation are simply not measured.
- **Repath reuse has never shown a measurable benefit** in any run. It appears harmless; treat it as unproven, not as a speed-up.
- **Mob behaviour under load.** Async paths install at least one tick later than synchronous ones. Nothing we ran checked whether mobs behave the same when a thousand of them are served asynchronously.

## Known limitations

- Workers read live chunk and mob state through `PathNavigationRegion`, which is a read-only **view backed by live chunks, not an immutable snapshot**. A block change mid-search can be observed by a worker. Our stress testing found no failure from this; that is not a proof that none exists. A private snapshot evaluator was designed, cost-measured and rejected as too expensive; the only real fix is an upstream immutable-chunk API that does not exist.
- **The compatibility scan is a conservative best-effort list, not a complete picture of what a worker touches.** It checks a fixed set of pathfinding classes. It cannot see mods that mixin into vanilla `Entity`, `LivingEntity` or `Mob` methods a worker reaches, nor into the chunk, section and palette delegates that back the world reads. A clean scan means no *known* sensitive target was hit — not that the search is provably isolated.
- Chunk unloading was not meaningfully exercised in testing.
- Server-side. Vanilla clients connect normally.

**Keep backups.**

## Requirements

Minecraft 26.1.x, Fabric Loader 0.19+, Fabric API, Cloth Config, Java 25. ModMenu optional but recommended for the toggle.

## Building and testing

```bash
./gradlew clean test build
```

When reporting an issue, include your Minecraft/Fabric/PathWeaver versions, your config, the full mod list, the log (including the scan line above), and reproduction steps. For anything performance-related, include a real profiler capture — and note whether your server was actually exceeding its tick budget beforehand.

Source and issues: <https://github.com/Zimdin12/PathWeaver>
