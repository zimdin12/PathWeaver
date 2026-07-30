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
 * <p>Publication is package-private and there is no reset. Both matter. An earlier revision exposed
 * a public first-writer setter, which any loaded mod could call with the widest answer before the
 * scan ran; because the first write wins, the scan's own {@code STRICT} publication would then be
 * ignored and the same scan would clear its denials down the {@code ALL} path. A test-only reset was
 * also compiled into the shipped jar, which contradicted the process-lifetime guarantee this class
 * exists to provide. Tests use {@link #isolatedState()} instead, which cannot touch the singleton.
 *
 * <p>Stored as booleans rather than as a {@code CompatibilityTier}. The dispatch interceptor is a
 * mixin applied to a vanilla class during early transformation, and naming the enum from there
 * forces it, and the Cloth GUI interface it implements, to resolve at that moment.
 *
 * <p>Before publication both answers are false, so an aborted or absent scan denies.
 */
public final class ActiveCompatibilityPolicy {
    private static final State PROCESS = new State();

    private ActiveCompatibilityPolicy() {}

    /**
     * Freeze the tier for the remainder of the process. Called by the scan that consumes it.
     *
     * <p>Package-private on purpose: the only caller is {@link ForeignMixinScanner}, and widening
     * this to public would let anything on the classpath decide the safety tier before the scan has
     * read the configured one.
     */
    static void publish(boolean tierAllowsAudited, boolean tierBypassesScan) {
        PROCESS.publish(tierAllowsAudited, tierBypassesScan);
    }

    /** True when audited exemptions may be honoured. False until the scan publishes. */
    public static boolean allowsAudited() {
        return PROCESS.allowsAudited();
    }

    /** True when the operator asked for no compatibility checking at all. */
    public static boolean bypassesScan() {
        return PROCESS.bypassesScan();
    }

    /** True once the scan has frozen a tier. */
    public static boolean published() {
        return PROCESS.published();
    }

    /** Fresh state for pure tests; it cannot mutate or replace the production singleton. */
    static State isolatedState() {
        return new State();
    }

    static final class State {
        private volatile boolean published;
        private volatile boolean allowsAudited;
        private volatile boolean bypassesScan;

        /** Idempotent by first write, so nothing later in startup can widen the frozen answer. */
        synchronized void publish(boolean tierAllowsAudited, boolean tierBypassesScan) {
            if (published) return;
            allowsAudited = tierAllowsAudited;
            bypassesScan = tierBypassesScan;
            published = true;
        }

        boolean allowsAudited() {
            return published && allowsAudited;
        }

        boolean bypassesScan() {
            return published && bypassesScan;
        }

        boolean published() {
            return published;
        }
    }
}
