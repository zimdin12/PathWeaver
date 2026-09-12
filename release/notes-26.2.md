Minecraft **26.2** build of 0.9.0. Everything the 26.1.2 changelog describes applies here unchanged.

The two branches differ in five files, and all five are the compatibility audits, which name 26.2
artifacts rather than 26.1.2 ones. Every other source file is identical.

### The checked tier works on 26.2 again

`compatibilityTier=AUDITED` used to deny every movement family on 26.2, so the mod did nothing at that
setting. That was the gate working rather than a bug: each audit is pinned to the exact bytes it was
derived from, 26.2 changed four of those classes, and the pins failed closed.

It is fixed. All four audited artifacts certify on 26.2, three by re-deriving hashes whose shape
proofs still passed on the new bytes, and one by a new proof because that mod changed mechanism. The
working is in `docs/AUDITS-ON-26.2.md`, including nine claims an earlier version of that document got
wrong and a reviewer refuted.

The shipped default is still `UNSAFE`, which performs no such check.

### Verified on 26.2, at the commit this jar was built from

- 66 suites, 502 unit tests, 0 failures
- 28 four-state witnesses: green, then a cause-specific red on reverting exactly the production
  change, then green again on restoring it
- 8 of 8 game-test harnesses, one attempt each, no reruns, every row checked against the manifest it
  actually consumed and the providers on its classpath at launch
- Booted on a clean Fabric 26.2 server with Fabric API and no Cloth Config: starts, reports all six
  movement families active, writes its config file. With the Cloth interface deliberately put back it
  does not start, which is what makes the first result mean something. Logs in
  `docs/evidence/no-cloth-26.2/`.

### For the curious: what 26.2 changed

Nothing in the mod's logic. The port is a version bump, a manifest range and the audit pins. Two
vanilla changes did break the *test* sources: `EntityType` lost about 170 constants
(`EntityType.ZOMBIE` is now an `EntityTypeIds` resource key resolved through the registry, and
`ResourceLocation` was renamed to `Identifier`), and `Minecraft.screen` no longer exists, since the
current screen moved to `Gui.screen` and is private. Both are worked around in a way that still
compiles against 26.1.2, so one source tree serves both.

If you run ServerCore, make sure it is the 26.2 build; the 26.1.2 one fails at mixin apply on 26.2
regardless of this mod.
