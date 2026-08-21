package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.CompatibilityTier;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.ForeignMixinScanner;
import dev.pathweaver.gate.SafetyGate;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Live witness for the configuration this mod actually ships: {@code compatibilityTier=UNSAFE}, in
 * force at scan time, on an install where the scan really did deny something.
 *
 * <p>This existed as a gap and is worth naming. Every other harness pins {@code AUDITED} because
 * every other harness exists to witness the gate <em>closing</em>. That left the shipped default —
 * the entire content of the 0.5.0 release, and the state every new user starts in — as the one
 * configuration with no end-to-end coverage at all. The evidence for it was a maintainer's own
 * client log, which is real evidence but not a regression test: it cannot fail a build.
 *
 * <p>The tier cannot be set from inside a game test, because it is frozen when the startup scan
 * publishes its policy — long before any test runs. So this harness is pinned on disk by
 * {@code -PunsafeTierHarness}, and the first thing asserted here is that the pin worked.
 *
 * <p><strong>Non-vacuity is the whole difficulty.</strong> At {@code UNSAFE} almost every assertion
 * one would like to make is trivially true, so a careless version of this test passes on an install
 * where nothing was ever denied and proves nothing. It is therefore written to fail unless the scan
 * genuinely had something to waive: this harness deliberately ships
 * {@code pathweaver-gametest.mixins.json}, which mixes into {@code PathNavigation} and so denies
 * every movement family. The waiver is only observable because the denial is real.
 */
public final class UnsafeTierWaiverGameTest {
    public UnsafeTierWaiverGameTest() {}

    @GameTest(maxTicks = 900)
    public void theUnsafeTierWaivesRealDenialsAndStillDispatches(GameTestHelper helper) {
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
        private final int oldTolerance;
        private Mob walker;
        private long installBefore;
        private int stage;
        private boolean cleaned;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.cfg = PathWeaverConfig.get();
            this.oldEnabled = cfg.enabled;
            this.oldTolerance = cfg.repathToleranceBlocks;
        }

        void tick() {
            try {
                if (stage == 0) begin();
                else if (stage == 1) awaitInstall();
            } catch (Throwable failure) {
                cleanup();
                throw failure;
            }
        }

        private void begin() {
            // 1. The pin worked. Everything below is trivially true at UNSAFE and trivially false at
            //    AUDITED, so without this the result says nothing about either tier.
            check(cfg.compatibilityTier == CompatibilityTier.UNSAFE,
                "harness must be pinned to compatibilityTier=UNSAFE, found " + cfg.compatibilityTier);
            check(cfg.bypassesCompatibilityScan(),
                "the frozen policy must report the scan as bypassed; the tier is read at scan time, "
                    + "so a config file written after startup would leave this false");

            // 2. There was genuinely something to waive. This is the assertion that stops the test
            //    passing vacuously on an install where nothing was denied in the first place.
            List<String> blockers = ForeignMixinScanner.blockingModIds();
            check(!blockers.isEmpty(),
                "no mod was denied, so a waiver cannot be observed and this witness proves nothing. "
                    + "This harness must ship a mixin into sensitive pathfinding code");

            // 3. The waiver actually cleared the denials rather than merely being configured.
            {
                check(SafetyGate.snapshotDenials().isEmpty(),
                    "the unsafe tier did not clear the denial set; " + blockers.size()
                        + " mod(s) denied and " + SafetyGate.denialCount()
                        + " family/families are still refused: " + SafetyGate.snapshotDenials());
            }
            check(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "the walk evaluator is still refused after the denial set was cleared, so something "
                    + "other than the scan is holding the gate shut");

            // 4. And dispatch really happens, which is the only thing an operator can observe.
            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            cfg.enabled = true;
            cfg.repathToleranceBlocks = 0;

            walker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 2);
            walker.setOnGround(true);
            PathNavigation navigation = walker.getNavigation();

            long dispatchBefore = PathWeaverRuntime.get().dispatchedCount();
            installBefore = PathWeaverRuntime.get().installedCount();

            BlockPos target = helper.absolutePos(new BlockPos(9, 2, 2));
            check(navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                "the move must be accepted");
            check(PathWeaverRuntime.get().dispatchedCount() == dispatchBefore + 1,
                "a mob whose family the scan denied and the tier waived must still dispatch; got "
                    + (PathWeaverRuntime.get().dispatchedCount() - dispatchBefore) + " dispatches");
            check(PathWeaverRuntime.get().entitySink().isRegistered(walker.getId()),
                "the request did not create a worker registration");

            stage = 1;
        }

        private void awaitInstall() {
            if (PathWeaverRuntime.get().entitySink().isRegistered(walker.getId())) {
                if (helper.getTick() >= 700) {
                    throw helper.assertionException("the waived request never reached a terminal "
                        + "state; installed="
                        + (PathWeaverRuntime.get().installedCount() - installBefore));
                }
                return;
            }
            check(PathWeaverRuntime.get().installedCount() > installBefore,
                "the waived request reached a terminal state without installing a path, so the "
                    + "unsafe tier admitted the search and then produced nothing");

            cleanup();
            stage = 2;
            helper.succeed();
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.repathToleranceBlocks = oldTolerance;
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }
    }
}
