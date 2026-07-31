package dev.pathweaver.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.pathweaver.duck.PWNavigation;
import java.util.List;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * When a search's epilogue is allowed to run, and against which evaluator.
 *
 * <p>Moving the prologue and epilogue onto the main thread is what let flying and amphibious mobs
 * path off-thread, and it introduced a race that none of the existing tests could see. The epilogue
 * is {@code NodeEvaluator.done()}, which clears the two caches the search reads and nulls the
 * context it reads them through. Superseding or stopping a navigation happens while that search may
 * still be running, so running the epilogue there tore the evaluator out from under its own worker.
 *
 * <p>The fix is that a discard no longer runs the epilogue; the drain path does, and the pool queues
 * a result only after the search callable has returned. These tests pin both halves — the deferral,
 * and the fact that the epilogue is owed to the exact request that prepared it rather than to the
 * navigation, which can already be on its next request by the time the first one lands.
 */
class EpilogueLifecycleTest {

    private static class CountingEvaluator extends WalkNodeEvaluator {
        int dones;

        @Override
        public void done() {
            dones++;
            // Deliberately not calling super: the base implementation needs a live prepared context.
        }
    }

    private static final class FakeNav implements PWNavigation {
        int rollbacks;
        private final Object uuid = new Object();
        private final Object world = new Object();

        @Override public void pathweaver$install(Path path) { }
        @Override public boolean pathweaver$stale(double x, double y, double z) { return false; }
        @Override public NavigationIdentity pathweaver$identity() {
            return new NavigationIdentity(uuid, world, "overworld", this, null, 0L);
        }
        @Override public void pathweaver$rollbackOptimisticTarget() { rollbacks++; }
        @Override public void pathweaver$abortFailedInstall() { }
    }

    private static RequestKey key(long token, int entityId) {
        return new RequestKey(1L, token, entityId);
    }

    @Test
    void noTerminalRouteRunsTheEpilogueWhileAWorkerMayStillOwnTheEvaluator() {
        record Route(String name, java.util.function.BiConsumer<EntityInstallSink, RequestKey> run) { }
        for (Route route : List.of(
                new Route("noPath", (sink, k) -> sink.noPath(k)),
                new Route("failed", (sink, k) -> sink.failed(k, new IllegalStateException("x"))),
                new Route("discard", (sink, k) -> sink.discard(k, RequestOutcome.ARRIVED_STALE)),
                new Route("supersede", (sink, k) -> sink.supersede(k.entityId())))) {
            EntityInstallSink sink = new EntityInstallSink();
            FakeNav nav = new FakeNav();
            CountingEvaluator evaluator = new CountingEvaluator();
            RequestKey requestKey = key(1L, 1);
            sink.setTick(100L);
            sink.register(requestKey, nav, RequestTarget.of(java.util.Set.of(), 0, false, 0, 0.0F));
            sink.armEpilogue(requestKey, evaluator);

            route.run().accept(sink, requestKey);

            assertEquals(0, evaluator.dones,
                "route '" + route.name() + "' finished the evaluator while its worker may still "
                    + "have been searching with it");
            assertEquals(1, nav.rollbacks,
                "route '" + route.name() + "' must still restore the pre-dispatch target");

            sink.runEpilogue(requestKey);
            assertEquals(1, evaluator.dones,
                "route '" + route.name() + "' must still owe exactly one epilogue");
        }
    }

    @Test
    void theEpilogueRunsAtMostOnceHoweverOftenItIsReleased() {
        EntityInstallSink sink = new EntityInstallSink();
        CountingEvaluator evaluator = new CountingEvaluator();
        RequestKey requestKey = key(2L, 2);
        sink.armEpilogue(requestKey, evaluator);

        sink.runEpilogue(requestKey);
        sink.runEpilogue(requestKey);
        sink.runEpilogue(requestKey);

        assertEquals(1, evaluator.dones, "a second release must not restore the mob's costs twice");
    }

    @Test
    void aSecondRequestDoesNotStealTheFirstRequestsEpilogue() {
        // The exact failure that made per-navigation ownership wrong: supersede leaves the first
        // evaluator owed, the navigation dispatches again next tick, and the first result then lands.
        EntityInstallSink sink = new EntityInstallSink();
        FakeNav nav = new FakeNav();
        CountingEvaluator first = new CountingEvaluator();
        CountingEvaluator second = new CountingEvaluator();
        RequestKey firstKey = key(1L, 7);
        RequestKey secondKey = key(2L, 7);
        sink.setTick(100L);

        sink.register(firstKey, nav, RequestTarget.of(java.util.Set.of(), 0, false, 0, 0.0F));
        sink.armEpilogue(firstKey, first);
        sink.supersede(7);

        sink.register(secondKey, nav, RequestTarget.of(java.util.Set.of(), 0, false, 0, 0.0F));
        sink.armEpilogue(secondKey, second);

        sink.runEpilogue(firstKey);
        assertEquals(1, first.dones, "the superseded request's own evaluator must be finished");
        assertEquals(0, second.dones,
            "finishing the first request must not touch the second request's live evaluator");

        sink.runEpilogue(secondKey);
        assertEquals(1, second.dones);
    }

    @Test
    void serverResetFinishesEveryOutstandingEpilogue() {
        // Safe here and only here: the runtime shuts the pool down before clearing the sink, so no
        // worker still owns an evaluator. Leaking these would leave mobs carrying search costs.
        EntityInstallSink sink = new EntityInstallSink();
        CountingEvaluator orphan = new CountingEvaluator();
        CountingEvaluator registered = new CountingEvaluator();
        FakeNav nav = new FakeNav();
        sink.setTick(100L);
        sink.armEpilogue(key(1L, 11), orphan);
        RequestKey live = key(2L, 12);
        sink.register(live, nav, RequestTarget.of(java.util.Set.of(), 0, false, 0, 0.0F));
        sink.armEpilogue(live, registered);

        sink.clear();

        assertEquals(1, orphan.dones, "an orphaned epilogue must not survive a server boundary");
        assertEquals(1, registered.dones);
    }

    @Test
    void anEpilogueThatThrowsIsStillConsumed() {
        EntityInstallSink sink = new EntityInstallSink();
        RequestKey requestKey = key(3L, 3);
        CountingEvaluator throwing = new CountingEvaluator() {
            @Override public void done() {
                super.done();
                throw new IllegalStateException("a mod's onPathfindingDone threw");
            }
        };
        sink.armEpilogue(requestKey, throwing);

        sink.runEpilogue(requestKey);
        sink.runEpilogue(requestKey);

        assertEquals(1, throwing.dones,
            "a throwing epilogue must not be retried against an evaluator that already restored");
    }
}
