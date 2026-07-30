package dev.pathweaver.gate;

/**
 * Read-only view of the compatibility tier this process is running under, frozen once at scan time.
 *
 * <p>The tier cannot be read live. Evidence for audited exemptions is computed exactly once, during
 * the startup scan, and denials are published from it; but the land-registry latch and the dispatch
 * interceptor used to consult the live config on every request. Saving a different tier from the
 * settings screen therefore produced an incoherent mixture rather than a change: startup denials
 * that had already been waived stayed waived, while checks that are evaluated per request tightened
 * immediately. Starting at {@code ALL} and saving {@code STRICT} left unaudited mixins still
 * dispatching under a setting that claimed to forbid exactly that.
 *
 * <p>So the tier is captured when the scan computes evidence, and every tier-derived decision reads
 * that snapshot instead of the config. The setting is annotated as requiring a restart, which is now
 * enforced rather than merely advertised. Saving still persists to disk and still takes effect — on
 * the next launch.
 *
 * <p><b>This class holds nothing and can change nothing.</b> The frozen value lives in
 * {@link ForeignMixinScanner} behind a {@code private} mutator, and this is a facade over the two
 * answers. That placement is deliberate. An earlier revision kept the state here with a public
 * setter, which let anything on the classpath freeze the widest answer before the scan ran — and
 * because the first write wins, the scan's own {@code STRICT} publication then became a no-op and
 * the same scan cleared its denials down the {@code ALL} path. Narrowing that setter to
 * package-private was not enough either: runtime package access is package name plus classloader,
 * not code source, and Fabric mods share the target classloader, so a mod shipping a class in
 * {@code dev.pathweaver.gate} could still have called it. Only {@code private} is enforced against
 * that, so the mutator sits in the class that computes the value and nothing else can reach it.
 *
 * <p>Answers are primitives rather than a {@code CompatibilityTier}. The dispatch interceptor is a
 * mixin applied to a vanilla class during early transformation, and naming the enum from there
 * forces it, and the Cloth GUI interface it implements, to resolve at that moment.
 *
 * <p>Before the scan freezes a tier both answers are false, so an aborted or absent scan denies.
 */
public final class ActiveCompatibilityPolicy {
    private ActiveCompatibilityPolicy() {}

    /** True when audited exemptions may be honoured. False until the scan freezes a tier. */
    public static boolean allowsAudited() {
        return ForeignMixinScanner.frozenTierAllowsAudited();
    }

    /** True when the operator asked for no compatibility checking at all. */
    public static boolean bypassesScan() {
        return ForeignMixinScanner.frozenTierBypassesScan();
    }

    /** True once the scan has frozen a tier. Package-private: production needs only the answers. */
    static boolean published() {
        return ForeignMixinScanner.tierFrozen();
    }
}
