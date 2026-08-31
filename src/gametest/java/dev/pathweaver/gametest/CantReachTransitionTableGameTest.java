package dev.pathweaver.gametest;

import dev.pathweaver.config.PathWeaverConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Blocks;

/**
 * The shipping gate this feature was required to pass, written out in full.
 *
 * <p>DESIGN.md section 10 and ROADMAP.md both state it in the same words: "a game test asserting the
 * whole CANT_REACH_WALK_TARGET_SINCE transition table — erased on canReach, set once with the
 * dispatch game time when unreachable, erased on arrival, never surviving a later successful search.
 * If that test cannot be written, the feature must not ship."
 *
 * <p>The feature shipped with one of those four covered, on the refused-dispatch path. A stated
 * must-not-ship condition being quietly passed over is worse than the gap it hides, so this is the
 * other three plus the pinning of the first.
 *
 * <p><strong>Why the gate exists, and it is not academic.</strong> That memory is read by
 * {@code SetWalkTargetFromBlockMemory}, which calls {@code villager.releasePoi(...)} once
 * {@code gameTime - since} exceeds its retry cooldown. A villager that keeps a memory it should have
 * lost <em>permanently loses its workstation or bed</em>, silently, with no crash and no log line.
 * The project's own docs call that the worst-shaped bug this mod could ship.
 *
 * <h2>STATUS: KNOWN FLAKY. This is a reproducer, not a release gate.</h2>
 *
 * <p>Measured in its own harness, 18 runs per arm: it fails 5/18 with {@code brainSinkAsync} ON and
 * 3/18 with it OFF. Fisher exact two-tailed p = 0.69 — no significant difference, so the flake is
 * NOT attributable to the feature. An earlier 0-of-8 control said otherwise and was quoted four
 * times; it had been measured on a materially different version of this test and did not survive
 * re-measuring.
 *
 * <p>The failing signature, from {@code BrainSinkDiagnostics}: the villager is alive, on the floor,
 * with PATH absent and WALK_TARGET present — both entry conditions satisfied — and yet
 * {@code MoveToTargetSink} is never evaluated at all across 800 ticks ({@code sc=0, life=none}).
 * The instrument is sound; other runs record {@code sc=3} and a start/stop lifecycle. Why the brain
 * skips this behaviour under those conditions is not established.
 *
 * <p>So this runs only under {@code -PcantReachHarness} and is deliberately NOT in any harness the
 * release depends on. Keeping it opt-in rather than deleting it: it is the only reproducer of the
 * signature, and a test kept out of the gate is honest where a deleted one is not. What it means for
 * the shipping gate DESIGN.md and ROADMAP.md state is recorded there — the gate is written and is
 * not yet reliable, which is not the same as met.
 *
 * <p>The walk target is re-asserted each tick here, and that is deliberately NOT the compensation
 * that hid an earlier defect. The subject of this test is the memory's transitions, so the
 * destination has to be the one under test rather than whatever the villager's idle behaviours
 * picked. That the target itself survives a deferral is a different property, proved separately in
 * {@code BrainSinkRoutingGameTest}.
 */
public final class CantReachTransitionTableGameTest {
    public CantReachTransitionTableGameTest() {}

    private static final int WALL_X = 6;

    @GameTest(maxTicks = 3200)
    public void everyTransitionOfTheCantReachMemoryHolds(GameTestHelper helper) {
        State s = new State();
        helper.onEachTick(() -> s.tick(helper));
    }

    private static final class State {
        private Mob villager;
        private BlockPos reachable;
        private BlockPos walledOff;
        private int stage;
        private long enteredStage;
        private long stampedAt = Long.MIN_VALUE;
        private boolean restored;
        /** Last few ticks of stage 2, dumped on the control failure so it can be diagnosed. */
        private final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
        private boolean oldBrainSink;

        void tick(GameTestHelper helper) {
            long tick = helper.getTick();
            if (tick < 20) return;
            if (villager == null) {
                build(helper);
                enteredStage = tick;
                return;
            }
            if (stage == 0 && tick - enteredStage < 40) return;

            var brain = villager.getBrain();
            boolean present = brain.hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);

            switch (stage) {
                case 0 -> beginReachable(helper, brain, tick);
                case 1 -> reachableMustNotMarkUnreachable(helper, brain, present, tick);
                case 2 -> unreachableStampsExactlyOnce(helper, brain, present, tick);
                case 3 -> aLaterSuccessfulSearchMustClearIt(helper, brain, present, tick);
                case 4 -> arrivalMustClearIt(helper, brain, present, tick);
                default -> { }
            }
        }

        private void build(GameTestHelper helper) {
            PathWeaverConfig cfg = PathWeaverConfig.get();
            oldBrainSink = cfg.brainSinkAsync;
            dev.pathweaver.brain.BrainSinkDiagnostics.setEnabled(true);
            cfg.enabled = true;
            cfg.brainSinkAsync = true;
            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
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
            // The pen is built HERE, not raised mid-test. Raising it later meant the setup
            // depended on which side of the line a free-running villager happened to be standing on,
            // and the control assertion -- that an unreachable destination CAN set the memory --
            // failed about one run in six or seven for that reason alone. Sealed from spawn, the
            // west half is unreachable for the whole test and there is no race to lose.
            for (int y = 2; y <= 4; y++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(WALL_X, y, z, Blocks.BEDROCK);
            }
            villager = helper.spawn(VanillaTypes.mob(VanillaTypes.VILLAGER), 9, 2, 3);
            villager.setOnGround(true);
            pathweaverStripBrainToMovementOnly(villager);
            reachable = helper.absolutePos(new BlockPos(10, 2, 3));
            walledOff = helper.absolutePos(new BlockPos(2, 2, 3));
        }

        private void beginReachable(GameTestHelper helper, net.minecraft.world.entity.ai.Brain<?> brain,
                                    long tick) {
            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            aim(brain, reachable);
            advance(1, tick);
        }

        /** TRANSITION 1: a destination the mob can reach must never leave the memory set. */
        private void reachableMustNotMarkUnreachable(GameTestHelper helper,
                                                     net.minecraft.world.entity.ai.Brain<?> brain,
                                                     boolean present, long tick) {
            aim(brain, reachable);
            if (present) {
                throw helper.assertionException(
                    "a reachable destination left CANT_REACH_WALK_TARGET_SINCE set. Vanilla erases "
                        + "it whenever the path canReach, and leaving it set is what eventually "
                        + "makes SetWalkTargetFromBlockMemory release the villager's POI");
            }
            // Only wall once the villager is provably EAST of the wall line. It is free-running
            // during this stage, and if it drifted west before the wall went up the "unreachable"
            // target would be on its own side -- so the control assertion below could never fire and
            // the run failed about one in three, on the assertion whose whole job is to prove the
            // detector works.
            if (tick - enteredStage > 40) {
                // Stop the navigation so the walled target is evaluated by a fresh START check
                // rather than by whatever state the behaviour happened to be in.
                villager.getNavigation().stop();
                advance(2, tick);
            }
        }

        /**
         * TRANSITION 2: set once, and left alone.
         *
         * <p>Rewriting the stamp every tick would keep {@code gameTime - since} pinned near zero, so
         * the retry countdown that releases an unreachable POI would never elapse and the villager
         * would hold a workstation it cannot reach forever.
         */
        private void unreachableStampsExactlyOnce(GameTestHelper helper,
                                                  net.minecraft.world.entity.ai.Brain<?> brain,
                                                  boolean present, long tick) {
            aim(brain, walledOff);
            if (!present) {
                if (trace.size() >= 12) trace.removeFirst();
                trace.addLast("t" + (tick - enteredStage)
                    + " at=" + villager.blockPosition().getX()
                    + " navPath=" + (villager.getNavigation().getPath() != null)
                    + " brainPATH=" + brain.hasMemoryValue(MemoryModuleType.PATH)
                    + " walkTgt=" + brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    + " pend=" + dev.pathweaver.PathWeaverRuntime.get().entitySink()
                        .hasPendingBrainSink(villager.getId(), walledOff)
                    + " reg=" + dev.pathweaver.PathWeaverRuntime.get().entitySink()
                        .isRegistered(villager.getId())
                    + " last=" + dev.pathweaver.brain.BrainSinkDiagnostics.last(villager.getId())
                    + " sc=" + dev.pathweaver.brain.BrainSinkDiagnostics.startChecks(villager.getId())
                    + " th=" + dev.pathweaver.brain.BrainSinkDiagnostics.tickHooks(villager.getId())
                    + " life=" + dev.pathweaver.brain.BrainSinkDiagnostics.lifecycle(
                        villager.getId())
                    + " alive=" + villager.isAlive()
                    + " removed=" + villager.isRemoved()
                    + " hp=" + villager.getHealth()
                    + " y=" + villager.blockPosition().getY());
            }
            if (present) {
                long since = brain.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                    .orElseThrow();
                if (stampedAt == Long.MIN_VALUE) {
                    stampedAt = since;
                } else if (since != stampedAt) {
                    throw helper.assertionException(
                        "CANT_REACH_WALK_TARGET_SINCE moved from " + stampedAt + " to " + since
                            + ". It must be stamped once and left alone, or the countdown that "
                            + "releases an unreachable POI never elapses");
                }
                if (tick - enteredStage > 20) {
                    wall(helper, Blocks.AIR);
                    villager.getNavigation().stop();
                    advance(3, tick);
                }
                return;
            }
            // 1400 ticks, not 700, and not 400 before that. The villager has to actually attempt
            // between the deferral, vanilla's stuck cooldown (up to 40 ticks per stop) and its own
            // idle behaviours competing for the walk target, that took longer than 400 about one run
            // in six. The assertion is that the detector FIRES, and it does; the budget was measuring
            // impatience rather than the property.
            if (tick - enteredStage > 1400) {
                throw helper.assertionException(
                    "a walled-off destination never set CANT_REACH_WALK_TARGET_SINCE, so every "
                        + "assertion here that depends on it being settable proves nothing. at="
                        + villager.blockPosition() + " target=" + walledOff
                        + " hasPath=" + (villager.getNavigation().getPath() != null)
                        + " walkTarget=" + brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)
                        + " brainPath=" + brain.hasMemoryValue(MemoryModuleType.PATH)
                        + " | trace: " + String.join(" ;; ", trace));
            }
        }

        /** TRANSITION 4: it must not survive a later search that succeeds. */
        private void aLaterSuccessfulSearchMustClearIt(GameTestHelper helper,
                                                       net.minecraft.world.entity.ai.Brain<?> brain,
                                                       boolean present, long tick) {
            // Aim somewhere ELSE, and far. The first version re-aimed at the same walled-off block
            // after opening the wall and then waited for the memory to clear -- but MoveToTargetSink
            // was already RUNNING, and tick() only re-paths when the target has drifted more than
            // two blocks (offsets 83-90), so no search happened and nothing re-evaluated
            // reachability. It failed IDENTICALLY with brainSinkAsync off, which is how I know it was
            // the test and not the feature. The property is about a later SUCCESSFUL SEARCH, so the
            // test has to cause one.
            aim(brain, reachable);
            if (!present) {
                advance(4, tick);
                return;
            }
            if (tick - enteredStage > 400) {
                throw helper.assertionException(
                    "CANT_REACH_WALK_TARGET_SINCE survived a later successful search. That is "
                        + "exactly the state SetWalkTargetFromBlockMemory turns into releasePoi(), "
                        + "so the villager silently loses its workstation or bed");
            }
        }

        /** TRANSITION 3: arriving clears it. */
        private void arrivalMustClearIt(GameTestHelper helper,
                                        net.minecraft.world.entity.ai.Brain<?> brain,
                                        boolean present, long tick) {
            aim(brain, villager.blockPosition());
            if (!present) {
                restore();
                helper.succeed();
                return;
            }
            if (tick - enteredStage > 200) {
                throw helper.assertionException(
                    "standing on the walk target did not clear CANT_REACH_WALK_TARGET_SINCE");
            }
        }

        /**
         * Leave the villager with ONE behaviour: the movement sink this gate is about.
         *
         * <p>This test was flaky, and the flakiness was structural rather than a defect in the
         * feature: a live villager brain runs idle strolling, look-at and its job-site and
         * meeting-point behaviours, all of which write WALK_TARGET. The gate then had to out-wait
         * whichever of them happened to win, which is why the stamping stage carried a 700-tick
         * budget and still failed about one run in six with a control that failed three in
         * eighteen. It was measuring the brain competing with itself.
         *
         * <p>Stripping the brain removes the competition instead of budgeting around it. What is
         * left is exactly the transition table DESIGN.md section 10 asks about: MoveToTargetSink,
         * a walk target, and the CANT_REACH memory.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void pathweaverStripBrainToMovementOnly(net.minecraft.world.entity.Mob mob) {
            net.minecraft.world.entity.ai.Brain brain = mob.getBrain();
            brain.removeAllBehaviors();
            brain.addActivity(
                net.minecraft.world.entity.schedule.Activity.CORE,
                com.google.common.collect.ImmutableList.of(
                    com.mojang.datafixers.util.Pair.of(
                        0, new net.minecraft.world.entity.ai.behavior.MoveToTargetSink())),
                java.util.Set.of(),
                java.util.Set.of());
            brain.setCoreActivities(
                java.util.Set.of(net.minecraft.world.entity.schedule.Activity.CORE));
            brain.useDefaultActivity();
        }

        private void aim(net.minecraft.world.entity.ai.Brain<?> brain, BlockPos target) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.5F, 0));
        }

        private void wall(GameTestHelper helper, net.minecraft.world.level.block.Block block) {
            for (int y = 2; y <= 4; y++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(WALL_X, y, z, block);
            }
        }

        private void advance(int next, long tick) {
            stage = next;
            enteredStage = tick;
        }

        private void restore() {
            if (restored) return;
            restored = true;
            PathWeaverConfig.get().brainSinkAsync = oldBrainSink;
        }
    }
}
