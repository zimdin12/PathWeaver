package dev.pathweaver.async;

import dev.pathweaver.duck.PWNavigation;
import dev.pathweaver.gate.FabricLandPathRegistryLatch;
import net.minecraft.world.level.pathfinder.Path;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityInstallSinkTest {

    static class FakeNav implements PWNavigation {
        int rollbacks, aborts;
        boolean pathCleared;
        @Override public void pathweaver$abortFailedInstall() {
            aborts++; pathCleared = true; rollbacks++;
        }
        @Override public void pathweaver$rollbackOptimisticTarget() { rollbacks++; }

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

    @Test void lateLandProviderRegistrationMakesOnlyCapturedWalkResultStale() throws Exception {
        var publish = FabricLandPathRegistryLatch.class
            .getDeclaredMethod("publishHooksVerified", boolean.class);
        var reset = FabricLandPathRegistryLatch.class.getDeclaredMethod("resetForTests");
        publish.setAccessible(true);
        reset.setAccessible(true);
        reset.invoke(null);
        publish.invoke(null, true);
        try {
            EntityInstallSink sink = new EntityInstallSink();
            sink.setTick(1L);
            RequestTarget target = RequestTarget.of(java.util.Set.of("walk"), 8, false, 1, 32.0F);
            RequestKey walk = key(1L, 1L, 20);
            RequestKey swim = key(1L, 2L, 21);
            sink.register(walk, new FakeNav(), target, true);
            sink.register(swim, new FakeNav(), target, false);
            assertFalse(sink.isStale(walk, 1L, 0.0, 0.0, 0.0));
            assertFalse(sink.isStale(swim, 1L, 0.0, 0.0, 0.0));

            FabricLandPathRegistryLatch.beforeProviderMutation();

            assertTrue(sink.isStale(walk, 1L, 0.0, 0.0, 0.0),
                "R before I must discard the exact Walk request");
            assertFalse(sink.isStale(swim, 1L, 0.0, 0.0, 0.0),
                "land-provider registration must not invalidate unrelated Swim work");
        } finally {
            reset.invoke(null);
        }
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

    @Test void everyNonInstallRouteRestoresThePreDispatchTarget() {
        // Dispatch writes targetPos optimistically. If a route ends without installing a path and
        // does not undo that write, targetPos names the new target while path still holds the old
        // one; vanilla's reuse short-circuit then returns the stale path and reports success, so
        // the mob walks to the previous destination forever. Every terminal route must roll back.
        record Route(String name, java.util.function.BiConsumer<EntityInstallSink, RequestKey> run) { }
        for (Route route : List.of(
                new Route("noPath", (sink, key) -> sink.noPath(key)),
                new Route("failed", (sink, key) -> sink.failed(key, new IllegalStateException("x"))),
                new Route("discard", (sink, key) -> sink.discard(key)),
                new Route("supersede", (sink, key) -> sink.supersede(key.entityId())))) {
            EntityInstallSink sink = new EntityInstallSink();
            FakeNav nav = new FakeNav();
            RequestKey key = key(1L, 1L, 1);
            sink.setTick(100L);
            sink.register(key, nav);

            route.run().accept(sink, key);

            assertEquals(1, nav.rollbacks,
                "route '" + route.name() + "' must restore the pre-dispatch target");
            assertEquals(0, nav.installs, "route '" + route.name() + "' must not install a path");
            assertEquals(1, nav.dones, "route '" + route.name() + "' must still balance the callback");
        }
    }

    @Test void installThatSetsThePathThenThrowsIsFullyAborted() {
        // A foreign mixin can inject into vanilla moveTo and throw AFTER the path is set, so the
        // navigation may hold a new or partial path when the failure surfaces. Restoring only the
        // target would pair that path with the old target — the same mismatched invariant the
        // rollback exists to prevent. The install-failure route must clear the path as well.
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(30L);
        FakeNav throwing = new FakeNav() {
            @Override public void pathweaver$install(Path path) {
                installs++;
                this.path = new Object();      // vanilla already applied a path
                throw new IllegalStateException("foreign injection threw after moveTo");
            }
        };
        RequestKey key = key(1L, 9L, 12);
        sink.register(key, throwing);

        assertDoesNotThrow(() -> sink.install(key, dummyPath()));

        assertEquals(1, throwing.aborts,
            "a throwing install must abort, not merely restore the target");
        assertTrue(throwing.pathCleared,
            "the partially applied path must be cleared, not left paired with the old target");
        assertEquals(1, throwing.dones, "the callback must still be balanced");
        assertFalse(sink.isRegistered(12));
        assertTrue(sink.shouldForceSync(12, 31L));
    }

    @Test void nonInstallRoutesPreserveTheExistingPath() {
        // The counterpart: routes that never touched the path must NOT clear it, only restore
        // the target. Clearing here would throw away a still-valid path the mob is following.
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        RequestKey key = key(1L, 1L, 1);
        sink.setTick(100L);
        sink.register(key, nav);

        sink.noPath(key);

        assertEquals(1, nav.rollbacks, "the target must be restored");
        assertEquals(0, nav.aborts, "no-path must not abort the navigation");
        assertFalse(nav.pathCleared, "no-path must leave the existing path alone");
    }

    @Test void cooldownSweepClockResetsSoAFreshServerSweepsImmediately() {
        // clear() runs on server stop/start. If the sweep timestamp survived, a new server
        // starting near tick 0 would not sweep until it had been up as long as the previous one.
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(500_000L);
        FakeNav old = new FakeNav();
        RequestKey oldKey = key(1L, 1L, 1);
        sink.register(oldKey, old);
        sink.failed(oldKey, new IllegalStateException("x"));
        sink.shouldForceSync(1, 500_000L);      // advances the sweep clock to a large tick

        sink.clear();

        // New server: low tick numbers, one abandoned cooldown.
        sink.setTick(10L);
        FakeNav fresh = new FakeNav();
        RequestKey freshKey = key(2L, 1L, 2);
        sink.register(freshKey, fresh);
        sink.failed(freshKey, new IllegalStateException("x"));
        assertEquals(1, sink.cooldownEntryCount());

        sink.shouldForceSync(9999, 10L + 40L + 21L);

        assertEquals(0, sink.cooldownEntryCount(),
            "a fresh server must sweep on its own tick timeline, not the previous server's");
    }

    @Test void expiredCooldownsAreSweptEvenIfTheEntityNeverAsksAgain() {
        // A mob that fails a search and then dies never queries shouldForceSync again. Without a
        // sweep its cooldown entry stays forever, and entity ids are not reused, so the map grew
        // without bound on long-lived servers.
        EntityInstallSink sink = new EntityInstallSink();
        sink.setTick(100L);
        for (int entityId = 1; entityId <= 50; entityId++) {
            FakeNav nav = new FakeNav();
            RequestKey key = key(1L, entityId, entityId);
            sink.register(key, nav);
            sink.failed(key, new IllegalStateException("search failed"));
        }
        assertEquals(50, sink.cooldownEntryCount(), "all 50 cooldowns should be live initially");

        // A single unrelated query well after expiry must clear the abandoned entries.
        sink.shouldForceSync(9999, 100L + 40L + 21L);

        assertEquals(0, sink.cooldownEntryCount(),
            "expired cooldowns for entities that never returned must be swept");
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
        assertEquals(1, throwing.rollbacks,
            "a throwing install never installed a path, so the optimistic target must be undone");
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

    @Test void acceptedSameTargetDrainsAndBalancesAcrossMidFlightMasterOff() {
        dev.pathweaver.config.PathWeaverConfig previous =
            dev.pathweaver.config.PathWeaverConfig.get();
        dev.pathweaver.config.PathWeaverConfig toggled =
            new dev.pathweaver.config.PathWeaverConfig();
        toggled.enabled = true;
        dev.pathweaver.config.PathWeaverConfig.set(toggled);
        assertTrue(dev.pathweaver.config.PathWeaverConfig.get().enabled,
            "accept/register/enqueue phase must run with master eligibility ON");
        EntityInstallSink sink = new EntityInstallSink();
        ResultInstaller installer = new ResultInstaller();
        FakeNav nav = new FakeNav();
        RequestKey key = key(1L, 3L, 17);
        RequestTarget target = RequestTarget.of(java.util.Set.of("same"), 8, false, 1, 32.0F);
        sink.register(key, nav, target);
        installer.enqueue(key, 0L, PathOutcome.success(dummyPath()), 0.0, 0.0, 0.0);
        assertEquals(1, installer.pending(), "accepted worker result must be queued before OFF");
        try {
            toggled.enabled = false;
            dev.pathweaver.config.PathWeaverConfig.set(toggled);
            assertEquals(EntityInstallSink.PendingDecision.PRESERVE,
                sink.pendingDecision(17, nav, target));
            assertEquals(EntityInstallSink.PendingDecision.SUPERSEDE,
                sink.pendingDecision(17, nav, target, true),
                "recompute/block-change invalidation must replace even same-target pending work");

            assertDoesNotThrow(() -> installer.drain(sink),
                "master OFF must not strand accepted work in the real main-thread drain");
            assertEquals(0, installer.pending());
            assertFalse(sink.isRegistered(17));
            assertEquals(0, sink.inFlightCount(), "terminal drain must remove the accepted registration");
            assertEquals(1, nav.installs);
            assertEquals(1, nav.dones);
            assertDoesNotThrow(() -> installer.drain(sink));
            assertEquals(0, installer.pending(), "a second drain must remain empty");
            assertEquals(1, nav.installs, "a second drain must not reinstall the terminal result");
            assertEquals(1, nav.dones, "a second drain must not duplicate the terminal callback");
        } finally {
            dev.pathweaver.config.PathWeaverConfig.set(previous);
        }
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
