# The audits on 26.2: what was wrong, and what fixed it

Measured 2026-08-31. An earlier version of this document described the broken state and got several
things wrong; a reviewer re-ran every claim and refuted nine of them. Those corrections are kept at
the bottom rather than edited away, because a findings document that hides its own corrections is
worth less than one that shows them.

**Status: fixed.** At `compatibilityTier=AUDITED` on 26.2 the mod now certifies all four audited
artifacts, and every harness passes on both branches.

## What was wrong

At `AUDITED`, PathWeaver on 26.2 did nothing at all. All six evaluator families ran on the server
thread and the mod said so at world start. Every audit pinned 26.1.2 artifacts, and 26.2 resolves
different ones, so all four refused on the version gate before any bytes were read.

This was never a 0.8.0 regression. The pins last changed on 2026-07-26, before the port branch's
first commit on 2026-08-19, so they had been stale for the branch's entire life, and 0.6.1+26.2 was
published in that state.

## What each audit needed

| Audit | 26.1.2 pin | 26.2 resolves | What it needed |
|---|---|---|---|
| `servercore` | `1.5.19+26.1.2` | `1.5.19+26.2` | new hashes; shape proof already passed |
| `fabric-content-registries-v0` | `11.2.1+76b0b6bb4c` | `11.3.1+37b1aa249e` | new hashes |
| `fabric-events-interaction-v0` | `5.2.2+07b380be4c` | `5.2.7+515ac5339e` | new hashes |
| `rabbit-pathfinding-fix` | `1.3.0` | `1.4.0` | a new proof: it changed mechanism |

Three were pure hash drift. Their shape proofs passed on the new bytes without any change, which is
what made them mechanically re-derivable rather than a fresh audit.

Byte facts, independently reproduced. Vanilla `PathFinder` is identical across 26.1.2 and 26.2 and
matches its pinned hash, which is the positive control. `PathNavigation`, `BlockStateBase`,
`WalkNodeEvaluator` and `Frog$FrogNodeEvaluator` all differ, which is the negative control.
ServerCore's two mixin configs and its plugin are byte-identical between the two builds; only
`PathFinderMixin` differs, and its 26.2 form still carries exactly three `@Redirect` handlers on the
pinned `findPath` descriptor with the three pinned INVOKE targets.

The Fabric interaction module needed only one module hash here. The dev environment resolves the same
bytes that ship nested inside `fabric-api-0.157.0+26.2`, confirmed by extracting the nested jar and
hashing it, so the two packaging forms the 26.1.2 branch distinguishes are one hash on 26.2.

## Rabbit 1.4.0 changed mechanism, and the audit could not see half of it

1.3.0 used `@Inject(method = "resetStuckTimeout", at = @At("TAIL"))`. 1.4.0 replaced it with
`@ModifyConstant(method = "resetStuckTimeout", constant = @Constant(doubleValue = 0.0))` on a
`double modifyTimeout(double)`, keeping the `@Inject` into `doStuckDetection`. It still modifies two
vanilla methods, by two different mechanisms.

The enumerator recognised three annotations, `@Mixin`, `@Redirect` and `@Inject`, and **silently
skipped** every method carrying anything else. It counted one modified method, expected two, and
failed closed. The guard worked, but the reason it refused was not the reason it reported, and the
hole underneath was worse than the symptom:

> An artifact with exactly the pinned handlers **plus** an extra `@ModifyConstant`,
> `@ModifyVariable`, `@Overwrite` or MixinExtras injector satisfied the pinned count, because the
> extra modification never entered the count meant to notice it.

The SHA-256 pin on the mixin class contained that for the artifacts pinned at the time. It would have
bitten whoever next re-pinned, which was this work: a green shape proof that under-reports the
modification surface makes the audit's claim false while leaving it green.

Both fixed. The enumerators now report any injection annotation they do not enumerate, matching what
the two Fabric audits already did, and the rabbit audit understands `@ModifyConstant` as a
modification. The safety argument does not change with the mechanism: whatever the mixin modifies
must be unreachable from the worker's search closure, and that is proved against the bytes either
way.

## The defect the re-pin exposed

`ForeignMixinScanner` kept its own literal copy of the content-registries id, version and config name
beside the audit that pins the same artifact. Moving the pin and not the copy made the Swim claim
shape stop matching, so two claims fell through to the `AuditKey` path, and because both of their
targets are shared pathfinding targets the scan denied all six families on a branch that declares the
artifact audited. It failed closed, and it was still wrong.

On 26.1.2 the two spellings coincide, so nothing there ever caught it: a list you have to remember to
update, with the failure deferred to whoever updates one copy. All three are derived from the audit
now, and a mutation reintroducing a drifted copy turns two tests red.

## Result

| Harness | Tier | 26.1.2 | 26.2 |
|---|---|---|---|
| default | AUDITED | 2 passed | 2 passed |
| `-PunsafeTierHarness` | UNSAFE (shipped default) | 5 passed | 5 passed |
| `-PrefusedHarness` | AUDITED | 2 passed | 2 passed |
| `-PbreakerHarness` | AUDITED | 2 passed | 2 passed |
| `-PauditedRoutingHarness` | AUDITED | 2 passed | 2 passed |
| `-PnewFamilyHarness` | AUDITED | 2 passed | 2 passed |
| `-PfabricAggregateHarness` | AUDITED | 2 passed | 2 passed |
| `-PauditedTierHarness` | AUDITED | 2 passed | **1 of 2 FAILS** |
| unit suite | n/a | 428 passed | 428 passed |

## `-PauditedTierHarness` fails on 26.2, and this table used not to say so

The five harnesses above the line were the whole matrix. `auditedTierHarness` was not in it, so its
result on 26.2 was not unknown, it was unasked. Running every harness rather than the recorded five
is what surfaced it, on 2026-09-06.

The cause is the same shape as the defect 0.8.0 fixed and it was not fixed for these two mods. The
26.2 branch resolves Lithium `0.25.3+mc26.2` and Diagonal Blocks `26.2.0`; the audits pin Lithium
`0.24.6+mc26.1.2` and a 26.1.2 Diagonal Blocks artifact. Both refuse on the version gate:

```
Foreign-mixin scan failure (fail-closed): Lithium exact audit: unsupported version 0.25.3+mc26.2
Foreign-mixin scan failure (fail-closed): Diagonal Blocks exact audit: unsupported version 26.2.0
```

so `AUDITED` denies all six movement families and the harness's dispatch assertion fails.

**It predates the 0.8.0 work on this branch.** Verified rather than assumed: the identical failure
reproduces at commit `2dc6849`, the last mc-26.2 commit before the route cache, the config migration
and the default changes landed.

**What it means for a user.** On 26.2, `compatibilityTier=AUDITED` does nothing on any pack
containing Lithium or Diagonal Blocks, which is most performance packs. The shipped default is
`UNSAFE`, so a default install is unaffected, and the world-start report already says so out loud in
the log. It is a real limitation of the stricter tier on that version and the release notes should
say it rather than let the 26.1.2 result stand in for both.

**Fixing it** means re-deriving the Lithium and Diagonal Blocks audits against their 26.2 artifacts,
which is the work 0.8.0 did for `servercore` and `rabbit-pathfinding-fix`: exact hashes plus a
bytecode shape proof, not a version bump. That is its own piece of work and is not attempted here.

`default` and `auditedRouting` used to fail on 26.2. At runtime the scan now emits no audit refusals
at all, and logs live evidence for the content registry, the land-registry lifecycle and the audited
tuple.

Dispatch counters are timing-dependent and should not be quoted as properties: four unsafe-tier runs
on 26.2 gave 5, 5, 6, 5. The stable facts are the pass counts.

## Corrections to the first version of this document

Each was stated with more confidence than the evidence carried.

1. **`fabric-events-interaction-v0` was listed as `11.3.1+37b1aa249e`.** That is the
   content-registries version, in the wrong row. It resolves `5.2.7+515ac5339e`.
2. **"Nothing here is inferred from version numbers"** — that row was not measured.
3. **"The shape proof re-runs mechanically against whatever bytes are present"** — not at runtime.
   The version gate short-circuits before any bytes are read.
4. **The quoted rabbit diagnostic** came from calling the verifier directly, not from the running
   mod, which refuses on version.
5. **"Nothing dispatches because every family is denied"** — the routing test clears the denials
   before the failing assertion. The land-registry latch was the blocker.
6. **"Re-pinning the three clean audits would not help"** — wrong, and this is the one that mattered.
   Re-pinning content-registries is exactly what reopened the latch.
7. **"Both 26.2 failures are this one cause"** — two causes, neither of them rabbit.
8. **`dispatched=7, installed=1, discarded=3`** was one run's timing quoted as a property.
9. **The enumerator gap was described as a `@ModifyConstant` blind spot.** It was every non-`@Inject`
   annotation in one enumerator and every non-`@Redirect` in the other.

## Re-pinning Lithium for 26.2: evidence so far

Work in progress for 0.9. Recorded as it is gathered so the next person does not repeat it.

**The structural proof holds.** Re-run against Lithium `0.25.3+mc26.2`, which is what the 26.2 branch
resolves, using `tools/audit_field_writes.py`. Every field write is in `<init>`, `<clinit>`,
`lithium$initializePathNodeTypeCache` or `lithium$initializeFlags`. `WalkNodeEvaluatorMixin`,
`FlyNodeEvaluatorMixin`, `PathfindingContextMixin`, `PathfindingContextAccessor`, the chunk-access
`PathNavigationRegionMixin` and the inactive-navigations `PathNavigationMixin` write nothing at all.
Zero violations.

**Eight of the audited classes did not change at all.** Comparing the pinned 26.1.2 hashes against
the 26.2 artifact, nine of twelve are byte-identical, and all eight audited mixin classes are among
them. The proof's subject matter is literally the same bytes.

| pin | 26.1.2 | 26.2 |
|---|---|---|
| the eight audited mixin classes | | identical |
| `lithium-fabric.mixins.json` | | identical |
| `lithium.mixins.json` | `f9674d7b9bb5` | `14ed3a630a22` |
| the module jar | `509e7f770c7d` | `fdde92e238e8` |
| `LithiumMixinPlugin` | `b97aed37b9ed` | `795f0a10e2cb` |

**The mixin config declares the same set.** 286 entries both sides, 21 of them pathfinding-relevant,
none added and none removed. The whole diff is a `conformVisibility` overwrite option and one
unrelated sensor mixin renamed from `parent_animal_sensor` to `baby_specific_sensors`. Neither
touches an audited class.

**Still open before the pin can be written.** `LithiumMixinPlugin` changed bytes, and it is pinned
precisely because a plugin decides which mixins actually apply. The declared set being identical and
the audited classes being byte-identical bound the risk to one thing: whether the new plugin switches
ON a pathfinding mixin the old one left off. That has to be read out of the plugin rather than
assumed, and it is the next step.

**A design question the evidence raises.** The audit refuses on `MOD_VERSION` before it ever looks at
a byte. Since the proof's subject matter is byte-identical across these two builds, a version label
is the only thing refusing, which is the same defect 0.7.0 fixed for 26.1.1: gate on the bytes the
proof pinned, not on a label. The pin should probably carry a set of known artifact fingerprints
rather than one version string.

