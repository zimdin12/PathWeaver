# PathWeaver

**Experimental server-side mod for Minecraft 26.1.2 (Fabric). Moves vanilla mob path searches off the server thread.**

**Read this first: PathWeaver ships with its compatibility checking turned off, so out of the box it runs other mods' uninspected pathfinding code on worker threads. Back up worlds you care about.**

That is a deliberate choice and the reasoning is in the open. The checked tier, `AUDITED`, only honours individual bytecode audits, and on a real modpack that means it denies everything and the mod does nothing — measured at **0 of 187** eligible mob types on a 221-jar server pack. Shipping it as the default shipped something indistinguishable from broken. Shipping `UNSAFE` means it works on arrival and the risk is yours to opt out of, which is the trade this project decided to make.

What you are accepting: the most likely failure is quiet — a wrong path or a torn read. It is not the only possible one: nothing has been proven about code that was never inspected, so a crash or a corrupted world is not excluded, only less likely. Nothing here is evidence that it is safe.

**What has actually happened so far**, stated as what it is: across development this has run on packs of 200–370 mods through hundreds of thousands of dispatched searches, and no corruption or crash has been traced to it. That is an absence of reported problems, not a proof — it is exactly the evidence you would also see if the failure were rare, or quiet, or had not been looked for hard enough. Several defects *were* found in that time, by review and by bytecode audit rather than by anything going visibly wrong in a world.

**So the honest recommendation is to decide for yourself, on a copy.** Run it on a world you can throw away. If mobs path normally and nothing looks off after a few sessions, keep the default. If anything does look off, switch to `AUDITED` and say so — that report is evidence this project cannot generate on its own.

Two ways to opt back into checking, both one setting away:

- `compatibilityTier=AUDITED` — full checking. Expect it to refuse on a heavy pack; the log names which mods did it.
- `trustedMods` — the middle option, and the one worth knowing about. Name the specific mods you have decided to run unaudited and the scan keeps working for everything else. See [Will it actually do anything?](#will-it-actually-do-anything).

Whichever you land on, the game log says so at world start and names the mods responsible, rather than leaving you to infer it from silence.

See the [version-exact compatibility matrix](COMPATIBILITY.md) for audited verdicts, artifact hashes, and the evidence boundary. Future or modified artifacts fail closed — at `AUDITED`. The shipped default does not consult this at all.

## It will tell you if your machine is too small for it

PathWeaver does not make pathfinding cheaper. It moves the same A* work onto another thread so the
server thread has room, and it adds a little of its own on the way — the prologue, the epilogue and
the install all run on the main thread, and every discarded search is CPU spent for nothing. That
trade only pays when a core is free for the worker to use.

On **2 cores or fewer** it says so at world start and recommends `enabled=false`. On **4 or fewer**
it warns that the benefit will be small and points you at `/pathweaver status` to decide. Nothing is
switched off automatically.

This is a structural argument about how the work is scheduled, not a measurement taken on a small
machine — both benchmarks on this page ran on many-core hardware, and that is exactly why the
recommendation is stated rather than enforced.

## What it does

Minecraft runs mob A* path searches on the server thread. PathWeaver runs eligible ones on a small worker pool instead, so a server that is falling behind because of pathfinding can keep up.

It touches every concrete vanilla evaluator: walking, swimming, flying, amphibious, and the frog's and creaking's. Third-party evaluator subclasses stay synchronous unless you choose `compatibilityTier=UNSAFE`. Mob classes added by mods also stay synchronous unless you opt in, either with `allowModdedMobAsync` or by choosing `UNSAFE`, which implies it. It does not move entity ticking, collision, or AI goals off-thread.

Flying and amphibious mobs were excluded before 0.4.0, and the stated reason was wrong in an instructive way. Their evaluators do write to the live mob — but only in `prepare()` and `done()`, never in the search between them. Those two calls now run on the main thread, so the exclusion is gone rather than worked around.

## Will it actually do anything?

At the shipped `UNSAFE` default the answer is yes, on any pack, because no check can stop it. **This whole section describes `compatibilityTier=AUDITED`** — what you get when you opt back into checking.

At `AUDITED`, PathWeaver scans every loaded mod at startup for mixins into pathfinding code. If a mod modifies that code and has not been audited, PathWeaver disables itself for the affected movement family and everything runs vanilla-synchronous.

Failing closed is better than running unaudited code on a worker thread and corrupting a world — which is exactly why it is worth understanding what the default gives up. The consequence, and the reason it is not the default, is that `AUDITED` only does something once every mod in your pack that touches pathfinding has been individually audited. Two of the biggest have been:

- **The Fabric API that PathWeaver itself requires.** It bundles `fabric-content-registries-v0`, which injects into a method the walk search calls for every block. That alone used to deny Walk on *every* stock install. It is now allowed while no mod has registered a land path-type provider — the condition that makes the injection inert — and denies permanently the moment one registers, including cancelling a search already in flight. Fabric API also bundles `fabric-events-interaction-v0`, whose exemption rests on an inventory of calls from ten pinned worker-side classes. That is a bounded sample, not an exhaustive proof that no worker route reaches those methods.
- **Lithium**, which ships in most performance modpacks, and **Diagonal Blocks** (Diagonal Fences/Walls/Windows). Their pathfinding mixins are pinned by hash and checked at startup: the audited classes are rejected if they write a field on the search path, and Diagonal Blocks is additionally required never to reach the unsynchronised shape caches. That is a bounded mechanical check, not a whole-program proof — [COMPATIBILITY.md](COMPATIBILITY.md) states exactly what each verifier inspects. They are allowed at `AUDITED`; see below for the trade.

Mods that just mark a block dangerous — "mobs should avoid my spikes" — are handled generically, with no per-mod entry: their rule is asked for every answer it can give and frozen before any worker sees it. That rests on such a rule being a pure function of the block state, which the API's shape encourages but does not enforce, so it rests on an assumption rather than a proof. A rule that *receives* the surrounding world normally switches Walk back to synchronous, because its answers cannot be precomputed. Farmer's Delight's lit stove is the one exception: it receives the world and provably never reads it, so an exact audit of that artifact lets its answers be frozen too. That audit is bounded evidence, not a proof.

Still denied at the time of writing: Carpet, and any mod not in [the compatibility matrix](COMPATIBILITY.md).

Check your server log for:

```
Foreign-mixin scan complete: scanned=…, failed=…, deniedFamilies=…
```

`deniedFamilies=0` means PathWeaver is active — always true at the `UNSAFE` default. Any other value means it is partly or wholly inactive, and the preceding lines name each mod responsible.

## How many of your mobs are actually eligible

Two numbers from the same 221-jar server pack, because only quoting the flattering one would be misleading:

| Tier | Eligible mob types |
|---|---|
| `UNSAFE` — the shipped default | **187 of 187** (0.3.0 managed 163) |
| `AUDITED` | **0 of 187** |

The second number has been true since 0.3.0 and no version has improved it, which is why it is no
longer the default. Nine mods in that pack —
balm, carpet, expandability, ferritecore, scalablelux, sereneseasons, terrain_slabs, vehicleupgrade
and yungscavebiomes — mix into pathfinding-adjacent code, so the scan denies every movement family
and the mod does nothing. From 0.4.0 it says exactly that at world start and names them.
**What limits PathWeaver is other mods touching block state, not which mobs it can handle.**

Eligibility is not the same as coverage. It means nothing blocks dispatch for that mob type — not
that every movement it makes goes off-thread. Brain-driven movement (`MoveToTargetSink`, which is
villagers, piglins, axolotls, frogs, allays and the warden) and wall-climber chases call
`createPath` directly and stay synchronous by construction; see [DESIGN.md §10](DESIGN.md) for why
that is deliberate rather than a gap.

Where the scan denies nothing — a lean pack, Fabric API and Lithium — `AUDITED` admits everything on
its own, which is the configuration the benchmark below runs in. That is the case the checked tier was
built for, and it is rarer than a modpack list makes it look.

### There is a middle option

`UNSAFE` is all-or-nothing: it waives every denial, permanently, including for mods you install next
month. `trustedMods` is the scoped version — name the specific mods you have decided about, and the
scan keeps working for everything else.

On the same 221-jar server pack, tier set to `AUDITED` throughout:

| `trustedMods` | Result |
|---|---|
| empty | 0 of 187 — nine mods named as blockers |
| 4 of the 9 | still refuses, now names the remaining 5 |
| all 9 | **185 of 187**, `deniedFamilies=0`, 709 searches installed, zero exceptions |

The two still refused are the spiders, whose evaluator stormiespiders replaces — trusting a mod does
not admit a third-party evaluator, which remains `UNSAFE` only.

**This is not a safety feature.** Anything named runs unaudited on worker threads, exactly as `UNSAFE`
would, aimed at fewer mods. Matching is by mod id, so an entry keeps applying after that mod updates
and changes what its mixins do — the audited exemptions are pinned to exact artifact hashes precisely
because this is not.

What 0.4.0 changed is the first row. The 24 types that were ineligible at the widest tier are now
eligible: 12 amphibious, 8 flying, the frog's and the creaking's bespoke evaluators, and both spiders,
which use stormiespiders' `AdvancedWalkNodeProcessor` — the first third-party evaluator this mod has
ever actually dispatched.

Run **`/pathweaver mobs`** for the same breakdown on your own pack.

## What we measured

**The short version: PathWeaver's benefit is fewer tick spikes, not a higher average TPS.**

Average tick rate is not what players notice — a server sitting at "20 TPS" still stutters when one tick in a hundred takes 80 ms. That is what PathWeaver reduces. It raises *throughput* only in the narrower case where path-searching alone already pushes the server past its 50 ms budget and spare CPU cores exist.

### Measured on the configuration you would actually get

This benchmark uses **no harness intervention at all**: stock Fabric API, Lithium loaded, `compatibilityTier=AUDITED`, shipped limits (`maxInFlight=256`, `poolThreads=0`; path reuse off, which is the shipped default). The gate opened on its own. The only difference between arms is the master switch. 1024 zombies in a walled maze, all retargeted every 6 ticks; two pairs, interleaved and order-reversed so machine drift cannot masquerade as an effect, **on the exact jar in this release**.

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 88.5–96.6 ms | **50.0–50.3 ms** |
| Tick interval, p99 | 832–958 ms | **367–383 ms** |
| Effective tick rate | 10.4–11.3 TPS | **20.0 TPS** |
| Main-thread cost per request | 480–500 µs | **195–202 µs** |

Mean tick time fell **43.5% to 48.2%**; p99 fell **55–61%**. No overlap: every async run beat every synchronous one.

**One run is excluded from that range and it is worth saying why.** A synchronous arm came in at 149 ms rather than the usual 88–97, which would make the same asynchronous result read as a 66% reduction. Nothing about the mod changed between those runs — the machine was busier. Quoting it would be picking the flattering sample, which is exactly the mistake an earlier revision of this file made when it published 44.8%.

**Read the spread, not the headline.** The asynchronous arm is remarkably stable — every asynchronous run measured for this release, across three separate sweeps of two versions, landed between 50.0 and 50.3 ms — while the *synchronous* baseline swings between 87 and 108 ms with ambient machine load. Essentially all the variation in the percentage comes from the baseline rather than from the mod, so a single pair over- or under-states the gain by several points. Quote the range.

**These are not comparable to the figures published for 0.3.0.** During 0.4.0's development the mixin that isolates Minecraft's shared path-type cache from workers silently stopped applying, and a search reusing that already-populated shared cache runs faster than one filling a private cache. Every figure here was re-measured after that was fixed, on the exact release artifact rather than a close relative of it.

### Profiled on a real modpack, with spark

The tables above are a synthetic burst in a four-mod environment. This is the same question asked
the way a server admin would ask it: spark, on a 221-jar server pack, 220 mixed mobs (zombies, skeletons,
spiders, bees, drowned) retargeted every 6 ticks across a pillared arena, two 45-second profiles.
Profiles were saved locally rather than uploaded, so the pack's composition stays on the machine.

| | Server-thread time in pathfinding |
|---|---|
| PathWeaver off | 5,572 ms of 45,000 — **12.38%** |
| PathWeaver on | 2,216 ms of 45,000 — **4.92%** |

**Pathfinding's share of the server thread fell by 60%**, with 7,262 searches dispatched and 96.3%
installed over the profiled window.

What remains on the server thread with the mod on is mostly `PathNavigation.createPath` (1,872 ms) —
the dispatch itself: building the region, cloning the evaluator, running the search's prologue. That
is the cost this design deliberately keeps on the tick, and it is what the synchronous arm's 3,664 ms
in `moveTo` becomes. The mod does not make pathfinding free; it moves the search and leaves the
setup.

This run used `UNSAFE`, because that pack denies every family at the shipped default — see the
eligibility table above. On a pack where the scan denies nothing, the same effect is available at
the default.

### 0.3.0 against 0.4.0, same machine, adjacent runs

0.4.0 moves each search's setup and teardown onto the server thread. That is work added back to the
tick to buy four more mob families, and the obvious question is what it cost. Both artifacts were run
through the identical sweep back to back, rather than comparing today's numbers to figures published
weeks ago on a differently loaded machine:

| | v0.3.0 (published artifact) | v0.4.0 |
|---|---|---|
| Asynchronous mean | 50.04 / 50.04 ms | **50.05 / 50.06 ms** |
| Asynchronous p99 | 377 / 372 ms | 381 / 381 ms |
| Searches dispatched | 55,266 / 54,424 | 55,793 / 57,717 |
| Installed | 83.4% / 82.8% | 82.6% / 81.8% |

**The two versions are indistinguishable, and that is the answer.** Hoisting the prologue and
epilogue onto the server thread cost nothing measurable — two hundredths of a millisecond, well
inside run-to-run noise, and the asynchronous arm landed between 50.02 and 50.06 ms across three
separate sweeps of both versions. It is also worth being blunt about what that means in the other direction:
**0.4.0 is not faster than 0.3.0.** This workload is 1024 zombies, which only exercises the walk
search, and 0.3.0 already handled that completely. What 0.4.0 buys is the mob families 0.3.0 could
not touch at all, not more speed on the ones it could.

The percentage reductions differ between the two columns (46.5%/46.1% against 44.5%/45.7%) purely
because the *synchronous* baselines differed by a couple of milliseconds. That is the spread warned
about above, visible in a controlled comparison.

**A caveat this file used to state incorrectly.** Earlier versions said *13.6–20.6% of dispatched searches were not installed in time*, which implied workers were returning results too late to be wanted. 0.4.0 splits the outcome counter by cause, and the measurement says otherwise:

| Outcome | Pair 1 | Pair 2 |
|---|---|---|
| Installed | 65,113 | 65,450 |
| Cancelled before the result landed | 13,773 | 14,044 |
| **Rejected as stale on arrival** | **0** | **0** |
| Admission refused (ran synchronously instead) | 75,227 | 74,586 |

So about **82% of dispatched searches install**, and the rest were **cancelled by the mob changing its mind, not delivered late** — in this workload every mob is retargeted every 6 ticks, and cancelling a navigation is what that does. Not one result was ever rejected for arriving too late.

The number worth watching is the last row: at the shipped `maxInFlight=256`, **admission refused nearly as many requests as it accepted — 49% and 48% across the two pairs.** Those are not wasted — they run synchronously on the tick exactly as they would without this mod — but it does mean the in-flight limit, not worker speed, is the binding constraint under a burst this heavy.

This is still a synthetic burst with all other mob AI stripped out. It shows what happens when pathfinding alone overloads the server; it is not a measurement of ordinary play.

### Swimming, measured separately

Every table above uses zombies, which exercises only `WalkNodeEvaluator`. `SwimNodeEvaluator` was measured on its own: 1024 cod in a flooded maze, same shipped configuration, same paired method.

From 0.4.0 there are six eligible families rather than two, and **only these two have been benchmarked.** Flying, amphibious, frog and creaking searches are proven to dispatch and to return the mob's pathfinding costs — there is a game test that spawns a bee and a drowned and checks exactly that — but nobody has measured what they are worth. Do not read the numbers below as covering them.

| | Synchronous | With PathWeaver |
|---|---|---|
| Tick interval, mean | 50.0 ms | 50.0 ms |
| Tick interval, p99 | 212 / 217 ms | **73 / 75 ms** |
| Main-thread cost per request | 101–103 µs | **12.6–12.8 µs** |

95,204 searches dispatched and 94,214 installed within the capture window — **1.0% not installed in time**, against 13.5% for the walking workload, because swim searches finish fast enough to beat their mob's next request.

Mean tick time did not move, and that is the honest result rather than a disappointing one: 1024 cod never push this server past its budget, so both arms sit at the 20 TPS cap and there is no throughput to reclaim. What changes is the spike profile — **p99 fell 65%** and per-request main-thread cost fell **8×**. This is the clearest demonstration of the claim at the top of this section: the benefit is fewer tick spikes, not a higher average.

### On a real modpack

The four-mod environment isolates the effect but says nothing about a pack where 300+ mods transform the same classes. The same release jar, same load, on a **371-mod server-side derivative of a real pack** at `compatibilityTier=ALL` (the tier now named `UNSAFE`):

| Pair | Synchronous | With PathWeaver | p99 | Effective TPS |
|---|---|---|---|---|
| 1 | 99.4 ms | **49.9 ms** (−49.8%) | 893 → **353 ms** | 10.1 → **20.0** |
| 2 | 90.9 ms | **61.5 ms** (−32.3%) | 815 → **449 ms** | 11.0 → **16.3** |

Mean reduction **41%**, which lands on the same number as the isolated environment. But the spread is much wider, and the difference shows up as work that went unused: **13.4% of dispatches were not installed within the capture window in the first pair, and 38.0% in the second.** The second pair could not reach 20 TPS at all.

These two pairs predate the split outcome counter, so "not installed" here is the old conflated number and its causes were never separated. In the four-mod environment, where they have been separated, the same figure turned out to be cancellations rather than late arrivals. Do not assume that carries over to a busy pack — it has not been measured there.

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

- **`AUDITED`** (not the default; opt in) allows mods cleared by bounded evidence rather than by proof. It is the most conservative tier that exists, and it is what any pack containing Fabric API needs. Four different mechanisms sit behind it, and they are not equally strong — [COMPATIBILITY.md](COMPATIBILITY.md) states each one:
  - **Lithium and Diagonal Blocks**: no field-write opcode in the audited classes on the search path, plus per-mod structural conditions. That check does not model array stores, mutations inside methods those classes call, or effects reached through helpers, so it means no worker write was *found* — it lowers the risk of worker-side corruption rather than excluding it.
  - **Fabric API's interaction module**: an inventory of direct calls from ten pinned worker-side classes, which is a sample rather than an exhaustive proof that no worker route reaches the injected methods.
  - **Mods that mark blocks dangerous**: an assumption that such a rule is a pure function of block state, which the API's shape encourages but does not enforce.
  - **Farmer's Delight's stove**: one artifact's bytecode, read to show the world and position it receives are never loaded, plus a runtime check that nothing has transformed that class.

  All of them also add live block reads, so a search running while the world changes can return a worse path. Path quality under live mutation has not been measured.
- **`UNSAFE`** ignores the scan completely. It also admits third-party evaluator subclasses, which are rebuilt from their constructor plus the four traversal flags vanilla exposes. A mod's evaluator may hold configuration beyond that — a field set after construction, a reference to its own settings — and none of it is copied, so the worker searches under different rules than the mob's own evaluator would use. That is a quietly different path rather than a crash, and it is the failure mode least likely to be noticed. This runs unaudited third-party code on a worker thread, which is the exact thing the scan exists to prevent. Failures are not limited to bad paths. Keep backups.

`allowModdedMobAsync` is an advanced, genuinely unsafe override. It bypasses only the vanilla-origin mob check; every other gate still applies. Do not enable it unless you accept running unaudited mod code on a worker thread.

`compatibilityTier=UNSAFE` implies `allowModdedMobAsync`, because the origin gate is a compatibility check like any other. Leaving it armed under "ignore every check" kept most of a heavily-modded pack's mobs synchronous while the log reported that nothing was being checked. The separate flag remains the way to reach that bypass from `AUDITED`.

## What is unproven

- **Only the saturated-burst case has been measured at shipped defaults.** Realistic mob counts, mixed workloads and ordinary play remain unmeasured.
- **No benefit measured at realistic mob counts or with mixed workloads.** The benchmark was almost entirely pathfinding, with all other mob AI stripped out.
- **Path correctness is proven only in a static world, and only for two of the six families.** Five Walk and Swim cases produced node-for-node identical paths to a synchronous oracle with the world held still, plus a 128-mob soak. **Flying, amphibious, frog and creaking searches have no equivalence evidence at all** — they became eligible in 0.4.0 and that oracle comparison has not been re-run for them. Their prologue and epilogue are argued safe from bytecode, not demonstrated by measurement.
- **Flying searches deliberately do not reproduce vanilla's start node.** A worker draws its start candidate from thread-confined randomness instead of the mob's own, so an async flying search can begin from a different candidate than the synchronous one would have. Vanilla chooses that candidate arbitrarily, so both are valid — but they are not identical, and a path-equivalence test for flying mobs would have to compare reachability rather than nodes.
- **The amphibious malus window is real.** Its evaluator's costs are applied to the mob at dispatch and restored at install, so for roughly one tick the mob carries search costs it would not have carried under vanilla. Only pathfinding is known to read them; nothing was measured to confirm nothing else does.
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
