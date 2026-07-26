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
        private final AtomicLong workerProviderLookupBypasses = new AtomicLong();
        private volatile boolean hooksVerified;

        void beforeProviderMutation() {
            providerRegistrationObserved.set(true);
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
            return hooksVerified && !providerRegistrationObserved.get();
        }

        boolean providerRegistrationObserved() {
            return providerRegistrationObserved.get();
        }
    }
}
