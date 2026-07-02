package dev.pathweaver.config;

/**
 * Plain config model the engine reads. Cloth-config GUI/serialization is layered on in Task 11;
 * this POJO stays free of MC/cloth types so it is unit-testable off-game.
 */
public class PathWeaverConfig {
    public boolean asyncEnabled = true;
    public boolean repathElisionEnabled = true;
    public int poolThreads = 0;          // 0 = auto (cores/4)
    public int maxInFlight = 256;
    public boolean distanceThrottleEnabled = false;  // opt-in: makes far mobs dumber
    public boolean syncFallbackOnly = false;         // panic switch: never dispatch async
    public int repathToleranceBlocks = 1;
    public double stalenessMoveThreshold = 4.0;      // blocks moved since dispatch -> discard

    private static PathWeaverConfig INSTANCE = new PathWeaverConfig();
    public static PathWeaverConfig get() { return INSTANCE; }
    public static void set(PathWeaverConfig c) { INSTANCE = c; }

    public int resolvedPoolThreads() {
        return poolThreads > 0 ? poolThreads
             : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }
}
