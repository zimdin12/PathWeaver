# PathWeaver

**Experimental server-side mod for Minecraft 26.1.2 (Fabric). Moves vanilla mob path searches off the server thread.**

**Read this first: on most modpacks PathWeaver deliberately does nothing.** It refuses to run whenever another mod modifies pathfinding code, and the standard Fabric API is itself one of those mods. See [Will it actually do anything?](#will-it-actually-do-anything) before installing.

See the [version-exact compatibility matrix](COMPATIBILITY.md) for audited verdicts, artifact hashes, and the evidence boundary. Future or modified artifacts fail closed.

## What it does

Minecraft runs mob A* path searches on the server thread. PathWeaver runs eligible ones on a small worker pool instead, so a server that is falling behind because of pathfinding can keep up.

It only touches the exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator` searches, and only for mobs whose class comes from vanilla. Everything else — flying mobs, amphibious mobs, evaluator subclasses, mod-defined mob classes — stays synchronous. It does not move entity ticking, collision, or AI goals off-thread.

## Will it actually do anything?

At startup PathWeaver scans every loaded mod for mixins into pathfinding code. If it finds any, it disables itself for the affected movement family and everything runs vanilla-synchronous.

**This includes the Fabric API that PathWeaver itself requires.** The aggregate Fabric API bundles `fabric-content-registries-v0`, which mixes into pathfinding code. On a stock install that denies both Walk and Swim, so PathWeaver does nothing at all.

(A narrow, exactly-audited exemption for swimming mobs has been prototyped — vanilla's `SwimNodeEvaluator` provably never reaches the method Fabric injects into — but it is **not** active in a released build and is not something you should count on.)

Other common mods that trip the scanner: Lithium, Carpet, ServerCore, rabbit-pathfinding-fix, diagonalblocks. In one 250-mod pack we counted six.

This is deliberate. Failing closed is better than running unaudited code on a worker thread and corrupting a world. But it means **for most people this mod is currently inert.**

Check your server log for:

```
Foreign-mixin scan complete: scanned=…, failed=…, deniedFamilies=…
```

`deniedFamilies=0` means PathWeaver is active. Any other value means it is partly or wholly inactive, and the preceding lines name each mod responsible.

## What we measured

**The short version: PathWeaver's benefit is fewer tick spikes, not a higher average TPS.**

Average tick rate is not what players notice — a server sitting at "20 TPS" still stutters when one tick in a hundred takes 80 ms. That is what PathWeaver reduces. It raises *throughput* only in the narrower case where path-searching alone already pushes the server past its 50 ms budget and spare CPU cores exist.

All figures below were produced **with the compatibility gate manually cleared by a test harness, which a released build will never do for you**, on a 16-core / 32-thread desktop CPU with 8 worker threads. Measurements were reproduced by a second engineer using an audited derivative of the same corrected harness, with fresh worlds and reversed-order paired runs. That is a check on the runs and the analysis, not an independently written timer.

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

**No benchmark has ever been run on the configuration you would actually get.** Every result above used a cleared gate, a much larger in-flight limit than the default, and repath reuse disabled.

## Who this will not help

- **Anyone whose server is not already exceeding 50 ms per tick because of pathfinding.** Below that threshold PathWeaver cannot raise TPS — the server is already keeping up. Under a staggered schedule of 1024 pathfinding mobs we measured **no throughput gain at all**. On an unloaded server a single paired sample measured mean MSPT 2.927 ms without and 3.012 ms with. That difference is well inside our observed run-to-run variance and was not repeated, so treat it as "no measurable benefit", not as a proven cost.
- **Anyone overloaded by something other than mob pathfinding** — chunk generation, redstone, entity ticking, block entities. PathWeaver moves A* and nothing else.
- **Anyone on a small host.** The worker pool defaults to `cores / 4`. On a 2–4 vCPU server that is one worker, competing with the server thread for the same CPU. Our numbers came from 8 workers on 32 idle cores.
- **Anyone running a mod that touches pathfinding** — which is most modpacks.

## Turning it off

With ModMenu installed: **Mods → PathWeaver → Config**. The first option is the master switch; turning it off sends all new path requests through vanilla synchronous pathfinding and disables repath reuse. Work already accepted drains safely; later routing is vanilla-synchronous.

You can also edit `config/pathweaver.json`. **The exact keys differ between versions — open your own file and edit what is there rather than copying an example from anywhere.** A malformed or unreadable config falls back to synchronous behaviour until a valid one is saved. Worker-thread and in-flight limits apply after a restart.

`allowModdedMobAsync` is an advanced, genuinely unsafe override. It bypasses only the vanilla-origin mob check; every other gate still applies. Do not enable it unless you accept running unaudited mod code on a worker thread.

## What is unproven

- **The shipped default configuration has never been benchmarked.** Measurements used a cleared gate, a much larger in-flight limit, and repath reuse disabled.
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
