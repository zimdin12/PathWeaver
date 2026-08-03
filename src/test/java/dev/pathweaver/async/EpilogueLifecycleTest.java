package dev.pathweaver.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A gate a worker was authorized through: its evaluator may be in use right now. */
    private static SearchStartGate opened() {
        SearchStartGate gate = new SearchStartGate();
        gate.open();
        return gate;
    }

    /** A gate that was cancelled before any worker could read the evaluator. */
    private static SearchStartGate cancelled() {
        SearchStartGate gate = new SearchStartGate();
        gate.cancel();
        return gate;
    }

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
            sink.armEpilogue(requestKey, evaluator, opened());

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
        sink.armEpilogue(requestKey, evaluator, opened());

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
        sink.armEpilogue(firstKey, first, opened());
        sink.supersede(7);

        sink.register(secondKey, nav, RequestTarget.of(java.util.Set.of(), 0, false, 0, 0.0F));
        sink.armEpilogue(secondKey, second, opened());

        sink.runEpilogue(firstKey);
        assertEquals(1, first.dones, "the superseded request's own evaluator must be finished");
        assertEquals(0, second.dones,
            "finishing the first request must not touch the second request's live evaluator");

        sink.runEpilogue(secondKey);
        assertEquals(1, second.dones);
    }

    @Test
    void aServerResetThatCouldNotQuiesceWorkersAbandonsEpiloguesRatherThanRaceThem() {
        // Observed on a real server stop: the pool interrupts workers without waiting, so running
        // the epilogue here nulled an evaluator's mob while its own worker was still reading it and
        // the search died on an NPE. Dropping the epilogue costs nothing that survives a server
        // boundary; racing one does.
        EntityInstallSink sink = new EntityInstallSink();
        CountingEvaluator stillInUse = new CountingEvaluator();
        sink.armEpilogue(key(9L, 9), stillInUse, opened());

        sink.clear(false);

        assertEquals(0, stillInUse.dones,
            "an evaluator a worker may still own must not be finished from the main thread");
        sink.runEpilogue(key(9L, 9));
        assertEquals(0, stillInUse.dones, "the abandoned epilogue must not resurface later either");
    }

    @Test
    void aHardStopStillFinishesEpiloguesNoWorkerEverStarted() {
        // The abandonment above was over-broad. It dropped every owed epilogue, including ones whose
        // gate was never opened -- and a gate that was cancelled means no worker was ever authorized
        // to read that evaluator, so there is nothing to race.
        //
        // Dropping those is not a lost optimisation. AmphibiousNodeEvaluator.prepare() sets the mob's
        // WALKABLE cost to 6.0 and WATER_BORDER to 4.0 and only done() restores them, so an abandoned
        // epilogue leaves a drowned or axolotl carrying search-time costs for as long as it stays
        // loaded. That outlives the shutdown the abandonment was protecting, which makes it the more
        // damaging of the two failures.
        EntityInstallSink sink = new EntityInstallSink();
        CountingEvaluator neverStarted = new CountingEvaluator();
        CountingEvaluator maybeRunning = new CountingEvaluator();
        sink.armEpilogue(key(1L, 1), neverStarted, cancelled());
        sink.armEpilogue(key(2L, 2), maybeRunning, opened());

        sink.clear(false);

        assertEquals(1, neverStarted.dones,
            "no worker was authorized through this gate, so the mob must get its costs back");
        assertEquals(0, maybeRunning.dones,
            "an authorized gate still means a worker may be inside the search; do not race it");
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
        sink.armEpilogue(key(1L, 11), orphan, opened());
        RequestKey live = key(2L, 12);
        sink.register(live, nav, RequestTarget.of(java.util.Set.of(), 0, false, 0, 0.0F));
        sink.armEpilogue(live, registered, opened());

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
        sink.armEpilogue(requestKey, throwing, opened());

        sink.runEpilogue(requestKey);
        sink.runEpilogue(requestKey);

        assertEquals(1, throwing.dones,
            "a throwing epilogue must not be retried against an evaluator that already restored");
    }

    /**
     * An entity that still owes an epilogue must not be allowed to start a second search.
     *
     * <p>{@code aSecondRequestDoesNotStealTheFirstRequestsEpilogue} pins the same interleaving and
     * counts {@code done()} invocations, which is why it passed while the bug below was live: both
     * epilogues did run, in completion order, and the mob was still left corrupted.
     *
     * <p>{@code AmphibiousNodeEvaluator.prepare} saves the mob's WALKABLE and WATER_BORDER costs and
     * overwrites them with 6.0 and 4.0; {@code done} restores what it saved. Two prepares before
     * either done means the second saves 6.0/4.0, so whichever done runs last writes those back
     * permanently — and every later request then captures them as the originals. Axolotls, turtles,
     * frogs and drowned all use that evaluator, the malus is not serialised, and nothing is logged.
     */
    /** The save/restore family the guard exists for; Frog's evaluator extends this one. */
    private static final class CountingAmphibious
            extends net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator {
        int dones;
        CountingAmphibious() { super(false); }
        @Override public void done() { dones++; }
    }

    @Test
    void anEntityOwingAnEpilogueCannotStartASecondSearch() {
        EntityInstallSink sink = new EntityInstallSink();
        CountingAmphibious first = new CountingAmphibious();
        RequestKey r1 = key(1L, 1);

        sink.armEpilogue(r1, first, opened());
        assertTrue(sink.owesEpilogue(1),
            "the entity owes a done() the moment its prologue has run");

        // Superseding drops the registration but must NOT clear the debt: the worker may still be
        // inside the search, which is exactly why the epilogue was deferred in the first place.
        sink.supersede(1);
        assertTrue(sink.owesEpilogue(1),
            "supersede removes the registration; the owed epilogue outlives it, and dispatch has to "
                + "see that or it will run a second prepare against the same live mob");

        sink.runEpilogue(r1);
        assertFalse(sink.owesEpilogue(1),
            "once the epilogue has run the entity is clear to dispatch again");
    }

    @Test
    void aWalkEvaluatorDebtDoesNotBlockDispatch() {
        // Deliberately scoped. WalkNodeEvaluator's prepare/done are onPathfindingStart/Done hooks,
        // not a save/restore pair, so a second outstanding one cannot invert anything. Blocking those
        // too would cost a real dispatch -- no supersede-and-redispatch within a tick -- to prevent a
        // corruption walking mobs cannot suffer.
        EntityInstallSink sink = new EntityInstallSink();
        sink.armEpilogue(key(1L, 5), new CountingEvaluator(), opened());
        assertFalse(sink.owesEpilogue(5),
            "a walk-evaluator debt must not block dispatch; only the malus save/restore family does");
    }

    @Test
    void owedEpiloguesAreTrackedPerEntityNotGlobally() {
        // A debt for one mob must not stop a different mob dispatching, or one busy axolotl would
        // quietly force the whole server synchronous.
        EntityInstallSink sink = new EntityInstallSink();
        sink.armEpilogue(key(1L, 1), new CountingAmphibious(), opened());
        assertTrue(sink.owesEpilogue(1));
        assertFalse(sink.owesEpilogue(2), "a different entity owes nothing");
    }
}
