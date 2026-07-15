package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityInstallSinkTest {

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
        RequestKey key = key(1L, 1L, 1);
        sink.setTick(100L);
        sink.register(key, nav);

        assertFalse(sink.shouldForceSync(1, 100L));
        sink.failed(key);

        assertTrue(sink.shouldForceSync(1, 101L));
        assertTrue(sink.shouldForceSync(1, 139L));
        assertFalse(sink.shouldForceSync(1, 140L));
        assertEquals(1, nav.dones);
        assertEquals(0, nav.installs);
    }

    @Test void successClearsAnyLingeringCooldown() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(10L);
        FakeNav nav1 = new FakeNav();
        RequestKey first = key(1L, 1L, 7);
        sink.register(first, nav1);
        sink.failed(first);
        assertTrue(sink.shouldForceSync(7, 11L));

        FakeNav nav2 = new FakeNav();
        RequestKey second = key(1L, 2L, 7);
        sink.register(second, nav2);
        sink.install(second, dummyPath());
        assertFalse(sink.shouldForceSync(7, 12L));
        assertEquals(1, nav2.installs);
    }

    @Test void clearForgetsEverything() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(5L);
        RequestKey key = key(1L, 1L, 3);
        sink.register(key, new FakeNav());
        sink.failed(key);
        sink.clear();
        assertFalse(sink.shouldForceSync(3, 6L));
        assertEquals(0, sink.inFlightCount());
    }

    @Test void lateOldResultCannotInstallIntoReplacementRegistrationForSameEntityId() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav oldNavigation = new FakeNav();
        FakeNav replacementNavigation = new FakeNav();
        RequestKey oldKey = key(1L, 40L, 11);
        RequestKey replacementKey = key(3L, 41L, 11);
        sink.register(oldKey, oldNavigation);
        sink.clear();
        sink.register(replacementKey, replacementNavigation);

        sink.install(oldKey, dummyPath());

        assertEquals(0, replacementNavigation.installs);
        assertTrue(sink.isRegistered(11));
        sink.install(replacementKey, dummyPath());
        assertEquals(1, replacementNavigation.installs);
    }

    @Test void lateOldFailureCannotCooldownOrConsumeReplacement() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(20L);
        RequestKey oldKey = key(1L, 5L, 12);
        RequestKey replacementKey = key(3L, 6L, 12);
        FakeNav replacement = new FakeNav();
        sink.register(replacementKey, replacement);

        sink.failed(oldKey);

        assertFalse(sink.shouldForceSync(12, 21L));
        assertTrue(sink.isRegistered(12));
        assertEquals(0, replacement.dones);
    }

    private static RequestKey key(long epoch, long token, int entityId) {
        return new RequestKey(epoch, token, entityId);
    }

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
