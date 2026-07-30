package dev.pathweaver.gate;

/**
 * The compatibility tier this process is actually running under, frozen once at scan time.
 *
 * <p>The tier cannot be read live. Evidence for audited exemptions is computed exactly once, during
 * the startup scan, and denials are published from it; but the land-registry latch and the dispatch
 * interceptor used to consult the live config on every request. Saving a different tier from the
 * settings screen therefore produced an incoherent mixture rather than a change: startup denials
 * that had already been waived stayed waived, while checks that are evaluated per request tightened
 * immediately. Starting at {@code ALL} and saving {@code STRICT} left unaudited mixins still
 * dispatching under a setting that claimed to forbid exactly that.
 *
 * <p>So the tier is captured here when the scan computes evidence, and every tier-derived decision
 * reads this snapshot instead of the config. The setting is annotated as requiring a restart, which
 * is now enforced rather than merely advertised. Saving still persists to disk and still takes
 * effect — on the next launch.
 *
 * <p>Stored as booleans rather than as a {@code CompatibilityTier}. The dispatch interceptor is a
 * mixin applied to a vanilla class during early transformation, and naming the enum from there
 * forces it, and the Cloth GUI interface it implements, to resolve at that moment.
 *
 * <p>Before publication both answers are false, so an aborted or absent scan denies.
 */
public final class ActiveCompatibilityPolicy {
    private static volatile boolean published;
    private static volatile boolean allowsAudited;
    private static volatile boolean bypassesScan;

    private ActiveCompatibilityPolicy() {}

    /**
     * Freeze the tier for the remainder of the process. Called by the scan that consumes it.
     *
     * <p>Idempotent by first write: a second call is ignored, so nothing later in startup can widen
     * what the scan already decided against.
     */
    public static synchronized void publish(boolean tierAllowsAudited, boolean tierBypassesScan) {
        if (published) return;
        allowsAudited = tierAllowsAudited;
        bypassesScan = tierBypassesScan;
        published = true;
    }

    /** True when audited exemptions may be honoured. False until the scan publishes. */
    public static boolean allowsAudited() {
        return published && allowsAudited;
    }

    /** True when the operator asked for no compatibility checking at all. */
    public static boolean bypassesScan() {
        return published && bypassesScan;
    }

    /** True once the scan has frozen a tier. */
    public static boolean published() {
        return published;
    }

    /** Test seam: unfreeze so a test can publish a different tier. Never called in production. */
    static synchronized void resetForTests() {
        published = false;
        allowsAudited = false;
        bypassesScan = false;
    }
}
