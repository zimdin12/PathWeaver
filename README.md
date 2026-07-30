# PathWeaver

**Experimental server-side mod for Minecraft 26.1.2 (Fabric). Moves vanilla mob path searches off the server thread.**

**Read this first: PathWeaver refuses to run wherever another mod modifies pathfinding code and that mod has not been individually audited.**

**The default is `AUDITED`, and that is a deliberate admission about the evidence.** The stricter setting, `STRICT`, admits only structural proofs — and the exemption covering Fabric API's own interaction module is a bounded call-sample rather than a proof, so `STRICT` denies any pack containing Fabric API, which this mod requires. Shipping `STRICT` as the default would ship a mod that does nothing on install. `AUDITED` is the weaker evidence standard and the one that describes what can actually be demonstrated today; the settings screen says so in those words. See [Will it actually do anything?](#will-it-actually-do-anything).

See the [version-exact compatibility matrix](COMPATIBILITY.md) for audited verdicts, artifact hashes, and the evidence boundary. Future or modified artifacts fail closed.

## What it does

Minecraft runs mob A* path searches on the server thread. PathWeaver runs eligible ones on a small worker pool instead, so a server that is falling behind because of pathfinding can keep up.

It only touches the exact vanilla `WalkNodeEvaluator` and `SwimNodeEvaluator` searches. Flying mobs, amphibious mobs and evaluator subclasses always stay synchronous. Mob classes added by mods also stay synchronous unless you opt in, either with `allowModdedMobAsync` or by choosing `compatibilityTier=ALL`, which implies it. It does not move entity ticking, collision, or AI goals off-thread.

## Will it actually do anything?

At startup PathWeaver scans every loaded mod for mixins into pathfinding code. If a mod modifies that code and has not been audited, PathWeaver disables itself for the affected movement family and everything runs vanilla-synchronous.

Failing closed is better than running unaudited code on a worker thread and corrupting a world. The consequence is that PathWeaver only does something once the mods in your pack that touch pathfinding have each been individually audited. Two of the biggest have been:

- **The Fabric API that PathWeaver itself requires.** It bundles `fabric-content-registries-v0`, which injects into a method the walk search calls for every block. That alone used to deny Walk on *every* stock install. It is now allowed while no mod has registered a land path-type provider — the condition that makes the injection inert — and denies permanently the moment one registers, including cancelling a search already in flight. Fabric API also bundles `fabric-events-interaction-v0`, whose exemption rests on an inventory of calls from six pinned worker-side classes. That is a bounded sample, not an exhaustive proof that no worker route reaches those methods, so **it is honoured at `AUDITED` only** — which is why `STRICT` is inert on a stock install.
- **Lithium**, which ships in most performance modpacks, and **Diagonal Blocks** (Diagonal Fences/Walls/Windows). Their pathfinding mixins are pinned by hash and checked at startup: the audited classes are rejected if they write a field on the search path, and Diagonal Blocks is additionally required never to reach the unsynchronised shape caches. That is a bounded mechanical check, not a whole-program proof — [COMPATIBILITY.md](COMPATIBILITY.md) states exactly what each verifier inspects. Allowing them requires setting `compatibilityTier` to `AUDITED`; see below for the trade.

Mods that just mark a block dangerous — "mobs should avoid my spikes" — are handled generically, with no per-mod entry: their rule is asked for every answer it can give and frozen before any worker sees it. That rests on such a rule being a pure function of the block state, which the API's shape encourages but does not enforce, so it is honoured at `AUDITED` rather than `STRICT`. A rule that *receives* the surrounding world normally switches Walk back to synchronous, because its answers cannot be precomputed. Farmer's Delight's lit stove is the one exception: it receives the world and provably never reads it, so an exact audit of that artifact lets its answers be frozen too. That audit is honoured at `AUDITED`, not at `STRICT`.

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

This is the one benchmark that used **no harness intervention at all**: stock Fabric API, Lithium loaded, `compatibilityTier=AUDITED`, shipped limits (`maxInFlight=256`, `poolThreads=0`; path reuse off, which is the shipped default). The gate opened on its own. The only difference between arms is the master switch. 1024 zombies in a walled maze, all retargeted every 6 ticks; **four pairs, interleaved and order-reversed, across two builds** (0.2.3 and the released 0.3.0).

| | Synchronous (n=4) | With PathWeaver (n=4) |
|---|---|---|
| Tick interval, mean | 78–96 ms | **49–50 ms** |
| Tick interval, p99 | 726–1096 ms | **338–390 ms** |
| Effective tick rate | 10.4–12.7 TPS | **20.0 TPS** |
| Main-thread cost per request | 421–516 µs | **164–222 µs** |

Mean tick time fell **about 40%** — median 40.1%, mean 41.2%, range 36.2–48.2% across the four pairs — with no overlap: every async run beat every synchronous run.

**Read the spread, not the headline.** The async arm is strikingly stable (49–50 ms in all four runs, across two builds), while the *synchronous* baseline swings 78–96 ms depending on ambient machine load. A single pair therefore over- or under-states the gain by several points; an earlier revision of this table quoted 44.8% because it happened to be paired against the heaviest sync run. Quote the range.

**The caveat that matters: at the shipped in-flight limit, 13.6–20.6% of dispatched searches were not installed within the capture window.** The harness records dispatches and installs and then halts, so it does not observe whether a straggler landed afterwards; read these as work not used in time, not as proven-discarded. Under this much load roughly one search in six is not making it back before it is wanted, which is the default doing real work at saturation rather than a tuned value.

This is still a synthetic burst with all other mob AI stripped out. It shows what happens when pathfinding alone overloads the server; it is not a measurement of ordinary play.

### Swimming, measured separately

Every table above uses zombies, which exercises only `WalkNodeEvaluator`. `SwimNodeEvaluator` is the other family PathWeaver accelerates, so it was measured on its own: 1024 cod in a flooded maze, same shipped configuration, same paired method.

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 50.0 ms | 50.0 ms |
| Tick interval, p99 | 212 / 217 ms | **73 / 75 ms** |
| Main-thread cost per request | 101–103 µs | **12.6–12.8 µs** |

95,204 searches dispatched and 94,214 installed within the capture window — **1.0% not installed in time**, against 13.5% for the walking workload, because swim searches finish fast enough to beat their mob's next request.

Mean tick time did not move, and that is the honest result rather than a disappointing one: 1024 cod never push this server past its budget, so both arms sit at the 20 TPS cap and there is no throughput to reclaim. What changes is the spike profile — **p99 fell 65%** and per-request main-thread cost fell **8×**. This is the clearest demonstration of the claim at the top of this section: the benefit is fewer tick spikes, not a higher average.

### On a real modpack

The four-mod environment isolates the effect but says nothing about a pack where 300+ mods transform the same classes. The same release jar, same load, on a **371-mod server-side derivative of a real pack** at `compatibilityTier=ALL`:

| Pair | Synchronous | With PathWeaver | p99 | Effective TPS |
|---|---|---|---|---|
| 1 | 99.4 ms | **49.9 ms** (−49.8%) | 893 → **353 ms** | 10.1 → **20.0** |
| 2 | 90.9 ms | **61.5 ms** (−32.3%) | 815 → **449 ms** | 11.0 → **16.3** |

Mean reduction **41%**, which lands on the same number as the isolated environment. But the spread is much wider, and the reason is visible in how much work went unused: **13.4% of dispatches were not installed within the capture window in the first pair, and 38.0% in the second**. On a busy pack the workers contend with everything else the pack is doing, so more results arrive too late to be wanted. The second pair could not reach 20 TPS at all.

Take from this that the *shape* of the win holds at pack scale — large p99 reduction, no overlap between arms — while the *size* of it is less predictable than the isolated benchmark suggests.

### `maxInFlight`: leave it alone unless you know which side you want

An earlier revision of this file said `maxInFlight` was "the knob that matters when discards climb", which implied raising it. **That was wrong, and measurably so.** Sweeping it on the same 371-mod pack, same load, with the shipped default repeated last so drift could not flatter the larger values:

| `maxInFlight` | Dispatched | Installed in window | Not installed in window |
|---|---|---|---|
| 256 | 52948 | 45781 | **13.5%** |
| 1024 | 66288 | 6165 | **90.7%** |
| 4096 | 54312 | **0** | **100%** |
| 256 (repeat) | 39396 | 25715 | 34.7% |

These count what the capture observed, not what was terminally thrown away: the harness halts at the end of the window, so the 4096 row says *nothing landed while we were watching*, not that every search was ultimately discarded.

The limit is not a buffer that catches overflow — it is an admission bound, and widening it converts refusals into latency. Workers are a fixed pool, so a deeper queue means each result lands later; a result that arrives after its mob has already asked again is superseded. In this workload every mob repaths every 6 ticks, so once the queue exceeds a few ticks of work, little survives to be installed.

**But do not conclude from that that lower is better.** Sweeping the limit against load (512/1024/1536 mobs) and against worker count (4/8/16) gives the same answer every time: **256 has the better p99, by 8–30%, while 64 leaves almost no work unused (1% against 13–16%). Mean tick time is indistinguishable between them.** A refused request does not disappear — it runs synchronously on the tick, which is where spikes come from — so the larger bound buys tail latency with worker CPU. Since this mod exists to cut spikes rather than raise averages, **the shipped 256 is the right side of that trade and stays.** Below about 64 it stops paying entirely: at 1024 mobs, `maxInFlight=32` was worse on both, 62.7 ms mean and a worse p99 than either larger limit. Full tables in [COMPATIBILITY.md](COMPATIBILITY.md).

Note what that failure looks like from outside: **no errors, and still 20 TPS.** The pool burns CPU on searches nothing consumes while the server looks healthy. Because that is invisible, PathWeaver now samples its own install ratio once a minute and logs a warning naming `maxInFlight` and `poolThreads` if under a quarter of completed searches are being used.

So: lower `maxInFlight` only if worker CPU is scarce and you would rather spend tick time than cores. Raising it above 256 is the one clearly bad move — 1024 and 4096 wasted almost everything.

**On reading tick time and tick rate together.** Effective tick rate is derived from mean tick interval (`mean > 50 ms ? 1000/mean : 20`), not measured separately, so "tick time halved" and "tick rate doubled" are one fact stated twice rather than two independent results. The `20.0` is a clamp: any mean at or under the 50 ms budget reports exactly 20.0, and a server with headroom sleeps to hold that rate, so this metric cannot show how much spare capacity the async arm actually had. The independent signals in these tables are **p99** and **main-thread cost per request**.

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

You can also edit `config/pathweaver.json`. **The exact keys differ between versions — open your own file and edit what is there rather than copying an example from anywhere.** A malformed or unreadable config falls back to synchronous behaviour until a valid one is saved. Worker-thread and in-flight limits, and the compatibility tier, apply after a restart.

`compatibilityTier` decides how much risk to accept from mods that modify pathfinding:

- **`STRICT`** (default) only runs off-thread where a worker provably cannot observe the other mod's change at all.
- **`AUDITED`** additionally allows mods cleared by bounded evidence rather than by proof. It is what most packs need, including any pack with Fabric API. Four different mechanisms sit behind it, and they are not equally strong — [COMPATIBILITY.md](COMPATIBILITY.md) states each one:
  - **Lithium and Diagonal Blocks**: no field-write opcode in the audited classes on the search path, plus per-mod structural conditions. That check does not model array stores, mutations inside methods those classes call, or effects reached through helpers, so it means no worker write was *found* — it lowers the risk of worker-side corruption rather than excluding it.
  - **Fabric API's interaction module**: an inventory of direct calls from six pinned worker-side classes, which is a sample rather than an exhaustive proof that no worker route reaches the injected methods.
  - **Mods that mark blocks dangerous**: an assumption that such a rule is a pure function of block state, which the API's shape encourages but does not enforce.
  - **Farmer's Delight's stove**: one artifact's bytecode, read to show the world and position it receives are never loaded, plus a runtime check that nothing has transformed that class.

  All of them also add live block reads, so a search running while the world changes can return a worse path. Path quality under live mutation has not been measured.
- **`ALL`** ignores the scan completely. This runs unaudited third-party code on a worker thread, which is the exact thing the scan exists to prevent. Failures are not limited to bad paths. Keep backups.

`allowModdedMobAsync` is an advanced, genuinely unsafe override. It bypasses only the vanilla-origin mob check; every other gate still applies. Do not enable it unless you accept running unaudited mod code on a worker thread.

`compatibilityTier=ALL` implies `allowModdedMobAsync`, because the origin gate is a compatibility check like any other. Leaving it armed under "ignore every check" kept most of a heavily-modded pack's mobs synchronous while the log reported that nothing was being checked. The separate flag remains the way to reach that bypass from `STRICT` or `AUDITED`.

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
