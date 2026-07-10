package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIX 4: a failed async search must flag the entity so the NEXT createPath runs synchronously (a short
 * self-expiring cooldown), instead of re-dispatching a deterministically-failing search forever.
 */
class EntityInstallSinkTest {

    /** Minimal PWNavigation stub - counts the balanced onPathfindingDone calls. */
    static class FakeNav implements PWNavigation {
        int installs, dones;
        boolean stale;
        public void pathweaver$install(Path p) { installs++; }
        public boolean pathweaver$stale(double x, double y, double z) { return stale; }
        public void pathweaver$onPathfindingDone() { dones++; }
    }

    @Test void failedMarksEntityForSyncThenCooldownExpires() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        sink.setTick(100L);
        sink.register(1, nav);

        assertFalse(sink.shouldForceSync(1, 100L), "not failed yet -> async allowed");
        sink.failed(1);

        // Immediately after a failure the mob must run sync (skip async dispatch).
        assertTrue(sink.shouldForceSync(1, 101L));
        assertTrue(sink.shouldForceSync(1, 139L));
        // After the cooldown window it may try async again.
        assertFalse(sink.shouldForceSync(1, 100L + 40L));
        // Balanced callback fired exactly once for the failure.
        assertEquals(1, nav.dones);
        assertEquals(0, nav.installs);
    }

    @Test void successClearsAnyLingeringCooldown() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(10L);
        FakeNav nav1 = new FakeNav();
        sink.register(7, nav1);
        sink.failed(7);
        assertTrue(sink.shouldForceSync(7, 11L));

        // A later successful install for the same entity must wipe the cooldown.
        FakeNav nav2 = new FakeNav();
        sink.register(7, nav2);
        sink.install(7, dummyPath());
        assertFalse(sink.shouldForceSync(7, 12L));
        assertEquals(1, nav2.installs);
    }

    @Test void clearForgetsEverything() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(5L);
        sink.register(3, new FakeNav());
        sink.failed(3);
        sink.clear();
        assertFalse(sink.shouldForceSync(3, 6L));
        assertEquals(0, sink.inFlightCount());
    }

    // Non-null Path sentinel; install only null-checks upstream, the FakeNav never dereferences it.
    private static Path dummyPath() {
        try {
            var f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            var alloc = unsafe.getClass().getMethod("allocateInstance", Class.class);
            return (Path) alloc.invoke(unsafe, Path.class);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
