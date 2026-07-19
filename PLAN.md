# PathWeaver release plan/status

## Shipped history

- [x] v0.1.1 honesty/default-off correction.
- [x] v0.1.2 navigation-only routing, default-on new configs, and fail-closed compatibility discovery.
- [x] v0.2 correctness baseline: epoch/token lifecycle isolation, complete install staleness, tagged outcomes, evaluator-specific callback accounting, exact terminal balance, and valid repath reuse/recompute invalidation.

## v0.2.3 release closure

- [x] Remove Cloth's empty untranslated implicit default category.
- [x] Contract-check all generated categories for translation and at least one visible option.
- [x] Rendered client proof: three translated tabs, 9/9 options, tooltip rendered, clean client stop.
- [x] Cold JDK 25 gate: 106/106 JUnit, 2/2 GameTests, clean v0.2.3 package.
- [ ] Independent exact-tree review.

## v0.2.2 release closure

- [x] Config safe publication and synchronous fail-closed load fallback.
- [x] Current-environment/slash-safe compatibility scanning and pre-scan deny-all state.
- [x] Remove dead/no-op Fly, distance-throttle, annotation-reader, and repath helper surfaces.
- [x] Vanilla-origin mob eligibility gate and default-off advanced modded-mob override.
- [x] Recompute pre-guard supersession and accepted-speed preservation.
- [x] Cold JDK 25 build with 105/105 JUnit, 2/2 GameTests, artifact-only production-Knot
  CodeSource probe (`Mob=true`, `Zombie=true`, bypass false), malformed-config fail-closed startup,
  dedicated-server startup, and package gate.
- [x] Independent exact-tree review: APPROVE at `2c7c417` with no blocking findings.

## v0.2.1 shipped baseline

- [x] Steven chose SHIP as the honest fail-closed final form.
- [x] Private snapshot/A* rejected after its lower-bound capture-cost spike failed the agreed relative-cost gate.
- [x] No load/scaling matrix for the cancelled engine.
- [x] Standard Fabric content-registry packs remain synchronous; no broad provider trust.
- [x] Explicit ModMenu entrypoint, prominent persistent async toggle, and panic switch.
- [x] Honest README/design/changelog/manifest and Modrinth copy.

Release closure used a cold JDK 25 unit/build/GameTest/runtime/package gate, an independent exact-final-tree PASS, a clean release commit pushed to `master`, and an annotated `v0.2.1` tag on that commit.

## Future boundary

No active engine work remains after 0.2.2. The only viable future route is an upstream immutable-chunk/provider-purity API. Do not resume private snapshot/A* or scaling work without a new explicit product decision and that external seam.

AI sensor/target-scan offload remains out of scope; any future proposal requires a separate purity audit and measured payoff.
