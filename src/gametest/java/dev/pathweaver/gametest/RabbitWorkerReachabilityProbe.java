package dev.pathweaver.gametest;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared state for the test-only Rabbit worker-reachability mixin. */
public final class RabbitWorkerReachabilityProbe {
    private static final AtomicInteger WORKER_ENTRIES = new AtomicInteger();

    private RabbitWorkerReachabilityProbe() {}

    public static void recordWorkerEntry() {
        WORKER_ENTRIES.incrementAndGet();
    }

    public static void reset() {
        WORKER_ENTRIES.set(0);
    }

    public static int workerEntries() {
        return WORKER_ENTRIES.get();
    }
}
