# PathWeaver compatibility matrix

This table is version-exact. A verdict applies only to the listed artifact on Minecraft **26.1.2**. PathWeaver fingerprints audited artifacts and relevant vanilla classes at startup; a changed version, byte, mixin selector, target descriptor, plugin contribution, missing class, or partial bundle fails closed to synchronous pathing.

`SAFE` means the exact audited worker-reachable behavior uses parameters, immutable data, or per-search state and performs no unsafe shared mutation or unbounded callback. `STALE-PATH RISK ONLY` means no worker shared-state mutation was found, but concurrent live reads can select a stale/wrong path and may expose the existing palette/storage lookup-exception envelope. `DENIED` means the default proven-safe tier keeps the affected evaluator family synchronous.

The verdict interacts with the `compatibilityTier` setting. `STRICT` (the default) honours only `SAFE` rows. `AUDITED` additionally honours `STALE-PATH RISK ONLY` rows, which is the setting a Lithium pack needs — and, since Fabric API's own interaction module is now a `STALE-PATH RISK ONLY` row, the setting a *stock* Fabric install needs before PathWeaver does anything. `ALL` ignores this table entirely and is not covered by any evidence here.

The tier is read once, when the startup scan computes this evidence, and is frozen for the rest of the process. Saving a different tier from the settings screen persists it and applies on the next launch; it does not re-open or re-close anything in the running server. Making it live would mean atomically rebuilding every denial and revalidating requests already admitted under the old tier, and doing it partially is worse than not doing it: a session started at `ALL` and saved to `STRICT` would otherwise keep dispatching work `STRICT` forbids.

| Mod | Exact version tested | What it modifies | Worker can reach it? | Verdict | Evidence hash (artifact SHA-256) |
|---|---|---|---|---|---|
| ServerCore | `1.5.19+26.1.2` | Three redirects in `PathFinder.findPath(PathNavigationRegion, Mob, Set, float, int, float)` replacing stream/map construction with a local map and per-search evaluator scratch | Yes | **SAFE** — exact exemption implemented | `593941ef360ba493b180c213bbb093d95223dba4a34d97e7559b914847363aa4` |
| rabbit-pathfinding-fix | `1.3.0` | `PathNavigation.doStuckDetection(Vec3)` and `resetStuckTimeout()` navigation-maintenance injections | No in the pinned submitted search closure; a live worker-marker probe observed zero entries | **SAFE** — exact non-reachability exemption implemented | `6388f7a83b303c7de485f5f0089bd7e887ea45f9adf6bc9b099cad932fa58851` |
| Fabric content registries | `11.2.1+76b0b6bb4c` | Land path-type hooks in `PathfindingContext`/Walk plus the structural `BlockStateBase` refresher | Yes for Walk; exact Swim route is structurally independent | **SAFE only while sealed empty** — exact lifecycle hooks publish a process-lifetime registration latch before provider mutation, worker lookup returns before the live registry map, dispatch denies after publication, and an exact in-flight Walk result is discarded if registration wins before install. | `d1c8a0a2753850ec422f9c03824a0475a24f1d27bbbf1227d9f9d952406bebd1` |
| Fabric events interaction | `5.2.2+07b380be4c` | Two cancellable `HEAD` injections into the exact `BlockStateBase.useItemOn` and `useWithoutItem` descriptors | Not from the six pinned worker-side classes that were inventoried | **STALE-PATH RISK ONLY** — the non-reachability argument is a bounded sample, not an exhaustive proof: it inventories direct calls from six pinned classes and does not traverse the whole worker call graph or build a reverse callsite inventory for those two methods. Honoured at `compatibilityTier=AUDITED`; denied at the `STRICT` default, which is why a stock Fabric install is inert at `STRICT`. | `dc4a15c9250c6d0e5839e5b696792b06869c65f1ab7e71627986d8f9ed247d60` |
| Farmer's Delight Refabricated | `26.1-3.6.7+refabricated` | Registers a **dynamic** land path-type provider (`AbstractStoveBlock`) | Yes when its provider is registered | **ALLOWED at `AUDITED`** — its provider receives the world and never loads it, so the answers are precomputed and frozen; denied at `STRICT` | `25adee6361b37f1e559373bf6aedc90fa62b2da8ab084e3dee53f037ffcac636` |
| Spiky Spikes | `26.1.0` | Registers **static** land path-type providers (fixed enums) | Answers are precomputed, so its code never runs on a worker | **ALLOWED at `AUDITED`** — certified generically, no audit or hash pin needed, but the certification rests on a provider being a pure function of block state; denied at `STRICT` | `323d974770988a17c6d054dc879b528c5e5c25ac48241748316bb6aa679eee04` |
| Carpet | `26.1+v260402` | Navigation `createPath` overload/deferred-return behavior and a piston transformation | Not from the audited worker search entry | **DENIED** pending a live logging/deferred-return semantic witness; it will be dropped if that witness is ambiguous | `59bd225d12423a7d7a635ca0c94fa786f97ccebb116922b16d76072da4ee67e7` |
| Lithium | `0.24.6+mc26.1.2` | Region/block-state path-type shortcuts, block-state flag cache, chunk-access region lookup, and inactive-navigation listener bookkeeping | Yes, except the navigation hook | **STALE-PATH RISK ONLY** — bounded bytecode check: no field-write opcode in the audited classes on the search path (see [what the verifiers actually check](#what-the-structural-verifiers-actually-check)); honoured at `compatibilityTier=AUDITED`, denied at the `STRICT` default | `509e7f770c7d48bd37e9592917329db2768e4695c72a43e22c19ef64d0f9839f` |
| Diagonal Blocks | `26.1.0` | `WalkNodeEvaluator.isDiagonalValid` override reading diagonal connection properties | Yes | **STALE-PATH RISK ONLY** — no field-write opcode; reads the search-owned context plus one map assumed immutable from its `static final` declaration, and is mechanically required never to call into the unsynchronized shape caches; honoured at `compatibilityTier=AUDITED` | `df59211601dc83718ec0189a56c9f5569a0654f56a58fbbd644ea462a51b74d6` |

## Milestone 1 evidence details

### ServerCore `1.5.19+26.1.2`

- Configs: `servercore.common.mixins.json`, SHA-256 `39a5120066542578e74e3775a880d14f04bee935e2d6764132cdf3f7d7af82a7`; `servercore.fabric.mixins.json`, SHA-256 `93b73019559e3c40245fc684d3d4e1b06049362ae3eaa5db53b179807a014a9f`
- Mixin: `me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin`, SHA-256 `ff0e986419f4685469063772c85e477810dfe425bf33a1ad1a62ed65ac6aefa7`
- Plugin: `me.wesley1808.servercore.mixin.ServerCoreMixinPlugin`, SHA-256 `0e6ddc8d3c66c7e5826831845e0da41f6594b758a128d207419083b081e33cf6`
- Vanilla `PathFinder`, SHA-256 `095d620eaac37aa71af017858682e89689039a3b999cf2a5fcfce3f1c3973b2c`
- Shape proof: exactly three `@Redirect` sites on the exact public `findPath` descriptor; the first two return null sentinels, and the third allocates a local `Object2ObjectOpenHashMap`, iterates the request-local target set, and calls only `NodeEvaluator.getTarget` plus collection/value accessors. Its only field read is the per-search `nodeEvaluator`; no field write exists.
- Plugin proof: `getMixins()` returns null and `acceptTargets`, `preApply`, and `postApply` are inert. The prepared runtime claim proves the configured PathFinder mixin was actually selected.

### rabbit-pathfinding-fix `1.3.0`

- Config: `rabbit-pathfinding-fix.mixins.json`, SHA-256 `4adce45f270e2890686cd403392fdb81f1450024ff6814df04e51c57ec49fde6`
- Mixin: `net.litetex.rpf.mixin.EntityNavigationMixin`, SHA-256 `bb31e6819c0d00216c9f2841849beff0ce5234f298d804876a91f7e5b225926b`
- Vanilla `PathNavigation`, SHA-256 `ecfbf40003f91522f8cb99da84ff4ab9e4891e9511808412421fc640be7b339e`
- Pinned worker `PathFinder`, SHA-256 `095d620eaac37aa71af017858682e89689039a3b999cf2a5fcfce3f1c3973b2c`
- Shape proof: exactly two injections, into `doStuckDetection(Vec3)` at the pinned `Path.getNextNodePos()` invocation and into `resetStuckTimeout()` at `TAIL`. The pinned worker pool invokes one submitted `Callable`; its exact generated search closure invokes the pinned `PathFinder.findPath` descriptor and contains no `PathNavigation` call. A test-only live injection into both Rabbit-modified methods observed zero entries while `PathWeaverThread.isWorker()` across the exact dispatch/install witness.

## Milestone 2 evidence details

### Fabric content registries `11.2.1+76b0b6bb4c`

- Module SHA-256 `d1c8a0a2753850ec422f9c03824a0475a24f1d27bbbf1227d9f9d952406bebd1`; config SHA-256 `0e9df73ad0f08696f4bf99024307b8b72151d13c7626f23e456d115b9eb65f9e`.
- Exact `LandPathTypeRegistry` SHA-256 `292f7f5c80e2a7afe220e050940e83448e38262d1d517a3b89eb50f5ad138a9c`; exact hooks prove both registration routes mutate `PATH_TYPES` and the lookup route reads it.
- Ordering contract: registration publishes a monotonic atomic latch before `PATH_TYPES.put`; dispatch reads that latch; workers cancel the provider lookup before its live `IdentityHashMap` read; main-thread installation rechecks the latch only for exact Walk requests captured under the sealed-empty assumption.
- Explicit interleaving tests pin all outcomes: dispatch after publication denies; registration between dispatch and install discards that exact result; install before registration linearizes the empty-registry result before Fabric's later non-retroactive mutation. There is no production reset.

### Fabric events interaction `5.2.2+07b380be4c`

- Module SHA-256 `dc4a15c9250c6d0e5839e5b696792b06869c65f1ab7e71627986d8f9ed247d60`; config SHA-256 `9a8445db121fce8e80c928290b8623f2f6e126459fddcb259b2016ae777f9759`; mixin SHA-256 `c35a9d60b12e32f2b1540b0116f6459bf515e8d1901dc18be5ebff9fd5bf72e7`.
- The exact mixin has only the two pinned cancellable `HEAD` injections on `useItemOn` and `useWithoutItem`. The MC 26.1.2 worker `BlockState` invocation inventory is pinned to `is`, `getFluidState`, `isAir`, `isPathfindable`, `getCollisionShape`, `getBlock`, and `getValue`; neither interaction descriptor is reachable.
- Altered module/config/mixin/vanilla bytes, wrong version, plugin contribution, changed selector, added injector, added sensitive claim, incomplete bundle, and ambiguous module origin all deny.

## Milestone 2 live verification scope

The stock aggregate-Fabric witness runs in a dedicated GameTest harness that registers only the milestone-2 test and deliberately loads no test Mixin on sensitive pathfinding classes. This separation is required because the milestone-1 Rabbit worker probe itself targets `PathNavigation`; loading that probe would make the observer contribute the sensitive claim it is measuring. The test asserts both that the dedicated harness mod is loaded and that no active harness-owned Mixin config contributes a sensitive claim.

With the exact aggregate Fabric API modules loaded, the unmodified production scanner reported `scanned=37`, `failed=0`, `deniedFamilies=0`. The test required active prepared claims, exact module/config/class/vanilla identity, no plugin, the complete audited claim bundles, and live near-miss denial. It observed a genuine Walk request increase both real counters, then dispatched a second exact Walk request and registered a real provider before installation; the terminal recheck discarded that captured result. Final live counters were `dispatched=2`, `installed=1`, `discarded=1`. A subsequent provider-present Walk produced a real synchronous path without adding an async dispatch. The run also exercised the worker provider-map bypass. No `SafetyGate` clearing or synthetic production decision was used.

The original milestone-1 harness remains separate and green: all three registered tests passed with the intentional test-probe denial and ended at `dispatched=11`, `installed=8`, `discarded=3`. The full unit suite contains 166 passing tests, and production plus source JARs contain no GameTest/probe artifacts.

## Verification scope

Milestone 1 was exercised by a registered, sequential phase in a real Fabric GameTest JVM with both exact mods loaded. Startup verified both runtime tuples, including the prepared ServerCore plugin class name and loaded class-byte hash. The isolated exact decision produced real worker registration, dispatch, and install. Version-near-miss decisions for each mod denied both eligible evaluator families and produced synchronous paths with zero new async dispatches. All three registered GameTests passed; the run ended at `dispatched=11`, `installed=8`, `discarded=3`, and the Rabbit worker-marker probe remained zero. This witness proves the exact loaded tuple and near-miss behavior; it does not certify future versions.

## Milestone 5 evidence details

### Lithium `0.24.6+mc26.1.2` — audited tier

Lithium decides whether PathWeaver does anything on a real performance modpack, so it is pinned to
the exact artifact rather than trusted by name. The artifact verified here is byte-identical to the
Modrinth release (`sha1 7631a4e81fcca6290bc32374a4338148bd2ba1ae`).

- Configs: `lithium.mixins.json`, SHA-256 `f9674d7b9bb56ba70aedae56bb07c46ed82b94f554c8573a1a8420350827dd37`; `lithium-fabric.mixins.json`, SHA-256 `e1bfe4635f34f0924b85d607fbd2416896a6591176bd4849b19047dd27c40c29`
- Plugin: `net.caffeinemc.mods.lithium.mixin.LithiumMixinPlugin`, SHA-256 `b97aed37b9ed2f2bd81868682ce8aac62808ec775fa3899afbca751ea204226a`. Unlike ServerCore's, this plugin is *not* inert — it selects mixins from `lithium.properties`. It is therefore pinned by hash and every exempted claim carries its plugin identity, so a pack with different Lithium options produces different claims and falls back to denial rather than reusing this audit.
- Audited mixins: `ai.pathing.BlockStateBaseMixin` `98e0029073adbf8ff610e6e69af696fc28d914b17e8c0d0bd78a22a806fccd19`; `ai.pathing.WalkNodeEvaluatorMixin` `ac04c4283d7502861410749c5d77ab83e02639166504f948630dee15a6953c73`; `ai.pathing.PathNavigationRegionMixin` `cb06d8689a5a77e54e77d0443611d3c3b3929b3ef61d0b423a7caead44d93593`; `ai.pathing.PathfindingContextMixin` `2dbd5a9f785ee775b8070b3add8a878154eb2d514e3df0b8a7878e449c2e2fea`; `ai.pathing.PathfindingContextAccessor` `88bac968c7a2476d802617aab427a54138cfffa49160b09981ccc3c01e3105b1`; `util.block_tracking.BlockStateBaseMixin` `4471cdb6ee762517ee42b1f7f4e2fc78e477547940900f1c34c290d1c811fc75`; `util.chunk_access.PathNavigationRegionMixin` `4bd80e9ef6c9bdccd0fdb1544cf5f7efc18fab24a5dca546e36eb2a6f94d885d`; `entity.inactive_navigations.PathNavigationMixin` `0c14996f3832bd7e8f2c51a963fa260f7a4f95bb3fe6311098a33102340ef146`

**Shape proof.** The load-bearing property is that nothing a worker executes writes shared state, and
it is enforced structurally rather than asserted. Every `PUTFIELD`/`PUTSTATIC` in the audited classes
must occur in a constructor, a static initializer, or one of the two eager cache initializers
(`lithium$initializePathNodeTypeCache`, `lithium$initializeFlags`); the check fails on any other
write. `BlockInfoInitializer.initializeBlockInfo()` drives both initializers across the whole
`Block.BLOCK_STATE_REGISTRY` at startup on the main thread, and no other method may call them, so a
worker cannot trigger a lazy write even before initialization. `ai.pathing.PathNavigationRegionMixin`
is additionally required to do its writing from a constructor injection, because PathWeaver builds
the region on the main thread at dispatch and that ordering is what makes those writes safe.
`util.chunk_access.PathNavigationRegionMixin` contains no field write at all.

`entity.inactive_navigations.PathNavigationMixin` is the exception and rests on a different proof.
Its handlers genuinely mutate shared state — they add and remove navigations from a listener set on
the level — so write-confinement would not save it. It is safe because a worker never runs it: the
worker's entry point is `PathFinder.findPath`, which contains no call edge into `PathNavigation`.
That is the same non-reachability proof the rabbit-pathfinding-fix exemption uses, re-checked here
against the vanilla bytes actually loaded.

**What this does not prove.** Lithium still adds live section and palette reads on the search path.
A search running concurrently with a block change can observe a stale or torn view and return a
worse path, and can meet a concurrent-resize exception. That failure is contained — it surfaces as a
failed search, which is discarded and leaves that mob synchronous for a short cooldown rather than
being retried synchronously in place — but path *quality* under live mutation
was never measured. This is why Lithium sits behind an explicit opt-in and not in the default tier.

**Live witness.** With Lithium loaded and `compatibilityTier=AUDITED`, the startup scan reports
`scanned=39, failed=0, deniedFamilies=0` and a real vanilla zombie Walk request dispatches to a
worker and installs (`dispatched=1, installed=1, discarded=0`). The same test proves the result is
not vacuous by re-running the production decision over Lithium's own live configs with the audited
evidence withheld and requiring that it denies Walk. At `compatibilityTier=STRICT` the same
environment reports `deniedFamilies=2`.

### Diagonal Blocks `26.1.0` — audited tier

Shipped as a jar nested inside Diagonal Fences, Walls and Windows. The audited artifact is the
nested `diagonalblocks-fabric-26.1.0.jar`, SHA-256
`df59211601dc83718ec0189a56c9f5569a0654f56a58fbbd644ea462a51b74d6`.

- Config: `diagonalblocks.common.mixins.json`, SHA-256 `8aeca65fac6618bb8d7c266c5b4194af876a963fabd77c55f86c9131abfe6ea8`. No mixin plugin.
- Mixin: `fuzs.diagonalblocks.common.mixin.WalkNodeEvaluatorMixin`, SHA-256 `fb5324c681fac2f33145fc67560f2162059353ab5e010d29405af1119d063381`
- The sibling `accessor.BlockBehaviorAccessor` targets `BlockBehaviour`, which is not a watched class, so it contributes no sensitive claim.

**Shape proof.** The override performs no field write at all. It reads block state only through the
`PathfindingContext` the search already owns, and one static field:
`StarCollisionBlock.PROPERTY_BY_DIRECTION`, which is `static final`, built once in a class
initializer via `Maps.immutableEnumMap`, and never written afterwards.

The reason this needs a mechanical proof is what sits beside that map. `StarCollisionBlock` also
holds `CORNER_SHAPES_CACHE` and `CORNER_SHAPES_BLOCK_CACHE`, plain unsynchronized fastutil maps
mutated lazily on the collision-shape path. Reaching either from a worker would be a genuine data
race. The verification therefore requires that the mixin's entire static-field-read surface is the
immutable map and that it makes no call into `StarCollisionBlock` whatsoever, so a future version
that routed the diagonal check through a shape cache would fail closed instead of inheriting this
finding.

**Live witness.** With Lithium and Diagonal Blocks both loaded at `compatibilityTier=AUDITED`, the
startup scan reports `scanned=45, failed=0, deniedFamilies=0`, and a real vanilla zombie Walk
request dispatches to a worker and installs. Each mod is separately checked for non-vacuity: the
production decision is re-run over that mod's own live configs with the audited evidence withheld,
and must deny Walk.

**Harness note.** The `maven.modrinth:diagonal-fences` coordinate serves a *different* artifact from
the release file — 128015 bytes with no `META-INF/jars` entry, against the 126183-byte release that
actually contains the library. The harness fetches the release file directly and pins its SHA-1, so
a substituted parent cannot stage a library the audit never examined.

## What the structural verifiers actually check

The Lithium and Diagonal Blocks entries above say "bytecode proof". Read that as a bounded
mechanical check, not a whole-program proof, and specifically:

- The Lithium verifier rejects field writes by opcode (`PUTFIELD`/`PUTSTATIC`) in the audited
  classes. It does not model array stores, mutations performed by methods those classes call, or
  transitive effects through helpers. Its initializer-confinement check is scoped to callers within
  the same class, not to a whole-jar caller inventory.
- The Diagonal Blocks verifier permits a small set of helper calls and establishes that the override
  never reaches `StarCollisionBlock`, which owns the unsynchronised shape caches. It assumes
  `PROPERTY_BY_DIRECTION` is immutable from its `static final` declaration rather than proving the
  closure of every call it allows.

Both are pinned to exact artifact bytes, so a different build fails closed rather than inheriting
these findings. That is what makes the bounded check worth something: it cannot silently drift.

## Land path-type providers (generic, no per-mod entry)

Mods that mark a block dangerous are not modifying pathfinding code; they are calling a public
Fabric API, and any mod may do it. Auditing them individually does not scale, so they are handled
by capability rather than by this table.

`StaticPathTypeProvider` receives only a block state and a neighbour flag — no `BlockGetter`, no
`BlockPos` — and its input domain (every state a block can have, times two) is finite. PathWeaver
calls such a provider on the main thread once per input at registration and freezes the answers.
Workers read the frozen table, so third-party provider code never executes off-thread. **This
requires no audit, no artifact hash, and no entry here, and works for mods written after this
release.**

**What that does not establish.** The signature proves what the provider is not *handed*, not that
its answer is stable. Provider code is arbitrary: it may close over a `Level` or any mutable object,
read a singleton, a config value or the clock, and answer differently later — a provider returning
from a captured `AtomicBoolean` would diverge from the frozen table the moment it flipped, and
nothing here would notice. So this is a bounded assumption about how the API is meant to be used, not
a structural proof, and it is honoured at `AUDITED` rather than at the `STRICT` default. Lithium
already caches path types per block state eagerly at startup, so an unstable provider is already
misbehaving on any pack running Lithium; that makes the assumption reasonable, not verified.

`DynamicPathTypeProvider` additionally receives the world and the position, so its answers cannot be
precomputed. Such a registration still denies Walk for the remainder of the process.

### Audited exception: Farmer's Delight's stove

`farmersdelight 26.1-3.6.7+refabricated` (artifact SHA-256 `25adee63…c636`) registers a *dynamic*
provider on its stove, so the rule above would deny Walk for the whole process. Farmer's Delight is
common enough that this alone switched PathWeaver off on a large number of packs — and the denial is
conservative rather than necessary:

```
AbstractStoveBlock.<init> → invokedynamic getPathType()DynamicPathTypeProvider
                            implMethod = AbstractStoveBlock.lambda$new$0
lambda$new$0(BlockState, BlockGetter, BlockPos, boolean)
    → state.getBlock() → (AbstractStoveBlock) → getBlockPathType(state, world, pos, null)
getBlockPathType(BlockState, BlockGetter, BlockPos, Mob)
    0: aload_1                    // the BlockState, and nothing else
    1: getstatic LIT
    4: getValue → booleanValue
   13: ifeq 22 → PathType.FIRE : null
```

The world and position are *received and never loaded* — locals 2 and 3 are absent from the method
body. The answer is a function of the `LIT` property alone, so it is precomputable exactly like a
static provider. `AbstractStoveBlock` is also the only class in the jar that declares
`getBlockPathType` (its one subclass, `StoveBlock`, does not override it) and the jar has no
`META-INF/jars` entries, so no other implementation can be dispatched to.

Generalising this is *not* the same problem as the static case. Certifying an arbitrary dynamic
provider requires proving that the world and position never reach a dereference through an arbitrary
call chain with virtual dispatch — a transitive escape analysis, not a signature check. Two shortcuts
were considered and rejected as unsound: invoking the provider with `null` world and position (a
provider that branches on `world == null` would answer differently under a real world), and checking
only that the implementation method itself never loads those locals (Farmer's Delight fails that
check, because it forwards them to a method that ignores them).

So this one artifact is audited by hand instead, and the audit fails closed on every step:

1. Artifact and `AbstractStoveBlock` pinned by SHA-256.
2. Exactly one `invokedynamic` in that class produces a `DynamicPathTypeProvider`, and its bootstrap
   arguments name `lambda$new$0`. Resolving the implementation from the bootstrap handle is what
   makes the runtime identity check meaningful: a lambda's own class has no readable bytecode, so
   the instance is matched by its host class, which only means something once that class is known to
   contain a single provider lambda.
3. That lambda calls nothing except `BlockState.getBlock` and the decider — it may forward, not
   compute.
4. The decider never references local slot 2 or 3, the `BlockGetter` and the `BlockPos`.
5. No other class in the jar declares the decider, and the jar carries no nested jars. The decider is
   invoked virtually, so a second implementation would be dispatched to instead.
6. At registration, the concrete block's class hierarchy is walked up to the audited host and must
   declare no override — closing the same hole for a subclass added by *another* mod, which the jar
   scan cannot see.

Only then is the provider evaluated over every block state with null world and position — safe
precisely because step 4 proved those arguments dead — and the answers frozen like a static
provider's. Both forms are honoured at `AUDITED` rather than `STRICT`, for different reasons: the
static form rests on a semantic assumption that a provider is a pure function of block state, and
this one rests on reading a single artifact's bytecode.

**The tier is not read at registration time.** Mods register blocks from their own initializer, and
Farmer's Delight's runs before PathWeaver has loaded its config, so a tier read there sees the
fail-closed default and denies whatever the operator actually chose. The audit result is published to
the registry latch instead, and the tier is applied at dispatch. Verified both ways on a dedicated
server outside Loom: with Farmer's Delight loaded, `AUDITED` dispatched 11119 searches where it
previously dispatched 0, and `STRICT` still refused to run.

## Every setting, measured

Single-variable sweep: one option moves per run, everything else stays at the shipped default.
Fabric API + Cloth + Lithium + Farmer's Delight + PathWeaver, `AUDITED`, 1024 zombies in a maze
retargeted every 6 ticks, 300 warm-up and 600 measured ticks. The shipped default was run first and
last as a drift control and came back 51.1 and 50.8 ms, so differences below roughly 1 ms of mean or
4 points of unused work are inside run-to-run noise and are reported as "no measurable effect"
rather than as small wins.

"Unused" means dispatched minus installed **within the capture window**. The harness halts at the
end of the window, so it does not observe stragglers; this is work that did not come back in time,
not proven-discarded work.

| Setting | Value | Mean | p99 | Unused | Verdict |
|---|---|---|---|---|---|
| *(baseline)* | shipped defaults | 51.1 / 50.8 ms | 388 / 385 ms | 30.1 / 26.2% | — |
| `enabled` | `false` | 92.7 ms | 941 ms | — | the A/B: **−45%** when on |
| `maxInFlight` | 64 | 49.5 ms | 425 ms | **1.1%** | **strictly better** |
| `maxInFlight` | 512 | 50.0 ms | 357 ms | **56.4%** | wastes over half the work |
| `stalenessMoveThreshold` | 0.0 | 51.2 ms | 386 ms | **91.8%** | **destroys the feature** |
| `poolThreads` | 2 / 4 / 16 | 50.0 / 65.4 / 52.5 ms | 365 / 533 / 367 ms | 15.8 / 15.6 / 13.1% | no consistent direction |
| `repathElisionEnabled` | `false` | 53.8 ms | 396 ms | 26.3% | no measurable effect here |
| `repathToleranceBlocks` | 0 / 4 | 56.1 / 55.2 ms | 424 / 414 ms | 32.2 / 28.5% | no measurable effect here |
| `maxResultAgeTicks` | 10 / 200 | 61.9 / 59.3 ms | 472 / 478 ms | 19.0 / 20.6% | no measurable effect here |
| `stalenessMoveThreshold` | 64.0 | 53.6 ms | 408 ms | 28.6% | no measurable effect here |

Two settings matter and the rest do not, on this workload.

### `stalenessMoveThreshold` must not be zero

At `0.0`, **91.8% of completed searches were unusable**: a result is rejected if its mob moved at all
since dispatch, and a moving mob always has. The setting exists to reject results that no longer
describe where the mob is; set to zero it rejects everything. Nothing in the config validation stops
this, because zero is a legal distance.

### `allowModdedMobAsync`, measured rather than assumed

This was the last exposed setting still resting on a smoke test. Measured with a mob class defined by
another mod — `aquariusplayz.animalgarden.lion.mob.ModMob` from Animal Garden, 512 of them, same maze
workload, tier `AUDITED` so the flag is isolated rather than implied by `ALL`:

| Setting | Dispatched | Result |
|---|---|---|
| `allowModdedMobAsync=false` | **0** | the origin gate refuses the class outright; the benchmark aborted its own async arm as vacuous |
| `allowModdedMobAsync=true` | 31890 | dispatches normally, 27478 installed |
| vanilla control (`minecraft:zombie`, flag off) | 28161 | 27902 installed |

Exactly the documented behaviour: with the flag off a mod-defined mob class is **entirely**
synchronous — not partly, not occasionally — and with it on it behaves like a vanilla-class mob. The
gate keys on where the class came from, so no amount of load changes that answer.

### Verified on the client as well as the server

Every other measurement here is from a dedicated server. The client runs an integrated server and
loads a different, larger set of mixin configs, and a past release had a client-only over-denial bug,
so the scan is checked there too via the Fabric client game test:

| Tier | Client scan |
|---|---|
| `STRICT` | `scanned=64, failed=0, deniedFamilies=2` — denied, naming the interaction module |
| `AUDITED` | `scanned=64, failed=0, deniedFamilies=0` — gate open |

`failed=0` across 64 configs is the part that matters: the client-only configs parse without a single
fail-closed fallback, and the tier behaves the same way it does on a server.

### The limit only means something relative to the worker count

Every measurement above ran on a 32-core machine. Worker threads are sized automatically at
`cores / 4`, so that is eight workers and the shipped `maxInFlight` of 256 was **32 queued per
worker**. A four-core server gets **one** worker, and the same setting becomes 256 deep on a single
thread — eight times deeper than anything that had been measured.

Measured with the pool forced to one worker:

| `maxInFlight` | Unused work |
|---|---|
| 32 | **0.7%** |
| 64 | 2.9% |
| 256 *(shipped)* | **48–54%** |

At two workers, 256 leaves 13% unused against 1.0% at 64. Two honest caveats: the one-worker drift
control moved 43.7% between identical arms, so tick time is **not** resolvable in that regime and
only the wasted share is; and forcing one worker on a 32-core box leaves the other 31 cores idle, so
real low-core hardware is likely worse rather than better.

So the limit is now capped to the pool's width: the enforced bound is
`min(maxInFlight, workers × 32)`. On the eight-worker machine every measurement here was taken on,
that is `min(256, 256)` and nothing changes. On a single-worker machine it becomes 32, which is the
regime that measured well. The configured value stays the operator's ceiling and the startup log
states both when they differ.

### What `discarded` in the stats line actually counts

Worth stating because it is easy to misread, and I misread it: the `discarded` counter is **not**
dispatched-minus-installed. `PathNavigation.stop()` cancels any in-flight request for that mob and
counts a discard, and vanilla AI calls `stop()` constantly — which is why a pack-scale run reported
`dispatched=80663, installed=70477, discarded=83974`, with more discards than dispatches.

So `discarded` mixes two unrelated things: a result that came back too late to be wanted, and a
request whose mob simply stopped navigating. Only the first says anything about admission. An
adaptive controller driven on `installed / (installed + discarded)` was built and measured against
this workload: it walked its bound down to the floor because roughly 45% of completions were
`stop()`-cancellations at *every* bound, so no window ever looked healthy. It removed the wasted work
(29.5% to 0.9% at the same ceiling) but cost mean tick time — 58.1 ms against 53.7 ms for a fixed 256
and 49.4 ms for a fixed 64 — because it starved the pool chasing a ratio that could not be reached.
It is not shipped. Doing it properly needs the discard counter split by cause first.

### `maxInFlight`: a real trade, and the shipped default is the right side of it

An earlier revision of this section claimed the shipped 256 was too high and that 64 beat it on every
axis. **That was wrong.** It rested on two sessions of a single workload — 1024 mobs, 8 worker
threads — where ambient machine load happened to favour the lower limit on mean tick time, and it
never looked at p99 across loads. Sweeping the limit against load and against worker count says
something different and consistent.

Three loads, auto worker threads:

| Mobs | Setting | Mean | p99 | TPS | Unused |
|---|---|---|---|---|---|
| 512 | synchronous | 50.4 ms | 483 ms | 19.8 | — |
| 512 | `maxInFlight=32` | 50.0 ms | 271 ms | 20.0 | 1.0% |
| 512 | `maxInFlight=64` | 50.0 ms | 261 ms | 20.0 | 1.1% |
| 512 | `maxInFlight=256` | 50.0 ms | **184 ms** | 20.0 | 1.6% |
| 1024 | synchronous | 82.5 ms | 777 ms | 12.1 | — |
| 1024 | `maxInFlight=32` | 62.7 ms | 554 ms | 16.0 | 0.8% |
| 1024 | `maxInFlight=64` | 49.7 ms | 427 ms | 20.0 | 1.0% |
| 1024 | `maxInFlight=256` | 50.1 ms | **373 ms** | 20.0 | 16.0% |
| 1536 | synchronous | 128.3 ms | 1793 ms | 7.8 | — |
| 1536 | `maxInFlight=32` | 81.1 ms | 731 ms | 12.3 | 1.2% |
| 1536 | `maxInFlight=64` | 78.3 ms | 682 ms | 12.8 | 0.9% |
| 1536 | `maxInFlight=256` | 78.4 ms | **634 ms** | 12.8 | 13.1% |

And at three worker counts, 1024 mobs:

| Threads | `maxInFlight` | Mean | p99 | Unused |
|---|---|---|---|---|
| 4 | 64 / 256 | 50.0 / 50.1 ms | 412 / **340** ms | 1.0 / 16.0% |
| 8 | 64 / 256 | 49.7 / 50.1 ms | 427 / **373** ms | 1.0 / 16.0% |
| 16 | 64 / 256 | 54.2 / 54.0 ms | 443 / **384** ms | 1.0 / 15.2% |

**The same shape at every load and every worker count.** Mean tick time is indistinguishable — the
drift control moved 7.8% between the first and last identical arm, so nothing smaller than that is
resolvable. What separates the limits is a genuine trade:

- **256 gives consistently better p99**, by 8% to 30% depending on load.
- **64 leaves almost no work unused**, 1% against 13–16%.

Both directions of that are explained by the same mechanism. A request refused at admission does not
disappear; it runs synchronously on the main thread. A smaller bound fills more often, so more
searches fall back onto the tick — which is exactly where the spikes come from. A larger bound
absorbs more of them, and pays for it by starting work that is sometimes no longer wanted when it
lands. The wasted searches are the price of the tail latency, spent on worker threads rather than on
the tick.

Since the entire point of this mod is spike reduction rather than average throughput, **p99 is the
metric that decides, and the shipped default of 256 is on the right side of the trade.** It stays.

Below about 64 the trade stops paying: at 1024 mobs `maxInFlight=32` gives up both, at 62.7 ms mean
and a worse p99 than either larger limit, because so much is being refused onto the main thread.

Lower it only if worker CPU is itself scarce and you would rather spend tick time than cores.

## Shipped-artifact verification, outside Loom

Every gametest runs inside Loom's dev classpath. That has hidden a production-only failure before —
`fabric-events-interaction-v0` hashes differently when nested inside Fabric API than when resolved
standalone, so the audit passed in dev and denied in production. The released jar is therefore also
booted on a plain dedicated server built from release artifacts only.

Fabric API `0.153.0+26.1.2`, Cloth Config `26.1.154`, Lithium `0.24.6+mc26.1.2`, and
`pathweaver-0.3.0+26.1.2` (SHA-256 `9baa9f7d…4314`), on JDK 25. 1024-mob maze load,
`maxInFlight=256`.

| Mods added | Tier | Scan result | Dispatched |
|---|---|---|---|
| Farmer's Delight | `AUDITED` | scanned=36, failed=0, denied=0 | **11119** — stove provider certified by the exact audit |
| Farmer's Delight | `ALL` | scanned=36, failed=0, denied=0 | 11133 |
| Farmer's Delight + FerriteCore | `AUDITED` | scanned=41, failed=0, **denied=2** | **0** — `WalkNodeEvaluator`, `SwimNodeEvaluator` |
| Farmer's Delight + FerriteCore | `ALL` | scanned=41, failed=0, denied=0 (waived, logged `WARN`) | 11132 |

The same runs also pin the mob-origin gate's tier coupling, which is otherwise easy to get silently
wrong — a tier that reports "nothing is being checked" while most of a modded pack's mobs stay
synchronous:

| Tier | `moddedBypass` | Logged boundary |
|---|---|---|
| `AUDITED` | `false` | `mod-defined mobs are synchronous by the origin gate` |
| `ALL` | `true` | `mod-defined mobs are allowed by compatibilityTier=ALL (unsafe)` |

The two `AUDITED` rows are the two distinct ways the gate closes, and they close for different
reasons — the provider latch and a mixin denial. The final row is the one that matters for `ALL`:
the scan *did* find a real denial, `ALL` waived it, said so at `WARN`, and dispatched anyway. An
`ALL` run against a pack with nothing to waive would not have tested that path.

The benchmark harness refuses to report an async arm that dispatched nothing
(`async arm dispatched no work; the measurement would be vacuous`) or one whose gate was closed at
startup, so a silently-synchronous run cannot be mistaken for a win. Both `AUDITED` rows above were
rejected by that check rather than reported as results.

### At modpack scale

The four-mod environment above isolates the gate; it does not show what happens when 300+ mods are
transforming the same classes. So the same release jar was also booted on a **371-mod load — 222
jars plus their nested libraries — a server-side derivative of a real 26.1.2 pack**, with that pack's
own `config/` directory and its own `pathweaver.json`.

```
Loading 371 mods
Foreign-mixin scan complete: scanned=331, failed=0, deniedFamilies=0    (compatibilityTier=ALL)
PathWeaver stats: dispatched=10975, installed=10489, discarded=708
```

`failed=0` across 331 scanned mixin configs is the load-bearing part: the scanner parsed every
config in a pack of that size without a single fail-closed fallback.

Provenance note: the scan census and the mod count come from the retained server logs
(`.minecraft-dev/pathweaver-pack/boot-pack.log` and `run-k*.log`), not from the benchmark JSON,
which records only timing and dispatch counters. The same applies to the "two builds" claim in the
four-mod table: the JSON carries no artifact hash, so which jar produced which result is established
by the run logs and the commit history rather than by the result files themselves.

**What the same pack denies at `AUDITED`** — read from the live scan, not from a corpus guess, which
matters because a static estimate over the same pack counted nearly twice as many:

| | |
|---|---|
| Denied families | `WalkNodeEvaluator`, `SwimNodeEvaluator` |
| Distinct denying mods (9) | `balm`, `carpet`, `expandability`, `ferritecore`, `scalablelux`, `sereneseasons`, `terrain_slabs`, `vehicleupgrade`, `yungscavebiomes` |
| Audited and exempt | `servercore`, `rabbit-pathfinding-fix`, `fabric-events-interaction-v0`, `lithium`, `diagonalblocks` |

Most of these mix into `BlockStateBase`, and most call into their own code from the injected method,
which is why the audited tier cannot clear them: proving they perform no shared-state write on the
search path would require a transitive analysis through mod code, not a signature check.

**A version-pinning demonstration fell out of this run.** The first attempt denied Lithium too:

```
Foreign-mixin scan failure (fail-closed): Lithium exact audit: unsupported version 0.24.5+mc26.1.2
```

The derivative carried Lithium `0.24.5`; the audit pins `0.24.6`. An unpinned build denies rather
than reusing an audit written against different bytes, exactly as intended. Aligning the version made
the audit verify and dropped Lithium from the denied list.

Residual assumption: a static provider that closes over mutable state and changes its answer after
registration would leave the frozen table stale. Lithium already caches path types per block state
eagerly at startup, so such a provider is already misbehaving on any pack running Lithium — but this
is an assumption, not a proof.
