package dev.pathweaver.snapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Right-sizes the pathfinding region radius and shares one immutable snapshot across nearby mobs
 * within a single tick. Region construction is injected via a {@link Supplier}, keeping this class
 * free of MC types and unit-testable off-game. Cleared each tick by the runtime.
 */
public class SnapshotProvider {
    private final Map<SnapshotKey, Object> cache = new ConcurrentHashMap<>();

    /**
     * @param vanillaRadius       the radius vanilla would have used (upper bound)
     * @param actualMaxPathLength the mob's real max path length for this search
     * @param margin              slack blocks beyond the path length
     * @return a radius >= 1, never above vanilla, never below path length + margin
     */
    public int rightSizedRadius(int vanillaRadius, int actualMaxPathLength, int margin) {
        int wanted = Math.max(actualMaxPathLength + margin, 1);
        return Math.min(wanted, vanillaRadius);
    }

    @SuppressWarnings("unchecked")
    public <R> R getOrBuild(SnapshotKey key, Supplier<R> factory) {
        return (R) cache.computeIfAbsent(key, k -> factory.get());
    }

    /** Drop every cached snapshot not belonging to the current tick. */
    public void clearTick(long currentTick) {
        cache.keySet().removeIf(k -> k.tick() != currentTick);
    }

    public int cachedCount() { return cache.size(); }
}
