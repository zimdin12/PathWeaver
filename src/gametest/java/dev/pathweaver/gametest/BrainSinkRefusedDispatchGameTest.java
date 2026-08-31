package dev.pathweaver.gametest;

import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.SafetyGate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * The brain-sink hook must be TRANSPARENT when dispatch is refused.
 *
 * <p>This runs in the default harness, which pins {@code AUDITED} and ships a mixin into pathfinding
 * so every movement family is denied. Dispatch therefore refuses every request here — which is the
 * point. The hook still runs on every brain mob on every tick regardless, so "the mod cannot offload
 * anything on this install" has to mean the villager behaves exactly as it would without PathWeaver,
 * not merely that nothing crashes.
 *
 * <p>This is the path the other brain-sink test cannot reach, because that one runs in the harness
 * where dispatch succeeds. It is also the path where an earlier defect lived: {@code null} was doing
 * double duty as "no route exists" and "nothing supplied", so a refusal that vanilla answered with
 * "no route" fell through and ran a second full synchronous search for the same destination.
 *
 * <p>What this test can and cannot show, stated plainly: it pins the BEHAVIOUR of the refusal path —
 * the mob still walks, and still gives up on an unreachable target. It cannot see the doubled search,
 * because two identical synchronous searches and one produce the same observable outcome. That fix
 * rests on the explicit flag, not on this.
 */
public final class BrainSinkRefusedDispatchGameTest {
    public BrainSinkRefusedDispatchGameTest() {}

    @GameTest(maxTicks = 900)
    public void aRefusedBrainSinkLeavesTheVillagerExactlyAsVanillaWouldHaveIt(GameTestHelper helper) {
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
        private BlockPos reachable;
        private int stage;
        private long stageStartedAt;
        private boolean cleaned;
        private boolean walled;

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
                    case 2 -> requireOrdinaryMovement();
                    case 3 -> requireOrdinaryGiveUp();
                    default -> { }
                }
            } catch (Throwable failure) {
                cleanup();
                throw failure;
            }
        }

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
            villager = helper.spawn(VanillaTypes.mob(VanillaTypes.VILLAGER), 2, 2, 3);
            villager.setOnGround(true);
            advance(1);
        }

        private void arm() {
            if (helper.getTick() - stageStartedAt < 40) return;

            // POLARITY. Without this the whole test is vacuous: if the scan were NOT denying walk,
            // dispatch would succeed and this would silently become a second copy of the other
            // brain-sink test rather than a witness for the refusal path.
            check(!SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "precondition: this harness must have the scan refusing walk, or 'dispatch refused' "
                    + "is not the situation under test");

            reachable = helper.absolutePos(new BlockPos(10, 2, 3));
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(reachable, 0.5F, 0));
            advance(2);
        }

        private void requireOrdinaryMovement() {
            // No assertion on dispatchedCount here, deliberately. It is a GLOBAL counter and this
            // harness runs its tests concurrently, so another test's dispatches move it -- a
            // mutation run failed on that line for a reason unrelated to the mutation, and it had
            // only been passing on timing. The polarity check in arm() is what establishes that
            // dispatch is refused, and it is attributable because SafetyGate is asked about this
            // mob's own family.
            if (villager.blockPosition().closerThan(reachable, 3.5)) {
                advance(3);
                return;
            }
            if (helper.getTick() - stageStartedAt > 300) {
                throw helper.assertionException(
                    "with dispatch refused the villager must walk exactly as vanilla would, but it "
                        + "is still at " + villager.blockPosition() + " heading for " + reachable
                        + ". The hook runs on every brain mob whether or not the mod can offload "
                        + "anything, so a refused install must be indistinguishable from no mod");
            }
        }

        private void requireOrdinaryGiveUp() {
            if (!walled) {
                walled = true;
                for (int y = 2; y <= 4; y++) {
                    for (int z = 0; z <= 6; z++) helper.setBlock(6, y, z, Blocks.BEDROCK);
                }
                check(villager.blockPosition().getX()
                        > helper.absolutePos(new BlockPos(6, 2, 3)).getX(),
                    "precondition: the villager must be on the far side of the wall, or the target "
                        + "below is not unreachable");
                villager.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(helper.absolutePos(new BlockPos(1, 2, 3)), 0.5F, 0));
                advance(3);
            }

            // Vanilla's own give-up bookkeeping must still run when we supplied its null. This is the
            // branch where null previously meant "nothing supplied" and a second search was issued.
            if (villager.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
                cleanup();
                stage = 4;
                helper.succeed();
                return;
            }
            if (helper.getTick() - stageStartedAt > 400) {
                throw helper.assertionException(
                    "an unreachable target never produced CANT_REACH_WALK_TARGET_SINCE with dispatch "
                        + "refused, so the hook is swallowing vanilla's unreachable handling on the "
                        + "one path where it does no work of its own");
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
