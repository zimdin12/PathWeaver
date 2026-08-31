# Why the audits refuse on 26.2, and what it costs

Measured 2026-08-31 on the `mc-26.2` branch. An earlier version of this document was checked by a
reviewer who re-ran every claim on their own instrument, and several did not survive. What follows is
the corrected version. The refutations are recorded at the bottom rather than quietly edited out,
because a findings document that hides its own corrections is worth less than one that shows them.

## The short version

At `compatibilityTier=AUDITED`, PathWeaver on 26.2 does **nothing at all**. All six evaluator families
run on the server thread, exactly as vanilla, and the mod says so loudly at world start:

```
Foreign-mixin scan complete: scanned=38, failed=0, deniedFamilies=6.
PathWeaver is doing NOTHING on this pack. All 6 movement
families are running on the server thread, exactly as vanilla.
```

At the shipped default, `compatibilityTier=UNSAFE`, PathWeaver on 26.2 works: the unsafe-tier harness
passes 5/5, four runs for four.

This costs only the operators who deliberately opted into the stricter tier. It is not a 0.8.0
regression. The pins last changed on 2026-07-26, before the port branch first commit on 2026-08-19,
so they have been stale for the whole life of the branch.

## What each audit says on 26.2

| Audit | Resolved on 26.2 | Pinned | Verdict |
|---|---|---|---|
| `servercore` | `1.5.19+26.2` | `1.5.19+26.1.2` | refuses on version; shape proof would pass |
| `fabric-content-registries-v0` | `11.3.1+37b1aa249e` | `11.2.1+76b0b6bb4c` | refuses on version |
| `fabric-events-interaction-v0` | `5.2.7+515ac5339e` | `5.2.2+07b380be4c` | refuses on version |
| `rabbit-pathfinding-fix` | `1.4.0` | `1.3.0` | refuses on version; **shape genuinely changed** |

**The runtime refuses on the version string, before reading any bytes.** `inspectRuntime` gates on the
mod version and returns early, so no shape proof executes in production at all:

```
Foreign-mixin scan failure (fail-closed): servercore exact audit: unsupported version 1.5.19+26.2
Foreign-mixin scan failure (fail-closed): rabbit-pathfinding-fix exact audit: unsupported version 1.4.0
```

The shape results below come from calling the verifiers directly, which is a different question from
what the running mod does. Re-pinning therefore means bumping the version constants as well as the
hashes; the hashes alone would never be reached.

Byte facts, independently reproduced. Vanilla `PathFinder` is identical on 26.1.2 and 26.2 and matches
its pinned hash, which is the positive control. `PathNavigation`, `BlockStateBase`, `WalkNodeEvaluator`
and `Frog$FrogNodeEvaluator` all differ, which is the negative control. ServerCore two mixin configs
and its mixin plugin are byte-identical across the two builds; only `PathFinderMixin` differs, and its
26.2 form still carries exactly three `@Redirect` handlers on the pinned `findPath` descriptor with the
three pinned INVOKE targets.

## Rabbit 1.4.0 changed mechanism, and the audit could not see half of it

1.3.0 used `@Inject(method = "resetStuckTimeout", at = @At("TAIL"))`. 1.4.0 replaced it with
`@ModifyConstant(method = "resetStuckTimeout", constant = @Constant(doubleValue = 0.0))` on a
`double modifyTimeout(double)`, keeping the `@Inject` into `doStuckDetection`. It still modifies two
vanilla methods, by two different mechanisms.

The enumerator recognised exactly three annotations, `@Mixin`, `@Redirect` for ServerCore and `@Inject`
for rabbit, and **silently skipped** every method carrying anything else. It counted one modified
method, expected two, and failed closed. The guard worked. But the reason it refused was not the reason
it reported, and the underlying hole was worse than the symptom:

> An artifact with exactly the pinned handlers **plus** an extra `@ModifyConstant`, `@ModifyVariable`,
> `@Overwrite` or MixinExtras injector satisfied the pinned count, because the extra modification never
> entered the count meant to notice it.

The SHA-256 pin on the mixin class contained that for the artifacts pinned today. It would have bitten
whoever next re-pinned: a green shape proof that under-reports the modification surface makes the audit
claim false while leaving it green. **Fixed** — both enumerators now diagnose any injection annotation
they do not enumerate, which is what the two Fabric audits already did. A test synthesising the exact
1.4.0 shape is mutation-verified against the old silent skip.

Note also that `modifiedMethods()` is never read by the production decision; validity is
`diagnostics.isEmpty()`. The load-bearing guard was the count inside the enumerator, not the set.

## Why the two 26.2 harness failures happen

They have **two different causes**, and neither is rabbit.

- **default (AUDITED)** — `coordinate move must dispatch one async request`. The routing test asserts
  all six families are denied and then *clears* the denials before reaching this assertion, so the scan
  denials are not what blocks it. The blocker is `FabricLandPathRegistryLatch`: the content-registries
  audit refuses on version, so `hooksVerified` stays false, `allowsWalk()` returns false, and
  `SafetyGate.canDispatch` denies because AUDITED does not bypass the scan.
- **auditedRouting** — `live evidence must contain the exact ServerCore audit key`. That one is a
  genuine audit-refusal failure, and it is the ServerCore audit.

**A partial re-pin might well fix the default harness.** Re-pinning `fabric-content-registries-v0`
alone would reopen the land-registry latch. That is the strongest open question here, and it has not
been tested, because testing it means editing pins.

## Harness matrix, both branches, 2026-08-31

| Harness | Tier | 26.1.2 | 26.2 |
|---|---|---|---|
| default | AUDITED | 2 passed | **1 failed** |
| `-PunsafeTierHarness` | UNSAFE (shipped default) | 5 passed | 5 passed |
| `-PrefusedHarness` | AUDITED | 2 passed | 2 passed |
| `-PbreakerHarness` | AUDITED | 2 passed | 2 passed |
| `-PauditedRoutingHarness` | AUDITED | 2 passed | **1 failed** |
| unit suite | n/a | 401 passed | 401 passed |

`refused` and `breaker` pass on 26.2 precisely because they assert refusal, which is what 26.2 does.

The unsafe-tier dispatch counters are timing-dependent and should not be quoted as a property: four
runs on 26.2 gave `dispatched` of 5, 5, 6, 5 with `installed` 1 every time. The stable fact is 5/5
passing.

## Corrections to the first version of this document

Recorded rather than removed, because each was a claim stated with more confidence than the evidence
carried.

1. **`fabric-events-interaction-v0` was listed as `11.3.1+37b1aa249e`.** That is the content-registries
   version, duplicated into the wrong row. It resolves `5.2.7+515ac5339e`.
2. **"Nothing here is inferred from version numbers"** — that row was not measured.
3. **"The shape proof re-runs mechanically against whatever bytes are present"** — not at runtime. The
   version gate short-circuits before any bytes are read.
4. **The quoted rabbit diagnostic** came from calling the verifier directly, not from the running mod,
   which refuses on version.
5. **"Nothing dispatches because every family is denied"** — the routing test clears the denials before
   the failing assertion. The land-registry latch is the blocker.
6. **"Re-pinning the three clean audits would not help"** — not established, and probably wrong for the
   default harness.
7. **"Both 26.2 failures are this one cause"** — two causes, neither of them rabbit.
8. **`dispatched=7, installed=1, discarded=3`** was one run timing, quoted as a property.
9. **The enumerator gap was described as a `@ModifyConstant` blind spot.** It was every non-`@Inject`
   annotation in one enumerator and every non-`@Redirect` in the other, and rabbit 1.4.0 is a real
   upstream mechanism change, not only something the audit could not see.
