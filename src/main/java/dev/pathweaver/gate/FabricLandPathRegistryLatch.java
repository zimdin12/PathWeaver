package dev.pathweaver.gate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-lifetime publication barrier for Fabric's land path-type provider registry.
 *
 * <p>The registration hook calls {@link #beforeProviderMutation()} after argument validation and
 * before the live registry map is mutated. The bit is monotonic in production. Workers never read
 * the registry; dispatch and main-thread installation read only this published state.</p>
 */
public final class FabricLandPathRegistryLatch {
    private static final AtomicBoolean PROVIDER_REGISTRATION_OBSERVED = new AtomicBoolean();
    private static final AtomicLong WORKER_PROVIDER_LOOKUP_BYPASSES = new AtomicLong();
    private static volatile boolean hooksVerified;

    private FabricLandPathRegistryLatch() {}

    /** Called by the exact registration hooks before PATH_TYPES.put. */
    public static void beforeProviderMutation() {
        PROVIDER_REGISTRATION_OBSERVED.set(true);
    }

    /** Called only when the worker-side HEAD hook bypasses the live provider map. */
    public static void recordWorkerProviderLookupBypass() {
        WORKER_PROVIDER_LOOKUP_BYPASSES.incrementAndGet();
    }

    public static long workerProviderLookupBypasses() {
        return WORKER_PROVIDER_LOOKUP_BYPASSES.get();
    }

    /** Published only after the exact module/class/ASM verification has completed. */
    static void publishHooksVerified(boolean verified) {
        hooksVerified = verified;
    }

    public static boolean allowsWalkDispatch() {
        return hooksVerified && !PROVIDER_REGISTRATION_OBSERVED.get();
    }

    public static boolean allowsWalkInstall() {
        return hooksVerified && !PROVIDER_REGISTRATION_OBSERVED.get();
    }

    public static boolean providerRegistrationObserved() {
        return PROVIDER_REGISTRATION_OBSERVED.get();
    }

    /** Package-private synthetic seam; production has no reset path. */
    static void resetForTests() {
        hooksVerified = false;
        PROVIDER_REGISTRATION_OBSERVED.set(false);
        WORKER_PROVIDER_LOOKUP_BYPASSES.set(0);
    }
}
