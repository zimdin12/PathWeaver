package dev.pathweaver.snapshot;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotProviderTest {
    @Test void radiusNeverExceedsVanillaNorGoesBelowPathLength() {
        SnapshotProvider p = new SnapshotProvider();
        assertEquals(10, p.rightSizedRadius(32, 8, 2));   // 8+2=10 < 32
        assertEquals(32, p.rightSizedRadius(32, 40, 2));  // clamp to vanilla 32
        assertEquals(1, p.rightSizedRadius(32, 0, 0));    // floor 1
    }
    @Test void sharesOneBuildPerKeyInSameTick() {
        SnapshotProvider p = new SnapshotProvider();
        SnapshotKey k = new SnapshotKey("minecraft:overworld", 100L, 0,0,2,2);
        AtomicInteger builds = new AtomicInteger();
        Object a = p.getOrBuild(k, () -> { builds.incrementAndGet(); return new Object(); });
        Object b = p.getOrBuild(k, () -> { builds.incrementAndGet(); return new Object(); });
        assertSame(a, b);
        assertEquals(1, builds.get());
    }
    @Test void clearTickDropsStaleEntries() {
        SnapshotProvider p = new SnapshotProvider();
        SnapshotKey k = new SnapshotKey("minecraft:overworld", 100L, 0,0,2,2);
        p.getOrBuild(k, Object::new);
        p.clearTick(101L); // different tick -> evict
        AtomicInteger builds = new AtomicInteger();
        p.getOrBuild(k, () -> { builds.incrementAndGet(); return new Object(); });
        assertEquals(1, builds.get()); // rebuilt because evicted
    }
}
