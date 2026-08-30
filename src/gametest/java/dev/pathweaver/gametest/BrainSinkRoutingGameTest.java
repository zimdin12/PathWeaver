package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.RequestOutcome;
import dev.pathweaver.config.PathWeaverConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Blocks;

/**
 * Feature C end to end: a brain mob's movement search leaves the server thread, it keeps the
 * destination it was given, and it arrives.
 *
 * <p>The walk target is set ONCE and never refreshed, and that is the whole point of this test.
 *
 * <p>An earlier version re-wrote {@code WALK_TARGET} to the same position every tick, to stop the
 * villager's own idle behaviours drifting it. That compensation hid the defect this file now exists
 * to catch. Deferring from inside {@code tryComputePath} made {@code checkExtraStartConditions} erase
 * {@code WALK_TARGET} — offsets 83-87 — on every tick the search was outstanding. The per-tick
 * rewrite put it straight back, so a healthy-looking test ran green over a mob that in the field
 * would have lost its destination and been claimed by whichever {@code absent(WALK_TARGET)} stroll
 * behaviour ran next in the same tick. A test that supplies its own trigger cannot prove the trigger
 * exists.
 *
 * <p>So: one write, an assertion on every tick that the memory is still there, and a requirement that
 * the mob actually arrives. Vanilla drops that memory only for an unreachable target or when the
 * behaviour stops, and this destination is eight blocks away across flat stone.
 */
public final class BrainSinkRoutingGameTest {
    public BrainSinkRoutingGameTest() {}

    @GameTest(maxTicks = 900)
    public void aVillagerKeepsItsTargetAcrossAnOffThreadSearchAndArrives(GameTestHelper helper) {
        Scenario[] scenario = new Scenario[1];
        helper.onEachTick(() -> {
            if (helper.getTick() < 20) return;
            if (scenario[0] == null) scenario[0] = new Scenario(helper);
            scenario[0].tick();
        });
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final PathWeaverConfig cfg;
        private final boolean oldEnabled;
        private final boolean oldBrainSink;

        private Mob villager;
        private BlockPos requestedTarget;
        private long parkedBefore;
        private int stage;
        private long stageStartedAt;
        private boolean cleaned;
        private boolean everHadPath;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.cfg = PathWeaverConfig.get();
            this.oldEnabled = cfg.enabled;
            this.oldBrainSink = cfg.brainSinkAsync;
        }

        void tick() {
            try {
                switch (stage) {
                    case 0 -> spawnAndSettle();
                    case 1 -> arm();
                    case 2 -> requireTargetSurvivesAndMobArrives();
                    default -> { }
                }
            } catch (Throwable failure) {
                cleanup();
                throw failure;
            }
        }

        /**
         * Spawn, then WAIT, and this is not padding.
         *
         * <p>A mob spawned a tick earlier has not settled onto the ground, so its very first search
         * legitimately finds no start node and vanilla returns null. That is vanilla's own behaviour
         * and nothing to do with deferral, but it is indistinguishable from the defect at the
         * assertion. This project has paid for that exact confusion once already, with a zombie.
         */
        private void spawnAndSettle() {
            cfg.enabled = true;
            cfg.brainSinkAsync = true;
            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            villager = helper.spawn(EntityType.VILLAGER, 2, 2, 3);
            villager.setOnGround(true);
            advance(1);
        }

        private void arm() {
            if (helper.getTick() - stageStartedAt < 40) return;
            check(villager.onGround(),
                "precondition: the villager must be settled, or its first search fails for reasons "
                    + "that have nothing to do with this feature");

            parkedBefore = PathWeaverRuntime.get().outcomeCount(RequestOutcome.PARKED_FOR_BRAIN);
            requestedTarget = helper.absolutePos(new BlockPos(10, 2, 3));
            // ONCE. Never refreshed. See the class comment.
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(requestedTarget, 0.5F, 0));
            check(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                "precondition: the walk target must actually be set, or asserting that it survives "
                    + "is vacuous");
            advance(2);
        }

        private void requireTargetSurvivesAndMobArrives() {
            long parked = PathWeaverRuntime.get().outcomeCount(RequestOutcome.PARKED_FOR_BRAIN)
                - parkedBefore;
            boolean walking = villager.getNavigation().getPath() != null;
            if (walking) everHadPath = true;

            // THE ASSERTION, scoped to the deferral window: from the tick the target was set until
            // the mob first holds a path. That window is exactly when a search is outstanding, and
            // vanilla has no reason to drop a reachable target there.
            //
            // It must NOT extend past that point. Vanilla legitimately erases WALK_TARGET when the
            // behaviour stops, and a path across open ground ends a couple of blocks short of the
            // requested block, so an unscoped version of this fired on a healthy build at three
            // blocks out -- on vanilla's own completion, not on the defect.
            if (!everHadPath) {
                check(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                    "the villager lost its walk target before it ever got a path, i.e. while the "
                        + "off-thread search was still outstanding. Deferring must not make vanilla "
                        + "forget where the mob was going: MoveToTargetSink is priority 1 and Brain "
                        + "iterates priorities ascending, so an absent(WALK_TARGET) stroll behaviour "
                        + "claims the mob on that same tick");
            }

            if (parked > 0 && everHadPath
                    && villager.blockPosition().closerThan(requestedTarget, 3.5)) {
                cleanup();
                stage = 3;
                helper.succeed();
                return;
            }
            if (helper.getTick() - stageStartedAt > 400) {
                throw helper.assertionException(
                    "villager did not complete an off-thread walk: parked=" + parked
                        + " everHadPath=" + everHadPath + " at " + villager.blockPosition()
                        + " target " + requestedTarget + ". parked=0 means nothing was ever "
                        + "offloaded, which is the feature failing outright");
            }
        }

        private void advance(int next) {
            stage = next;
            stageStartedAt = helper.getTick();
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.brainSinkAsync = oldBrainSink;
        }
    }
}
