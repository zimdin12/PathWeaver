# PathWeaver compatibility matrix

This table is version-exact. A verdict applies only to the listed artifact on Minecraft **26.1.2**. PathWeaver fingerprints audited artifacts and relevant vanilla classes at startup; a changed version, byte, mixin selector, target descriptor, plugin contribution, missing class, or partial bundle fails closed to synchronous pathing.

`SAFE` means the exact audited worker-reachable behavior uses parameters, immutable data, or per-search state and performs no unsafe shared mutation or unbounded callback. `STALE-PATH RISK ONLY` means no worker shared-state mutation was found, but concurrent live reads can select a stale/wrong path and may expose the existing palette/storage lookup-exception envelope. `DENIED` means the default proven-safe tier keeps the affected evaluator family synchronous.

The verdict interacts with the `compatibilityTier` setting. `STRICT` (the default) honours only `SAFE` rows. `AUDITED` additionally honours `STALE-PATH RISK ONLY` rows, which is the setting a Lithium pack needs. `ALL` ignores this table entirely and is not covered by any evidence here.

| Mod | Exact version tested | What it modifies | Worker can reach it? | Verdict | Evidence hash (artifact SHA-256) |
|---|---|---|---|---|---|
| ServerCore | `1.5.19+26.1.2` | Three redirects in `PathFinder.findPath(PathNavigationRegion, Mob, Set, float, int, float)` replacing stream/map construction with a local map and per-search evaluator scratch | Yes | **SAFE** — exact exemption implemented | `593941ef360ba493b180c213bbb093d95223dba4a34d97e7559b914847363aa4` |
| rabbit-pathfinding-fix | `1.3.0` | `PathNavigation.doStuckDetection(Vec3)` and `resetStuckTimeout()` navigation-maintenance injections | No in the pinned submitted search closure; a live worker-marker probe observed zero entries | **SAFE** — exact non-reachability exemption implemented | `6388f7a83b303c7de485f5f0089bd7e887ea45f9adf6bc9b099cad932fa58851` |
| Fabric content registries | `11.2.1+76b0b6bb4c` | Land path-type hooks in `PathfindingContext`/Walk plus the structural `BlockStateBase` refresher | Yes for Walk; exact Swim route is structurally independent | **SAFE only while sealed empty** — exact lifecycle hooks publish a process-lifetime registration latch before provider mutation, worker lookup returns before the live registry map, dispatch denies after publication, and an exact in-flight Walk result is discarded if registration wins before install. | `d1c8a0a2753850ec422f9c03824a0475a24f1d27bbbf1227d9f9d952406bebd1` |
| Fabric events interaction | `5.2.2+07b380be4c` | Two cancellable `HEAD` injections into the exact `BlockStateBase.useItemOn` and `useWithoutItem` descriptors | No from the pinned MC 26.1.2 worker search call surface | **SAFE** — exact negative-reachability exemption implemented; identity, ownership, selector, injector count, plugin absence, and aggregate claims fail closed. | `dc4a15c9250c6d0e5839e5b696792b06869c65f1ab7e71627986d8f9ed247d60` |
| Farmer's Delight Refabricated | `26.1-3.6.7+refabricated` | Registers a bounded land path-type provider used by Fabric's pathfinding hook | Yes when its provider is registered | **DENIED** pending exact closed provider-set and lifecycle certification | `25adee6361b37f1e559373bf6aedc90fa62b2da8ab084e3dee53f037ffcac636` |
| Spiky Spikes | `26.1.0` | Registers a fixed-enum land path-type provider used by Fabric's pathfinding hook | Yes when its provider is registered | **DENIED** pending exact closed provider-set and lifecycle certification | `323d974770988a17c6d054dc879b528c5e5c25ac48241748316bb6aa679eee04` |
| Carpet | `26.1+v260402` | Navigation `createPath` overload/deferred-return behavior and a piston transformation | Not from the audited worker search entry | **DENIED** pending a live logging/deferred-return semantic witness; it will be dropped if that witness is ambiguous | `59bd225d12423a7d7a635ca0c94fa786f97ccebb116922b16d76072da4ee67e7` |
| Lithium | `0.24.6+mc26.1.2` | Region/block-state path-type shortcuts, block-state flag cache, chunk-access region lookup, and inactive-navigation listener bookkeeping | Yes, except the navigation hook | **STALE-PATH RISK ONLY** — bytecode proof that no worker-reachable method writes shared state; honoured at `compatibilityTier=AUDITED`, denied at the `STRICT` default | `509e7f770c7d48bd37e9592917329db2768e4695c72a43e22c19ef64d0f9839f` |
| Diagonal Blocks | `26.1.0` | Extra `WalkNodeEvaluator` diagonal-validity reads | Yes | **DENIED** — believed equivalent in risk to Lithium, but not yet given the same bytecode proof, so it is not in the audited tier | `df59211601dc83718ec0189a56c9f5569a0654f56a58fbbd644ea462a51b74d6` |

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
failed search that falls back to synchronous pathfinding — but path *quality* under live mutation
was never measured. This is why Lithium sits behind an explicit opt-in and not in the default tier.

**Live witness.** With Lithium loaded and `compatibilityTier=AUDITED`, the startup scan reports
`scanned=39, failed=0, deniedFamilies=0` and a real vanilla zombie Walk request dispatches to a
worker and installs (`dispatched=1, installed=1, discarded=0`). The same test proves the result is
not vacuous by re-running the production decision over Lithium's own live configs with the audited
evidence withheld and requiring that it denies Walk. At `compatibilityTier=STRICT` the same
environment reports `deniedFamilies=2`.
