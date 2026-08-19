Minecraft **26.2** build of 0.6.1. Same mod, same code — no behaviour differs from the 26.1.2 release.

### Read this if you use the checked tier

`compatibilityTier=AUDITED` **denies every movement family on 26.2**, so the mod does nothing at that
setting. That is the gate working, not a bug: every per-mod exemption is pinned to the exact bytes it
was derived from, and 26.2 changed vanilla — `WalkNodeEvaluator`, `PathNavigation` and
`BlockBehaviour$BlockStateBase` are all different files — so the evidence no longer applies. Re-pinning
the hashes without redoing the bytecode analysis would be a safety claim nobody had checked, so the
audits refuse instead. Re-auditing for 26.2 is on the roadmap.

The shipped default is `UNSAFE`, which performs no such check, so **this affects you only if you
deliberately switched to AUDITED**.

### Verified on 26.2

- `PathWeaver is ACTIVE: all 6 movement families can path off-thread`
- Server game tests at the shipped default: 2/2, dispatched=1 installed=1 discarded=0
- Client harness: real singleplayer world, 3 dispatched / 3 installed / 0 discarded, settings screen
  renders
- 357 of 360 unit tests. The 3 failures are the version-pinned audit bundles described above, and
  nothing else.

### For the curious: what 26.2 changed

Nothing in the mod. The port is a version bump plus a manifest range. Two vanilla changes did break
the *test* sources: `EntityType` lost about 170 constants (`EntityType.ZOMBIE` is now an
`EntityTypeIds` resource key resolved through the registry, and `ResourceLocation` was renamed to
`Identifier`), and `Minecraft.screen` no longer exists — the current screen moved to `Gui.screen`,
private. Both are worked around in a way that still compiles against 26.1.2, so one source tree
serves both.

If you run ServerCore, make sure it is the 26.2 build; the 26.1.2 one fails at mixin apply on 26.2
regardless of this mod.
