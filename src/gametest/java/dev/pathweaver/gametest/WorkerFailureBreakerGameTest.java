package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.PathRequest;
import dev.pathweaver.async.RequestOutcome;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.SafetyGate;
import dev.pathweaver.gate.WorkerFailureBreaker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.Set;

/**
 * The breaker, end to end, on a running server.
 *
 * <p>The unit tests prove each link separately: the pool reports a thrown search, the counter trips at
 * the threshold, the trip reaches {@code SafetyGate.isDenied}. What none of them can prove is the only
 * thing an operator cares about — that a real mob in a real world then stops dispatching, <em>and keeps
 * pathing normally</em>. The second half matters as much as the first: the breaker's entire safety
 * argument is that falling back to vanilla is free, and a fallback that left mobs unable to move would
 * be a far worse bug than the one it is guarding against.
 *
 * <p>The failures here go through the production pool rather than calling the breaker directly, so the
 * chain under test is worker catch → breaker → gate → dispatch refusal, which is the chain that has to
 * work in the shipped jar.
 */
public final class WorkerFailureBreakerGameTest {
    public WorkerFailureBreakerGameTest() {}

    @GameTest(maxTicks = 900)
    public void aTrippedFamilyStopsDispatchingAndKeepsWalking(GameTestHelper helper) {
        Scenario[] scenario = new Scenario[1];
        helper.onEachTick(() -> {
            if (helper.getTick() < 40) return;
            if (scenario[0] == null) scenario[0] = new Scenario(helper);
            scenario[0].tick();
        });
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final PathWeaverConfig cfg;
        private final boolean oldEnabled;
        private final int oldLimit;
        private final int oldWindow;
        private final Set<Class<?>> oldDenials;
        private Mob walker;
        private long dispatchBefore;
        private long breakerOpenBefore;
        private int stage;
        private boolean cleaned;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.cfg = PathWeaverConfig.get();
            this.oldEnabled = cfg.enabled;
            this.oldLimit = cfg.workerFailureLimit;
            this.oldWindow = cfg.workerFailureWindowTicks;
            synchronized (SafetyGate.deniedBySafety) {
                this.oldDenials = Set.copyOf(SafetyGate.deniedBySafety);
            }
        }

        void tick() {
            try {
                switch (stage) {
                    case 0 -> arm();
                    case 1 -> awaitTrip();
                    case 2 -> proveTheMobIsRefusedAndStillWalks();
                    default -> { }
                }
            } catch (Throwable failure) {
                cleanup();
                throw failure;
            }
        }

        private void arm() {
            cfg.enabled = true;
            cfg.workerFailureLimit = 3;
            cfg.workerFailureWindowTicks = 1200;
            // The harness ships a mixin into pathfinding, so the scan denies everything here. Clearing
            // that is what makes the rest of this test about the breaker rather than about the scan.
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
            }
            WorkerFailureBreaker.reset();

            check(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "precondition: with the scan's denials cleared, walk must dispatch -- otherwise every "
                    + "assertion below is true for the wrong reason");

            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }

            // Three real searches that throw, through the real pool, on real worker threads.
            for (int i = 0; i < 3; i++) {
                boolean accepted = PathWeaverRuntime.get().pool().submit(new PathRequest(
                    PathWeaverRuntime.get().nextRequestKey(-1 - i),
                    helper.getTick(),
                    () -> { throw new IllegalStateException("synthetic worker failure"); },
                    outcome -> { },
                    ignored -> { },
                    WalkNodeEvaluator.class));
                check(accepted, "the pool refused a submission, so nothing can throw on a worker");
            }
            stage = 1;
        }

        private void awaitTrip() {
            if (!SafetyGate.isDeniedByRuntimeFailure(WalkNodeEvaluator.class)) {
                if (helper.getTick() >= 600) {
                    throw helper.assertionException("three searches threw on workers and the family "
                        + "was never switched off; counted "
                        + WorkerFailureBreaker.windowedCount(WalkNodeEvaluator.class));
                }
                return;
            }
            check(!SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "the trip must reach the gate dispatch asks, not merely be recorded");

            walker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 2);
            walker.setOnGround(true);
            dispatchBefore = PathWeaverRuntime.get().dispatchedCount();
            breakerOpenBefore = PathWeaverRuntime.get().outcomeCount(RequestOutcome.BREAKER_OPEN);
            stage = 2;
        }

        private void proveTheMobIsRefusedAndStillWalks() {
            PathNavigation navigation = walker.getNavigation();
            BlockPos target = helper.absolutePos(new BlockPos(9, 2, 2));
            boolean accepted = navigation.moveTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);

            // The half that proves the safety argument. "Fall back to vanilla" has to mean the mob
            // still gets a path, computed synchronously, exactly as if this mod were not installed.
            check(accepted, "a mob in a switched-off family must still be able to move -- the whole "
                + "safety argument is that the fallback is vanilla behaviour, and a fallback that "
                + "left mobs unable to path would be worse than the race it guards against");
            check(navigation.getPath() != null,
                "and it must have a real path this tick, not next tick: a refused dispatch is "
                    + "synchronous by definition");

            check(PathWeaverRuntime.get().dispatchedCount() == dispatchBefore,
                "a switched-off family must not dispatch; got "
                    + (PathWeaverRuntime.get().dispatchedCount() - dispatchBefore) + " dispatch(es)");
            check(PathWeaverRuntime.get().outcomeCount(RequestOutcome.BREAKER_OPEN)
                    > breakerOpenBefore,
                "and the refusal must be counted. Without a row for it, a switched-off family shows "
                    + "up only as `dispatched` ceasing to rise, which is the vanishing setup failure "
                    + "0.6.0 had to fix");

            cleanup();
            stage = 3;
            helper.succeed();
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.workerFailureLimit = oldLimit;
            cfg.workerFailureWindowTicks = oldWindow;
            WorkerFailureBreaker.reset();
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
                SafetyGate.deniedBySafety.addAll(oldDenials);
            }
        }
    }
}
