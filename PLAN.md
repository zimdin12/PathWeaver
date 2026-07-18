# PathWeaver release plan/status

## Shipped history

- [x] v0.1.1 honesty/default-off correction.
- [x] v0.1.2 navigation-only routing, default-on new configs, and fail-closed compatibility discovery.
- [x] v0.2 correctness baseline: epoch/token lifecycle isolation, complete install staleness, tagged outcomes, evaluator-specific callback accounting, exact terminal balance, and valid repath reuse/recompute invalidation.

## v0.2.0 final release

- [x] Steven chose SHIP as the honest fail-closed final form.
- [x] Private snapshot/A* rejected after its lower-bound capture-cost spike failed the agreed relative-cost gate.
- [x] No load/scaling matrix for the cancelled engine.
- [x] Standard Fabric content-registry packs remain synchronous; no broad provider trust.
- [x] Explicit ModMenu entrypoint, prominent persistent async toggle, and panic switch.
- [x] Honest README/design/changelog/manifest and Modrinth copy.

Release closure requires a cold JDK 25 unit/build/GameTest/runtime/package gate, an independent exact-final-tree PASS, a clean release commit pushed to `master`, and an annotated `v0.2.0` tag on that commit.

## Future boundary

No active engine work remains after 0.2.0. The only viable future route is an upstream immutable-chunk/provider-purity API. Do not resume private snapshot/A* or scaling work without a new explicit product decision and that external seam.

AI sensor/target-scan offload remains out of scope; any future proposal requires a separate purity audit and measured payoff.
