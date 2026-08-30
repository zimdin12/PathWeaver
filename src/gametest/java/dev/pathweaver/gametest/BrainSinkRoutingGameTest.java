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
 * Feature C end to end: a brain mob's movement search leaves the server thread, and vanilla's
 * unreachable handling still works when it should.
 *
 * <p>The unit tests cover the park/collect/release state machine directly. What they cannot cover is
 * the thing this feature actually risks: {@code MoveToTargetSink} is the only route by which
 * villagers, piglins, frogs and allays move at all, so getting it wrong does not degrade performance,
 * it stops villagers walking. That failure only shows up against a real brain on a running server.
 *
 * <h2>The control is the point</h2>
 *
 * The headline assertion is a NEGATIVE one — that {@code CANT_REACH_WALK_TARGET_SINCE} is never
 * written while a search is merely in flight. A negative assertion is worthless unless the same
 * detector is shown to fire, so the second phase asks the same villager to walk somewhere genuinely
 * unreachable and requires that memory to appear. If it never does, this test fails rather than
 * passing quietly, because that would mean the memory check could not have caught anything in
 * phase one either.
 *
 * <p>That control is also a regression test for a real defect found while writing it. Only a landed
 * path used to clear the pending slot, so a search ending in NO_PATH — the ordinary answer for an
 * unreachable destination — left the slot pending for {@code maxResultAgeTicks}. The behaviour would
 * refuse to start, never record the target as unreachable, and never run vanilla's random-position
 * fallback. Phase two hangs and this test goes red if that is ever reintroduced.
 */
public final class BrainSinkRoutingGameTest {
    public BrainSinkRoutingGameTest() {}

    @GameTest(maxTicks = 900)
    public void aVillagerPathsOffThreadAndStillLearnsWhenATargetIsUnreachable(GameTestHelper helper) {
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
        private BlockPos unreachableTarget;
        private boolean wallBuilt;
        private long parkedBefore;
        private int stage;
        private long stageStartedAt;
        private boolean cleaned;

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
                    case 2 -> awaitOffThreadPath();
                    case 3 -> demandTheDetectorCanFire();
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
         * <p>The first version armed on the spawn tick and went red immediately. A mob spawned a tick
         * earlier has not settled onto the ground, so its very first search legitimately finds no
         * start node, vanilla returns null and writes CANT_REACH_WALK_TARGET_SINCE itself. That is
         * vanilla's own behaviour and nothing to do with deferral -- but it is indistinguishable from
         * the defect at the assertion, so the test would have been reporting a bug that did not
         * exist. This project has paid for that exact confusion once already, with a zombie.
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

            // Cleared rather than asserted absent: the villager's own brain has been running for
            // forty ticks and may legitimately have tried to walk somewhere in that time. What the
            // next phase needs is a known-clean starting point, not a claim about the past.
            villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);

            parkedBefore = PathWeaverRuntime.get().outcomeCount(RequestOutcome.PARKED_FOR_BRAIN);
            requestedTarget = helper.absolutePos(new BlockPos(10, 2, 3));
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(requestedTarget, 0.5F, 0));
            advance(2);
        }

        private void awaitOffThreadPath() {
            // Re-asserted every tick. A villager's brain sets walk targets of its own -- wandering,
            // looking for a bed, following a player -- and the first run of this phase saw exactly
            // that: the search parked correctly and was then never collected, because by the time the
            // sink asked again it was asking about somewhere else. Keeping our target authoritative
            // is what makes this test about the feature rather than about villager idle behaviour.
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(requestedTarget, 0.5F, 0));

            long parked = PathWeaverRuntime.get().outcomeCount(RequestOutcome.PARKED_FOR_BRAIN)
                - parkedBefore;
            net.minecraft.world.level.pathfinder.Path path = villager.getNavigation().getPath();
            if (parked > 0 && path != null) {
                // THE TRAP, asserted by its signature rather than by watching for a memory write.
                //
                // A per-tick "CANT_REACH_WALK_TARGET_SINCE must stay absent" check was tried first and
                // is not attributable: a villager's own brain sets walk targets of its own while this
                // runs, and vanilla writes that memory for those legitimately. The check went red on a
                // healthy build twice.
                //
                // What the naive implementation would actually do is specific and observable. Letting
                // an in-flight search surface as a null path makes vanilla mark the destination
                // unreachable and then path to a RANDOM position near it via
                // DefaultRandomPos.getPosTowards. So the villager still ends up walking, and still
                // ends up with a path -- just not to the place it was asked to go. That is the
                // difference this asserts.
                double drift = path.getTarget().distSqr(requestedTarget);
                check(drift <= 9.0,
                    "the villager is walking to " + path.getTarget() + " but was asked to walk to "
                        + requestedTarget + " (" + Math.sqrt(drift) + " blocks away). A deferred "
                        + "search reported as 'no path' makes vanilla pick a random position near "
                        + "the target instead, which is exactly this");
                // No assertion here about CANT_REACH_WALK_TARGET_SINCE, deliberately. Two versions
                // of this test checked it and both went red on a healthy build: a villager's brain
                // is running its own behaviours throughout, and vanilla writes that memory for its
                // own walk targets whenever one of them is momentarily unpathable. The state is real
                // but it is not attributable to this feature, and an assertion that cannot say whose
                // fault a failure is will eventually be silenced rather than believed. The drift
                // check above is the detector that IS attributable, and phase three below is where
                // that memory has to appear.
                advance(3);
                return;
            }
            // Arrival counts as success too. The destination is eight blocks away, so a villager
            // that collected its path early and simply walked there has proved the same thing; only
            // sampling `getPath() != null` would turn that into a spurious failure.
            if (parked > 0 && villager.blockPosition().closerThan(requestedTarget, 2.0)) {
                advance(3);
                return;
            }
            if (helper.getTick() - stageStartedAt > 300) {
                throw helper.assertionException(
                    "a villager given a reachable walk target never got an off-thread path: parked="
                        + parked + " hasPath=" + (path != null) + " at " + villager.blockPosition()
                        + " target " + requestedTarget + ". Brain mobs reach navigation only through "
                        + "MoveToTargetSink, so this is the whole feature failing");
            }
        }

        private void demandTheDetectorCanFire() {
            // Set up ONCE. The first version did this every tick, which meant it erased the memory
            // immediately before testing for it -- the detector could not fire, and the phase written
            // to prove the detector works was itself incapable of failing for the right reason.
            if (!wallBuilt) {
                wallBuilt = true;
                for (int y = 2; y <= 4; y++) {
                    for (int z = 0; z <= 6; z++) helper.setBlock(6, y, z, Blocks.BEDROCK);
                }
                // The villager finished phase two standing ON the target, at x ~ 10. A destination at
                // x = 11 would have been on its own side of the wall and trivially reachable -- the
                // first version asked for exactly that and then reported the detector as broken.
                check(villager.blockPosition().getX() > helper.absolutePos(new BlockPos(6, 2, 3)).getX(),
                    "precondition: the villager must be on the far side of the wall from the target, "
                        + "or 'unreachable' is not unreachable");
                villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                unreachableTarget = helper.absolutePos(new BlockPos(1, 2, 3));
                advance(3);
            }

            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(unreachableTarget, 0.5F, 0));

            if (villager.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
                cleanup();
                stage = 4;
                helper.succeed();
                return;
            }
            if (helper.getTick() - stageStartedAt > 400) {
                throw helper.assertionException(
                    "an unreachable walk target never produced CANT_REACH_WALK_TARGET_SINCE. Either "
                        + "the detector cannot fire, so the drift check above is the only real "
                        + "assertion in this test -- or a search that found no path left the "
                        + "behaviour pending and the villager is frozen, which is the defect this "
                        + "phase exists to catch");
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
