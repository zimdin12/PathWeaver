# PathWeaver compatibility matrix

This table is version-exact. A verdict applies only to the listed artifact on Minecraft **26.1.2**. PathWeaver fingerprints audited artifacts and relevant vanilla classes at startup; a changed version, byte, mixin selector, target descriptor, plugin contribution, missing class, or partial bundle fails closed to synchronous pathing.

`SAFE` means the exact audited worker-reachable behavior uses parameters, immutable data, or per-search state and performs no unsafe shared mutation or unbounded callback. `STALE-PATH RISK ONLY` means no worker shared-state mutation was found, but concurrent live reads can select a stale/wrong path and may expose the existing palette/storage lookup-exception envelope. `DENIED` means the default proven-safe tier keeps the affected evaluator family synchronous.

| Mod | Exact version tested | What it modifies | Worker can reach it? | Verdict | Evidence hash (artifact SHA-256) |
|---|---|---|---|---|---|
| ServerCore | `1.5.19+26.1.2` | Three redirects in `PathFinder.findPath(PathNavigationRegion, Mob, Set, float, int, float)` replacing stream/map construction with a local map and per-search evaluator scratch | Yes | **SAFE** — exact exemption implemented | `593941ef360ba493b180c213bbb093d95223dba4a34d97e7559b914847363aa4` |
| rabbit-pathfinding-fix | `1.3.0` | `PathNavigation.doStuckDetection(Vec3)` and `resetStuckTimeout()` navigation-maintenance injections | No in the pinned submitted search closure; a live worker-marker probe observed zero entries | **SAFE** — exact non-reachability exemption implemented | `6388f7a83b303c7de485f5f0089bd7e887ea45f9adf6bc9b099cad932fa58851` |
| Fabric content registries | `11.2.1+76b0b6bb4c` | Land path-type lookup hook in `PathfindingContext`, Walk evaluator hook, and structural `BlockStateBase` refresher | Walk: yes; exact Swim route: no | **DENIED** pending the monotonic sealed-empty/provider lifecycle proof. Full Fabric API also has an independent interaction mixin, so aggregate FAPI remains denied. | `d1c8a0a2753850ec422f9c03824a0475a24f1d27bbbf1227d9f9d952406bebd1` |
| Farmer's Delight Refabricated | `26.1-3.6.7+refabricated` | Registers a bounded land path-type provider used by Fabric's pathfinding hook | Yes when its provider is registered | **DENIED** pending exact closed provider-set and lifecycle certification | `25adee6361b37f1e559373bf6aedc90fa62b2da8ab084e3dee53f037ffcac636` |
| Spiky Spikes | `26.1.0` | Registers a fixed-enum land path-type provider used by Fabric's pathfinding hook | Yes when its provider is registered | **DENIED** pending exact closed provider-set and lifecycle certification | `323d974770988a17c6d054dc879b528c5e5c25ac48241748316bb6aa679eee04` |
| Carpet | `26.1+v260402` | Navigation `createPath` overload/deferred-return behavior and a piston transformation | Not from the audited worker search entry | **DENIED** pending a live logging/deferred-return semantic witness; it will be dropped if that witness is ambiguous | `59bd225d12423a7d7a635ca0c94fa786f97ccebb116922b16d76072da4ee67e7` |
| Lithium | `0.24.6+mc26.1.2` | Region/block-state path-type shortcuts and live section/palette reads | Yes | **STALE-PATH RISK ONLY** — no worker shared-state write; default tier still denies until explicit risk tiers ship | `509e7f770c7d48bd37e9592917329db2768e4695c72a43e22c19ef64d0f9839f` |
| Diagonal Blocks | `26.1.0` | Extra `WalkNodeEvaluator` diagonal-validity reads | Yes | **STALE-PATH RISK ONLY** — no worker shared-state write; default tier still denies until explicit risk tiers ship | `df59211601dc83718ec0189a56c9f5569a0654f56a58fbbd644ea462a51b74d6` |

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

## Verification scope

Milestone 1 was exercised by a registered, sequential phase in a real Fabric GameTest JVM with both exact mods loaded. Startup verified both runtime tuples, including the prepared ServerCore plugin class name and loaded class-byte hash. The isolated exact decision produced real worker registration, dispatch, and install. Version-near-miss decisions for each mod denied both eligible evaluator families and produced synchronous paths with zero new async dispatches. All three registered GameTests passed; the run ended at `dispatched=11`, `installed=8`, `discarded=3`, and the Rabbit worker-marker probe remained zero. This witness proves the exact loaded tuple and near-miss behavior; it does not certify future versions.
