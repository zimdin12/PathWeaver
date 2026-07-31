package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.SafetyGate;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Live witness that flying and amphibious mobs really do path off-thread, and that the amphibious
 * mob gets its pathfinding costs back afterwards.
 *
 * <p>Everything else in this suite, and every benchmark, uses zombies and cod. Those exercise the
 * two families that already worked. The headline claim of 0.4.0 is about the four that did not, and
 * a claim measured only on the cases it did not change is not measured at all.
 *
 * <p>The malus assertions are the real content. The amphibious evaluator saves and overwrites three
 * of the mob's pathfinding costs in {@code prepare()} and restores two in {@code done()}, and 0.4.0
 * moves both of those onto the main thread while the search runs on a worker. If the prologue did
 * not run, the cost is never raised; if the epilogue is skipped or runs against a half-prepared
 * evaluator, the mob keeps a search cost forever. Neither failure produces a wrong path, an
 * exception, or a failed assertion anywhere else — the mob just quietly walks differently for the
 * rest of the session. So the cost is read before dispatch, while the request is in flight, and
 * after it lands.
 */
public final class NewEvaluatorFamilyRoutingGameTest {
    public NewEvaluatorFamilyRoutingGameTest() {}

    /** The amphibious evaluator overwrites this one with 6.0F for the duration of a search. */
    private static final PathType WATCHED = PathType.WALKABLE;
    private static final float SEARCH_TIME_WALKABLE_COST = 6.0F;

    @GameTest(maxTicks = 1000)
    public void flyingAndAmphibiousMobsDispatchAndGetTheirMalusBack(GameTestHelper helper) {
        Scenario[] scenario = new Scenario[1];
        helper.onEachTick(() -> {
            // Let the registered routing tests finish with the shared config and gate first.
            if (helper.getTick() < 480) return;
            if (scenario[0] == null) scenario[0] = new Scenario(helper);
            scenario[0].tick();
        });
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final PathWeaverConfig cfg;
        private final boolean oldEnabled;
        private final boolean oldModded;
        private final int oldTolerance;
        private Mob flyer;
        private Mob amphibian;
        private PathNavigation flyerNav;
        private PathNavigation amphibianNav;
        private float malusBeforeDispatch;
        private long installBefore;
        private int stage;
        private boolean cleaned;
        private java.util.Set<Class<?>> oldDenials = java.util.Set.of();

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.cfg = PathWeaverConfig.get();
            this.oldEnabled = cfg.enabled;
            this.oldModded = cfg.allowModdedMobAsync;
            this.oldTolerance = cfg.repathToleranceBlocks;
        }

        void tick() {
            try {
                if (stage == 0) begin();
                else if (stage == 1) awaitInstalls();
            } catch (Throwable failure) {
                cleanup();
                throw failure;
            }
        }

        private void begin() {
            // This JVM's own test mixin config targets pathfinding, so the scan denies every family
            // here by design. Clearing the denials isolates what this test is about — whether the
            // allowlist admits these two families at all — from what the scan decided about this
            // particular JVM. The sibling routing tests do the same for the same reason.
            synchronized (SafetyGate.deniedBySafety) {
                oldDenials = java.util.Set.copyOf(SafetyGate.deniedBySafety);
                SafetyGate.deniedBySafety.clear();
            }
            check(SafetyGate.isAllowed(FlyNodeEvaluator.class),
                "the flying evaluator must be admitted once its randomness is thread-confined");
            check(SafetyGate.isAllowed(AmphibiousNodeEvaluator.class),
                "the amphibious evaluator must be admitted once its malus writes run on the main thread");

            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }

            amphibian = helper.spawnWithNoFreeWill(EntityType.DROWNED, 1, 2, 2);
            amphibian.setOnGround(true);
            amphibianNav = amphibian.getNavigation();
            flyer = helper.spawnWithNoFreeWill(EntityType.BEE, 1, 4, 4);
            flyerNav = flyer.getNavigation();

            // Assert the subjects really are the families under test. Spawning a mob that turned out
            // to use the ordinary walk evaluator would pass everything below and prove nothing.
            check(evaluatorOf(amphibianNav) instanceof AmphibiousNodeEvaluator,
                "the drowned must navigate with the amphibious evaluator, not " + named(amphibianNav));
            check(evaluatorOf(flyerNav) instanceof FlyNodeEvaluator,
                "the bee must navigate with the flying evaluator, not " + named(flyerNav));

            cfg.enabled = true;
            cfg.allowModdedMobAsync = false;
            cfg.repathToleranceBlocks = 0;

            malusBeforeDispatch = amphibian.getPathfindingMalus(WATCHED);
            check(malusBeforeDispatch != SEARCH_TIME_WALKABLE_COST,
                "the drowned already carries the search-time cost, so restoring it cannot be observed");

            long dispatchBefore = counter("dispatched");
            installBefore = counter("installed");

            BlockPos amphibianTarget = helper.absolutePos(new BlockPos(9, 2, 2));
            check(amphibianNav.moveTo(amphibianTarget.getX() + 0.5, amphibianTarget.getY(),
                    amphibianTarget.getZ() + 0.5, 1.0),
                "the amphibious move must be accepted");
            check(amphibian.getPathfindingMalus(WATCHED) == SEARCH_TIME_WALKABLE_COST,
                "the amphibious prologue did not run on the main thread before dispatch: cost is "
                    + amphibian.getPathfindingMalus(WATCHED));

            BlockPos flyerTarget = helper.absolutePos(new BlockPos(9, 5, 4));
            check(flyerNav.moveTo(flyerTarget.getX() + 0.5, flyerTarget.getY(),
                    flyerTarget.getZ() + 0.5, 1.0),
                "the flying move must be accepted");

            check(counter("dispatched") == dispatchBefore + 2,
                "both new families must contribute a real dispatch each, got "
                    + (counter("dispatched") - dispatchBefore));
            check(PathWeaverRuntime.get().entitySink().isRegistered(amphibian.getId()),
                "the amphibious request did not create a worker registration");
            check(PathWeaverRuntime.get().entitySink().isRegistered(flyer.getId()),
                "the flying request did not create a worker registration");

            assertPrologueIsolatesTheSharedCache();
            stage = 1;
        }

        /**
         * The prologue builds the search's context on the main thread, and that constructor grabs
         * the level's shared {@code PathTypeCache} unless something substitutes a confined one. The
         * worker then writes through whatever it got.
         *
         * <p>Nothing else in this suite would notice the isolation lapsing: the search still returns
         * a correct path. It just corrupts a structure that synchronous mobs read too, silently and
         * probabilistically. So both directions are asserted — confined inside the prologue scope
         * the dispatch path uses, and still shared outside it, because breaking ordinary synchronous
         * pathfinding to fix this would be its own regression.
         */
        private void assertPrologueIsolatesTheSharedCache() {
            Object shared = helper.getLevel().getPathTypeCache();

            boolean outer = dev.pathweaver.async.PathWeaverThread.enterAsyncPrologue();
            Object confined;
            try {
                confined = cacheOf(new PathfindingContext(helper.getLevel(), amphibian));
            } finally {
                dev.pathweaver.async.PathWeaverThread.exitAsyncPrologue(outer);
            }
            check(confined != null && confined != shared,
                "a context built for a worker took the level's SHARED PathTypeCache; the worker "
                    + "would write through it while synchronous mobs read it");

            Object synchronousCache = cacheOf(new PathfindingContext(helper.getLevel(), amphibian));
            check(synchronousCache == shared,
                "an ordinary synchronous search stopped using the level's shared cache");
        }

        private Object cacheOf(PathfindingContext context) {
            for (Field field : PathfindingContext.class.getDeclaredFields()) {
                if (!PathTypeCache.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(context);
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    throw helper.assertionException("could not read the context's path-type cache");
                }
            }
            throw helper.assertionException("PathfindingContext no longer holds a PathTypeCache");
        }

        private void awaitInstalls() {
            boolean settled = !PathWeaverRuntime.get().entitySink().isRegistered(amphibian.getId())
                && !PathWeaverRuntime.get().entitySink().isRegistered(flyer.getId());
            if (!settled) {
                if (helper.getTick() >= 940) {
                    throw helper.assertionException("a new-family request never reached a terminal "
                        + "state; installed=" + (counter("installed") - installBefore));
                }
                return;
            }

            // Terminal covers install and discard alike, and the epilogue is owed on both paths.
            // Asserting the restore only after a successful install would leave the discard route --
            // the one that actually leaks state -- untested.
            check(amphibian.getPathfindingMalus(WATCHED) == malusBeforeDispatch,
                "the amphibious epilogue did not restore the mob's cost: expected "
                    + malusBeforeDispatch + ", found " + amphibian.getPathfindingMalus(WATCHED));

            cleanup();
            stage = 2;
            helper.succeed();
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.allowModdedMobAsync = oldModded;
            cfg.repathToleranceBlocks = oldTolerance;
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
                SafetyGate.deniedBySafety.addAll(oldDenials);
            }
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }

        private String named(PathNavigation navigation) {
            NodeEvaluator evaluator = evaluatorOf(navigation);
            return evaluator == null ? "<none>" : evaluator.getClass().getName();
        }
    }

    /** The evaluator a navigation really holds, read from the field the search reads. */
    private static NodeEvaluator evaluatorOf(PathNavigation navigation) {
        for (Class<?> type = navigation.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!NodeEvaluator.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    return (NodeEvaluator) field.get(navigation);
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    return null;
                }
            }
        }
        return null;
    }

    private static long counter(String name) {
        return switch (name) {
            case "dispatched" -> PathWeaverRuntime.get().dispatchedCount();
            case "installed" -> PathWeaverRuntime.get().installedCount();
            default -> throw new AssertionError("unknown PathWeaver counter: " + name);
        };
    }
}
