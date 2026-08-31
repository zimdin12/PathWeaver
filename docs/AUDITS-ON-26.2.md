# Why the audits refuse on 26.2, and what it costs

Measured 2026-08-31 on the `mc-26.2` branch, from the audits' own diagnostics and from `javap` on the
resolved artifacts. Nothing here is inferred from version numbers.

## The short version

At `compatibilityTier=AUDITED`, PathWeaver on 26.2 does **nothing at all**. All six evaluator
families run on the server thread, exactly as vanilla, and the mod says so loudly at world start.

At the shipped default, `compatibilityTier=UNSAFE`, PathWeaver on 26.2 works: the unsafe-tier harness
passes with `dispatched=7, installed=1, discarded=3`.

So this costs the operators who deliberately opted into the stricter tier, on the port branch. It is
not a 0.8.0 regression. It has been true since the branch was created, and 0.6.1+26.2 was published
this way.

## What each audit actually says on 26.2

Every audit pins one exact third-party artifact by SHA-256 and proves a shape against its bytes. On
26.2 the resolved artifacts differ. The interesting question is not *whether* the hashes drifted, it
is whether the **shape proof** still holds, because the shape proof is the safety argument and it
re-runs mechanically against whatever bytes are present.

| Audit | Resolved on 26.2 | Hash drift | Shape proof |
|---|---|---|---|
| `servercore` | `1.5.19+26.2` | jar, `PathFinderMixin` | **passes** — still exactly three `findPath` redirects |
| `fabric-events-interaction-v0` | `11.3.1+37b1aa249e` | jar, 3 vanilla classes | **passes** |
| `fabric-content-registries-v0` | `11.3.1+37b1aa249e` | jar, config, 1 vanilla class | **passes** |
| `rabbit-pathfinding-fix` | `1.4.0` | jar, mixin, vanilla `PathNavigation` | **fails** |

ServerCore's two mixin configs and its mixin plugin are byte-identical to the 26.1.2 artifacts.
Vanilla `PathFinder` is byte-identical on 26.2; `PathNavigation`, `BlockStateBase`,
`WalkNodeEvaluator` and `Frog$FrogNodeEvaluator` are not.

## The rabbit failure is the guard working

The audit refused with `rabbit navigation mixin must modify exactly two audited methods`, having
found one. Reading the 1.4.0 mixin bytecode directly, it modifies two:

- `@Inject(method = "doStuckDetection", at = @At(value = "INVOKE", target = "Path.getNextNodePos()"))`
- `@ModifyConstant(method = "resetStuckTimeout")`, on `double modifyTimeout(double)`

The audit's ASM enumerator does not recognise `@ModifyConstant`, so it counted one. **It then failed
closed**, which is the correct outcome and the reason the pinned count exists. But the reason it
refused is not the reason it reported, and that is worth knowing before anyone re-pins.

This is also a live gap in the enumerator, independent of 26.2: an audited artifact that used
`@ModifyConstant` would have that modification omitted from `modifiedMethods()`. Here the pinned
count caught it. An audit whose count happened to match would not.

## Why no partial fix helps

Three of the four audits are mechanically re-derivable — pure hash drift with a passing shape proof.
Re-pinning them would still not turn the 26.2 AUDITED harness green, because
`rabbit-pathfinding-fix` on its own forces all six evaluator families to sync. One unaudited mod
touching this code denies everything; that is the design.

So the only route to a working AUDITED tier on 26.2 is a real re-audit of rabbit 1.4.0, which needs
two things this document does not do:

1. `@ModifyConstant` support in the mixin enumerator, so the modified-method set is complete.
2. A fresh non-reachability derivation for a constant modification inside `resetStuckTimeout`.

Neither is release-shaped, and neither should be done by pasting new hashes over the old ones. An
audit is a claim that someone looked; updating the pin without looking makes the claim false while
leaving it green.

## Harness matrix, both branches, 2026-08-31

| Harness | Tier | 26.1.2 | 26.2 |
|---|---|---|---|
| default | AUDITED | 2 passed | **1 failed** |
| `-PunsafeTierHarness` | UNSAFE (shipped default) | 5 passed | 5 passed |
| `-PrefusedHarness` | AUDITED | 2 passed | 2 passed |
| `-PbreakerHarness` | AUDITED | 2 passed | 2 passed |
| `-PauditedRoutingHarness` | AUDITED | 2 passed | **1 failed** |
| unit suite | n/a | 400 passed | 400 passed |

Both 26.2 failures are this one cause, and both name it:

- default: `coordinate move must dispatch one async request`. Nothing dispatches, because every
  family is denied.
- auditedRouting: `live evidence must contain the exact ServerCore audit key`. There is no audit
  key, because the audit refused.

`refused` and `breaker` pass on 26.2 precisely because they assert refusal, which is what 26.2 does.

400 green on 26.2 is the branch's first green unit suite.
