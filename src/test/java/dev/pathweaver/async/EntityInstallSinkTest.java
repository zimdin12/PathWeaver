package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityInstallSinkTest {

    static class FakeNav implements PWNavigation {
        int installs, dones;
        boolean stale;
        Object uuid = new Object();
        Object world = new Object();
        Object dimension = "overworld";
        Object path;
        long revision;
        PWNavigation identityNavigation = this;
        public void pathweaver$install(Path p) { installs++; }
        public boolean pathweaver$stale(double x, double y, double z) { return stale; }
        public NavigationIdentity pathweaver$identity() {
            return new NavigationIdentity(uuid, world, dimension, identityNavigation, path, revision);
        }
        public void pathweaver$onPathfindingDone() { dones++; }
    }

    @Test void registeredNavigationMatchesByExactIdentity() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav registered = new FakeNav();
        sink.register(key(1L, 1L, 2), registered);

        assertTrue(sink.isRegistered(2, registered));
        assertFalse(sink.isRegistered(2, new FakeNav()));
        assertFalse(sink.isRegistered(3, registered));
    }

    @Test void failedMarksEntityForSyncThenCooldownExpires() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        RequestKey key = key(1L, 1L, 1);
        sink.setTick(100L);
        sink.register(key, nav);

        assertFalse(sink.shouldForceSync(1, 100L));
        sink.failed(key, new IllegalStateException("search failed"));

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
        sink.failed(first, new IllegalStateException("search failed"));
        assertTrue(sink.shouldForceSync(7, 11L));

        FakeNav nav2 = new FakeNav();
        RequestKey second = key(1L, 2L, 7);
        sink.register(second, nav2);
        sink.install(second, dummyPath());
        assertFalse(sink.shouldForceSync(7, 12L));
        assertEquals(1, nav2.installs);
        assertEquals(1, nav2.dones);
    }

    @Test void clearBalancesLiveRegistrationsAndForgetsCooldowns() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(5L);
        FakeNav failed = new FakeNav();
        sink.register(key(1L, 1L, 3), failed);
        sink.failed(key(1L, 1L, 3), new IllegalStateException("search failed"));
        FakeNav live = new FakeNav();
        sink.register(key(1L, 2L, 4), live);

        sink.clear();

        assertFalse(sink.shouldForceSync(3, 6L));
        assertEquals(0, sink.inFlightCount());
        assertEquals(1, failed.dones);
        assertEquals(1, live.dones);
    }

    @Test void throwingCallbackDuringClearCannotStrandOtherRegistrations() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav throwing = new FakeNav() {
            @Override public void pathweaver$onPathfindingDone() {
                dones++;
                throw new IllegalStateException("callback boom");
            }
        };
        FakeNav other = new FakeNav();
        sink.register(key(1L, 3L, 5), throwing);
        sink.register(key(1L, 4L, 6), other);

        assertDoesNotThrow(sink::clear);

        assertEquals(0, sink.inFlightCount());
        assertEquals(1, throwing.dones);
        assertEquals(1, other.dones);
    }

    @Test void installExceptionBalancesCallbackAndForcesLaterSync() {
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(30L);
        FakeNav throwing = new FakeNav() {
            @Override public void pathweaver$install(Path path) {
                installs++;
                throw new IllegalStateException("install boom");
            }
        };
        RequestKey key = key(1L, 5L, 8);
        sink.register(key, throwing);

        assertDoesNotThrow(() -> sink.install(key, dummyPath()));

        assertEquals(1, throwing.installs);
        assertEquals(1, throwing.dones);
        assertFalse(sink.isRegistered(8));
        assertTrue(sink.shouldForceSync(8, 31L));
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

        sink.failed(oldKey, new IllegalStateException("search failed"));

        assertFalse(sink.shouldForceSync(12, 21L));
        assertTrue(sink.isRegistered(12));
        assertEquals(0, replacement.dones);
    }

    @Test void resultAgeHasExactInclusiveBoundaryAndRejectsTickRollback() {
        dev.pathweaver.config.PathWeaverConfig previous =
            dev.pathweaver.config.PathWeaverConfig.get();
        dev.pathweaver.config.PathWeaverConfig configured =
            new dev.pathweaver.config.PathWeaverConfig();
        configured.maxResultAgeTicks = 5;
        dev.pathweaver.config.PathWeaverConfig.set(configured);
        try {
            EntityInstallSink sink = new EntityInstallSink();
            RequestKey key = key(1L, 7L, 13);
            sink.register(key, new FakeNav());

            sink.setTick(5L);
            assertFalse(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
            sink.setTick(6L);
            assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
            sink.setTick(-1L);
            assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
        } finally {
            dev.pathweaver.config.PathWeaverConfig.set(previous);
        }
    }

    @Test void everyCapturedNavigationIdentityComponentRejectsAChangedLiveNavigation() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        RequestKey key = key(1L, 8L, 14);
        sink.setTick(1L);
        sink.register(key, nav);
        assertFalse(sink.isStale(key, 0L, 0.0, 0.0, 0.0));

        Object original = nav.uuid;
        nav.uuid = new Object();
        assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
        nav.uuid = original;

        original = nav.world;
        nav.world = new Object();
        assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
        nav.world = original;

        nav.dimension = "the_nether";
        assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
        nav.dimension = "overworld";

        nav.identityNavigation = new FakeNav();
        assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
        nav.identityNavigation = nav;

        nav.path = new Object();
        assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
        nav.path = null;

        nav.revision++;
        assertTrue(sink.isStale(key, 0L, 0.0, 0.0, 0.0));
    }

    @Test void changedTargetSupersedesAndBalancesThePriorRegistration() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        RequestTarget first = RequestTarget.of(java.util.Set.of("a"), 8, false, 1, 32.0F);
        RequestTarget changed = RequestTarget.of(java.util.Set.of("b"), 8, false, 1, 32.0F);
        sink.register(key(1L, 9L, 15), nav, first);

        assertEquals(EntityInstallSink.PendingDecision.PRESERVE,
            sink.pendingDecision(15, nav, first));
        assertEquals(EntityInstallSink.PendingDecision.SUPERSEDE,
            sink.pendingDecision(15, nav, changed));
        assertEquals(EntityInstallSink.PendingDecision.SUPERSEDE,
            sink.pendingDecision(15, new FakeNav(), first));
        nav.path = new Object();
        assertEquals(EntityInstallSink.PendingDecision.SUPERSEDE,
            sink.pendingDecision(15, nav, first));
        nav.path = null;
        assertTrue(sink.supersede(15));
        assertFalse(sink.isRegistered(15));
        assertEquals(1, nav.dones);
    }

    @Test void noPathBalancesRegistrationWithoutFailureCooldown() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        RequestKey key = key(1L, 20L, 19);
        sink.setTick(100L);
        sink.register(key, nav);

        sink.noPath(key);

        assertFalse(sink.isRegistered(19));
        assertFalse(sink.shouldForceSync(19, 101L));
        assertEquals(1, nav.dones);
        assertEquals(0, nav.installs);
    }

    @Test void lateOldNavigationStopCannotCancelExplicitlyBalancedReplacementRegistration() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav oldNavigation = new FakeNav();
        FakeNav replacement = new FakeNav();
        sink.register(key(1L, 1L, 16), oldNavigation);
        assertTrue(sink.supersede(16));
        sink.register(key(1L, 2L, 16), replacement);

        assertFalse(sink.cancel(16, oldNavigation));
        assertTrue(sink.isRegistered(16));
        assertEquals(1, oldNavigation.dones);
        assertEquals(0, replacement.dones);

        assertTrue(sink.cancel(16, replacement));
        assertFalse(sink.isRegistered(16));
        assertEquals(1, replacement.dones);
    }

    @Test void duplicateRegisterFailsClosedWithoutDisplacingAcceptedRegistration() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav accepted = new FakeNav();
        FakeNav duplicate = new FakeNav();
        RequestKey acceptedKey = key(1L, 10L, 20);
        sink.register(acceptedKey, accepted);

        assertThrows(IllegalStateException.class,
            () -> sink.register(key(1L, 11L, 20), duplicate));

        assertTrue(sink.isRegistered(20));
        sink.discard(acceptedKey);
        assertEquals(1, accepted.dones);
        assertEquals(0, duplicate.dones);
    }

    @Test void acceptedSameTargetIsPreservedAcrossBothMidFlightConfigToggles() {
        dev.pathweaver.config.PathWeaverConfig previous =
            dev.pathweaver.config.PathWeaverConfig.get();
        dev.pathweaver.config.PathWeaverConfig toggled =
            new dev.pathweaver.config.PathWeaverConfig();
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        RequestTarget target = RequestTarget.of(java.util.Set.of("same"), 8, false, 1, 32.0F);
        sink.register(key(1L, 3L, 17), nav, target);
        try {
            toggled.asyncEnabled = false;
            toggled.syncFallbackOnly = false;
            dev.pathweaver.config.PathWeaverConfig.set(toggled);
            assertEquals(EntityInstallSink.PendingDecision.PRESERVE,
                sink.pendingDecision(17, nav, target));
            assertEquals(EntityInstallSink.PendingDecision.SUPERSEDE,
                sink.pendingDecision(17, nav, target, true),
                "recompute/block-change invalidation must replace even same-target pending work");

            toggled.asyncEnabled = true;
            toggled.syncFallbackOnly = true;
            dev.pathweaver.config.PathWeaverConfig.set(toggled);
            assertEquals(EntityInstallSink.PendingDecision.PRESERVE,
                sink.pendingDecision(17, nav, target));
        } finally {
            dev.pathweaver.config.PathWeaverConfig.set(previous);
        }
        assertTrue(sink.isRegistered(17));
        assertEquals(0, nav.dones);
    }

    @Test void throwingDoneCallbackCannotEscapeTerminalCancellation() {
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav throwing = new FakeNav() {
            @Override public void pathweaver$onPathfindingDone() {
                dones++;
                throw new IllegalStateException("callback boom");
            }
        };

        sink.register(key(1L, 4L, 18), throwing);
        assertDoesNotThrow(() -> sink.cancel(18, throwing));
        assertFalse(sink.isRegistered(18));

        sink.register(key(1L, 5L, 18), throwing);
        assertDoesNotThrow(() -> sink.supersede(18));
        assertFalse(sink.isRegistered(18));

        sink.register(key(1L, 6L, 18), throwing);
        assertDoesNotThrow(() -> sink.install(key(1L, 6L, 18), dummyPath()));
        assertFalse(sink.isRegistered(18));
        assertEquals(3, throwing.dones);
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
