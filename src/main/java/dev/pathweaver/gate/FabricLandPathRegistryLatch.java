package dev.pathweaver.gate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-lifetime publication barrier for Fabric's land path-type provider registry.
 *
 * <p>The registration hook calls {@link #beforeProviderMutation()} after argument validation and
 * before the live registry map is mutated. The production state is monotonic and has no reset.
 * Workers never read the registry; dispatch and main-thread installation read only this published
 * state.</p>
 */
public final class FabricLandPathRegistryLatch {
    private static final State PROCESS = new State();

    private FabricLandPathRegistryLatch() {}

    /** Called by the exact registration hooks before PATH_TYPES.put. */
    public static void beforeProviderMutation() {
        PROCESS.beforeProviderMutation();
    }

    /**
     * Record that a dynamic provider was registered but certified by an exact audit.
     *
     * <p>Kept separate from {@link #beforeProviderMutation()} because the two are decided at
     * different times. Whether the audit may be honoured depends on the compatibility tier, and
     * registration is far too early to read it: mods register blocks during their own initializer,
     * which for Farmer's Delight runs before PathWeaver has loaded its config at all. Reading the
     * tier there saw the fail-closed default and denied every time, regardless of the real setting.
     * So the fact is published here and the policy is applied at dispatch, where config is loaded.
     */
    public static void certifiedProviderRegistered() {
        PROCESS.certifiedProviderRegistered();
    }

    /**
     * Certified via the exact Farmer's Delight audit rather than the generic static rule.
     *
     * <p>Tracked separately because it carries one extra dispatch-time obligation: the audit read the
     * mod's jar, so it is only valid while no foreign mixin has transformed the audited class.
     */
    public static void auditedDynamicProviderRegistered() {
        PROCESS.certifiedProviderRegistered();
        PROCESS.auditedDynamicProviderRegistered();
    }

    public static boolean certifiedProviderObserved() {
        return PROCESS.certifiedProviderObserved();
    }

    /** Called only when the worker-side HEAD hook bypasses the live provider map. */
    public static void recordWorkerProviderLookupBypass() {
        PROCESS.recordWorkerProviderLookupBypass();
    }

    public static long workerProviderLookupBypasses() {
        return PROCESS.workerProviderLookupBypasses();
    }

    /** Published only after the exact module/class/ASM verification has completed. */
    static void publishHooksVerified(boolean verified) {
        PROCESS.publishHooksVerified(verified);
    }

    public static boolean allowsWalkDispatch() {
        return PROCESS.allowsWalk();
    }

    public static boolean allowsWalkInstall() {
        return PROCESS.allowsWalk();
    }

    public static boolean providerRegistrationObserved() {
        return PROCESS.providerRegistrationObserved();
    }

    /** Fresh state for pure ordering tests; it cannot mutate or replace the production singleton. */
    static State isolatedState() {
        return new State();
    }

    static final class State {
        private final AtomicBoolean providerRegistrationObserved = new AtomicBoolean();
        private final AtomicBoolean certifiedProviderObserved = new AtomicBoolean();
        private final AtomicBoolean auditedDynamicProviderObserved = new AtomicBoolean();
        private final AtomicLong workerProviderLookupBypasses = new AtomicLong();
        private volatile boolean hooksVerified;

        void beforeProviderMutation() {
            providerRegistrationObserved.set(true);
        }

        void certifiedProviderRegistered() {
            certifiedProviderObserved.set(true);
        }

        void auditedDynamicProviderRegistered() {
            auditedDynamicProviderObserved.set(true);
        }

        boolean certifiedProviderObserved() {
            return certifiedProviderObserved.get();
        }

        void recordWorkerProviderLookupBypass() {
            workerProviderLookupBypasses.incrementAndGet();
        }

        long workerProviderLookupBypasses() {
            return workerProviderLookupBypasses.get();
        }

        void publishHooksVerified(boolean verified) {
            hooksVerified = verified;
        }

        boolean allowsWalk() {
            if (!hooksVerified || providerRegistrationObserved.get()) return false;
            // An audited dynamic provider was frozen like a static one, but the audit is evidence
            // rather than proven inertness, so it only counts above the strict tier. Read here
            // rather than at registration: this runs at dispatch, where config is loaded.
            if (certifiedProviderObserved.get() && !ActiveCompatibilityPolicy.allowsAudited()) {
                return false;
            }
            // The audit proved a property of bytes read from a jar. Another mod transforming that
            // class changes what actually runs, so re-check once the scan can answer.
            return !auditedDynamicProviderObserved.get()
                || FarmersDelightStoveCompatibility.hostNotTransformed();
        }

        boolean providerRegistrationObserved() {
            return providerRegistrationObserved.get();
        }
    }
}
