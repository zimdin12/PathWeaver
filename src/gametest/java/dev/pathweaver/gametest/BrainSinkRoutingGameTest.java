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

    /**
     * Vanilla refuses to path for a mob that is already at its walk target, and erases the memory.
     *
     * <p>The hook sits above that guard, so it has to reproduce it. Without the reproduction the mod
     * dispatched a full A* to a block the mob was standing on, and — because the deferral cancels
     * before vanilla's body runs — withheld the arrival erase for a tick, blocking every
     * {@code absent(WALK_TARGET)} behaviour for that tick. That is a milder form of the exact defect
     * this file was written to catch.
     *
     * <p>Observable and attributable: with the guard, vanilla runs and clears WALK_TARGET; without
     * it, the deferral keeps the memory alive while a pointless search is in flight.
     */
    @GameTest(maxTicks = 400)
    public void anArrivedMobIsNotSentPathfindingForABlockItIsStandingOn(GameTestHelper helper) {
        int[] armedAt = {-1};
        Mob[] mob = new Mob[1];
        BlockPos[] armedPos = new BlockPos[1];
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick < 20) return;
            if (mob[0] == null) {
                for (int x = 0; x <= 8; x++) {
                    for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
                }
                // A RIM. Without it the villager wanders off the platform during the settle,
                // falls, and can never reach anything -- observed as roughly one failure in six,
                // with the mob two blocks below the floor. A test that fails at random gets
                // believed when it is green and ignored when it is red.
                for (int y = 2; y <= 3; y++) {
                    for (int x = 0; x <= 8; x++) {
                        helper.setBlock(x, y, 0, Blocks.BEDROCK);
                        helper.setBlock(x, y, 6, Blocks.BEDROCK);
                    }
                    for (int z = 0; z <= 6; z++) {
                        helper.setBlock(0, y, z, Blocks.BEDROCK);
                        helper.setBlock(8, y, z, Blocks.BEDROCK);
                    }
                }
                mob[0] = helper.spawn(EntityType.VILLAGER, 3, 2, 3);
                mob[0].setOnGround(true);
                return;
            }
            if (armedAt[0] < 0) {
                if (tick < 60) return;
                // Its own block. reachedTarget() is true immediately.
                armedPos[0] = mob[0].blockPosition();
                mob[0].getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(armedPos[0], 0.5F, 0));
                armedAt[0] = (int) tick;
                return;
            }

            // THE ASSERTION, and it is about dispatch rather than about timing.
            //
            // A tick-budget version of this was tried twice and the mutation that deletes the guard
            // survived both. Deleting it costs about one tick: the pointless search lands, gets
            // collected, and vanilla erases the memory a tick or two later anyway -- so at a budget
            // of 100 and again at 4, the test could not tell the two builds apart. Tightening
            // further would only have made it flaky at the resolution of entity-tick ordering.
            //
            // What is unambiguous is that no search should exist AT ALL for a mob standing on its
            // destination. The slot is per-entity and per-destination, so this is attributable.
            check(helper, !PathWeaverRuntime.get().entitySink()
                    .hasPendingBrainSink(mob[0].getId(), armedPos[0]),
                "a search was dispatched for a mob already standing on its walk target; vanilla "
                    + "computes nothing there (offsets 39-50 jump straight to the arrival erase)");

            if (!mob[0].getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                helper.succeed();
                return;
            }
            if (tick - armedAt[0] > 40) {
                throw helper.assertionException(
                    "a mob standing on its own walk target still holds WALK_TARGET after "
                        + (tick - armedAt[0]) + " ticks; vanilla erases it at offsets 83-87 on the "
                        + "next evaluation without computing anything");
            }
        });
    }

    /**
     * A mob whose destination is rewritten every tick must still move.
     *
     * <p>This is the {@code AnimalPanic} shape, and it is the reason the deferral carries a liveness
     * bound. {@code AnimalPanic.tick} overwrites WALK_TARGET with a FRESH random position on every
     * tick the navigation is idle, with no {@code absent(WALK_TARGET)} gate. Deferring leaves the mob
     * with no path, so {@code isDone()} stays true, so the destination re-rolls, so the answer that
     * eventually parks is never for the question now being asked. Without the bound the animal stands
     * still for the whole panic while dispatching one full A* per tick — strictly more pathfinding
     * than vanilla does, in a mod that exists to do less. For a burning mob, panic is how it reaches
     * water.
     *
     * <p>The test does not set anything on fire; it reproduces the mechanism directly, which is the
     * part that belongs to this mod.
     */
    @GameTest(maxTicks = 600)
    public void aMobWhoseTargetIsRewrittenEveryTickStillMoves(GameTestHelper helper) {
        Mob[] mob = new Mob[1];
        BlockPos[] start = new BlockPos[1];
        int[] armedAt = {-1};
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick < 20) return;
            if (mob[0] == null) {
                for (int x = 0; x <= 12; x++) {
                    for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
                }
                // A RIM. Without it the villager wanders off the platform during the settle,
                // falls, and can never reach anything -- observed as roughly one failure in six,
                // with the mob two blocks below the floor. A test that fails at random gets
                // believed when it is green and ignored when it is red.
                for (int y = 2; y <= 3; y++) {
                    for (int x = 0; x <= 12; x++) {
                        helper.setBlock(x, y, 0, Blocks.BEDROCK);
                        helper.setBlock(x, y, 6, Blocks.BEDROCK);
                    }
                    for (int z = 0; z <= 6; z++) {
                        helper.setBlock(0, y, z, Blocks.BEDROCK);
                        helper.setBlock(12, y, z, Blocks.BEDROCK);
                    }
                }
                mob[0] = helper.spawn(EntityType.VILLAGER, 2, 2, 3);
                mob[0].setOnGround(true);
                return;
            }
            if (armedAt[0] < 0) {
                if (tick < 60) return;
                armedAt[0] = (int) tick;
                start[0] = mob[0].blockPosition();
            }

            // A DIFFERENT destination every tick, exactly as AnimalPanic does.
            int step = (int) ((tick - armedAt[0]) % 5);
            mob[0].getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(helper.absolutePos(new BlockPos(8 + (step % 4), 2, 2 + (step % 4))),
                    1.0F, 0));

            if (!mob[0].blockPosition().closerThan(start[0], 2.5)) {
                helper.succeed();
                return;
            }
            if (tick - armedAt[0] > 200) {
                throw helper.assertionException(
                    "the mob has not moved in 200 ticks while its walk target was rewritten every "
                        + "tick. Deferring on a destination that keeps changing means the parked "
                        + "answer is never the one being asked for, so without a liveness bound the "
                        + "mob never gets a path at all -- and it dispatches a search every tick "
                        + "while standing still. at=" + mob[0].blockPosition()
                        + " start=" + start[0]
                        + " hasPath=" + (mob[0].getNavigation().getPath() != null)
                        + " walkTarget=" + mob[0].getBrain()
                            .hasMemoryValue(MemoryModuleType.WALK_TARGET));
            }
        });
    }

    // NOT COVERED HERE: the tick() re-path route.
    //
    // Two game tests for it were written and both deleted, because neither was attributable. A
    // pending brain-sink slot observed while a mob is walking can belong to a dispatch the START
    // check made for a destination the mob has since drifted away from -- so the mutation that makes
    // the tick hook inert survived both, first keyed on the navigation's path and then on the brain's
    // PATH memory. Keeping a test that cannot fail for the right reason would manufacture
    // confidence, which is worse than the gap.
    //
    // The route's PRESENCE is pinned structurally instead, by
    // dev.pathweaver.mixin.MoveToTargetSinkContractTest, which does kill that mutation. Its
    // BEHAVIOUR once taken is genuinely uncovered.

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) throw helper.assertionException(message);
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
        private int rearms;

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
            // A RIM. Without it the villager wanders off the platform during the settle,
            // falls, and can never reach anything -- observed as roughly one failure in six,
            // with the mob two blocks below the floor. A test that fails at random gets
            // believed when it is green and ignored when it is red.
            for (int y = 2; y <= 3; y++) {
                for (int x = 0; x <= 12; x++) {
                    helper.setBlock(x, y, 0, Blocks.BEDROCK);
                    helper.setBlock(x, y, 6, Blocks.BEDROCK);
                }
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(0, y, z, Blocks.BEDROCK);
                    helper.setBlock(12, y, z, Blocks.BEDROCK);
                }
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

            // Start from an idle navigation, and assert it. everHadPath latches on ANY path the
            // navigation holds, and a villager forty ticks after spawn is often already walking one
            // of its own idle strolls -- VillageBoundRandomStroll is in the villager IDLE package,
            // which is the default activity. Latching on that path skips the assertion this whole
            // file exists for, and the run then passes on the arrival check alone.
            villager.getNavigation().stop();
            check(villager.getNavigation().isDone(),
                "precondition: the navigation must be idle, or everHadPath latches on a stroll path "
                    + "the villager already had and the survival assertion never runs");

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
            // Scoped to ticks where a search for THIS destination is actually outstanding.
            //
            // "must survive until the mob first holds a path" was too strong and failed about one run
            // in six on a healthy build: after a deferral the synchronous fallback can hit a
            // transient no-path -- a villager momentarily off the ground -- and vanilla then erases
            // the target itself, which is its own behaviour and not ours. Asking only about the
            // deferred window keeps the assertion pointed at the thing this mod does, and it still
            // kills the mutation that erases on the defer branch, because that erase happens with the
            // slot pending.
            boolean deferredRightNow = PathWeaverRuntime.get().entitySink()
                .hasPendingBrainSink(villager.getId(), requestedTarget);
            if (!everHadPath && deferredRightNow) {
                check(villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET),
                    "the villager lost its walk target while an off-thread search for that exact "
                        + "destination was outstanding. Deferring must not make vanilla "
                        + "forget where the mob was going: MoveToTargetSink is priority 1 and Brain "
                        + "iterates priorities ascending, so an absent(WALK_TARGET) stroll behaviour "
                        + "claims the mob on that same tick. at="
                        + villager.blockPosition() + " target=" + requestedTarget
                        + " navPath=" + (villager.getNavigation().getPath() != null));
            }

            // If vanilla dropped the target for its own reason -- a transient no-path while the mob
            // was momentarily off the ground -- re-arm and carry on. This is NOT the compensation
            // that hid the original defect: that one rewrote the target every tick unconditionally,
            // which is what made the erase invisible. This re-arms only when no search is pending,
            // so the strict assertion above still sees every deferred tick, and it is bounded so a
            // mob that keeps losing its target fails rather than looping.
            if (!deferredRightNow
                    && !villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    && !villager.blockPosition().closerThan(requestedTarget, 3.5)) {
                if (++rearms > 2) {
                    throw helper.assertionException(
                        "the villager lost its walk target " + rearms + " times without a search "
                            + "pending; that is more than a transient no-path explains");
                }
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(requestedTarget, 0.5F, 0));
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
