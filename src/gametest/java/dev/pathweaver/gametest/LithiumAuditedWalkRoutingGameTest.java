package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.CompatibilityTier;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.ForeignMixinScanner;
import dev.pathweaver.gate.SafetyGate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live witness that the audited tier actually routes vanilla Walk searches off-thread with Lithium
 * loaded — the configuration nearly every performance modpack actually runs.
 *
 * <p>The interesting failure this guards against is a vacuous pass: if Lithium happened to
 * contribute no sensitive claim, every assertion below would succeed while proving nothing about
 * the exemption. So the test re-runs the production decision over Lithium's own live configs with
 * the audited evidence withheld and requires that it denies. Walk is therefore demonstrably
 * allowed <em>because of</em> the audited exemption, not in spite of Lithium being present.
 */
public final class LithiumAuditedWalkRoutingGameTest {
    private static final String LITHIUM = "lithium";

    public LithiumAuditedWalkRoutingGameTest() {}

    @GameTest(maxTicks = 650)
    public void auditedTierRoutesWalkOffThreadWithLithiumLoaded(GameTestHelper helper) {
        Scenario[] scenario = new Scenario[1];
        helper.onEachTick(() -> {
            if (helper.getTick() < 370) return;
            if (scenario[0] == null) scenario[0] = new Scenario(helper);
            scenario[0].tick();
        });
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final PathWeaverConfig cfg;
        private final boolean oldEnabled;
        private final boolean oldModded;
        private final boolean oldElision;
        private Mob mob;
        private PathNavigation navigation;
        private long installBefore;
        private int stage;
        private boolean cleaned;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.cfg = PathWeaverConfig.get();
            this.oldEnabled = cfg.enabled;
            this.oldModded = cfg.allowModdedMobAsync;
            this.oldElision = cfg.repathElisionEnabled;
        }

        void tick() {
            try {
                if (stage == 0) begin();
                else if (stage == 1) awaitInstall();
            } catch (Throwable t) {
                cleanup();
                throw t;
            }
        }

        private void begin() {
            check(FabricLoader.getInstance().getModContainer(LITHIUM).isPresent(),
                "Lithium is not loaded, so this witness would prove nothing");
            check(cfg.compatibilityTier == CompatibilityTier.AUDITED,
                "harness must run at compatibilityTier=AUDITED, found " + cfg.compatibilityTier);

            ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();
            check(report.decision().failed() == 0,
                "production scan must complete without failures: " + report.decision().diagnostics());

            List<ForeignMixinScanner.ActiveConfig> lithiumConfigs = report.configs().stream()
                .filter(config -> LITHIUM.equals(config.modId()))
                .toList();
            check(!lithiumConfigs.isEmpty(), "no live Lithium mixin config was attributed");

            // Non-vacuity: without the audited evidence, Lithium's own live claims must deny.
            // Otherwise Walk being allowed below would say nothing about the exemption.
            var denialsWithoutEvidence = ForeignMixinScanner.decide(lithiumConfigs, List.of(),
                ForeignMixinScanner.SwimExemptionEvidence.unverified(
                    "negative control withholds the Swim exemption"),
                ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied();
            check(denialsWithoutEvidence.contains(WalkNodeEvaluator.class),
                "Lithium contributed no Walk-denying claim, so this witness is vacuous");

            // ...and with the live evidence the very same configs must be admitted.
            var denialsWithEvidence = ForeignMixinScanner.decide(lithiumConfigs, List.of(),
                ForeignMixinScanner.SwimExemptionEvidence.unverified(
                    "Swim exemption is irrelevant to the Lithium tuple"),
                report.auditedEvidence()).denied();
            // Name the offenders rather than just the families, so an unaudited Lithium mixin is
            // immediately identifiable instead of requiring a separate investigation.
            String uncovered = lithiumConfigs.stream()
                .flatMap(config -> config.claims().stream()
                    .filter(claim -> !ForeignMixinScanner.denialsForTargets(
                        List.of(claim.target())).isEmpty())
                    .filter(claim -> !report.auditedEvidence().verifies(config, claim))
                    .map(claim -> claim.mixinClass() + " -> " + claim.target()))
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
            check(denialsWithEvidence.isEmpty(),
                "audited Lithium evidence did not clear its own claims: " + denialsWithEvidence
                    + "; unaudited sensitive claims: [" + uncovered + "]");

            // Asserted as a property, not a count. Lithium's mixin plugin decides at load time
            // which of its mixins actually apply, so the number of live claims is a function of
            // the user's lithium.properties; a fixed expected count would be brittle and would
            // also pass for the wrong reason if a claim disappeared. What must hold is that
            // nothing sensitive is left unaudited.
            check(uncovered.isEmpty(),
                "sensitive Lithium claims without live audited evidence: [" + uncovered + "]");

            check(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "production gate must admit Walk at the audited tier with Lithium loaded");

            for (int x = 0; x <= 14; x++) {
                for (int z = 0; z <= 4; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 2);
            mob.setOnGround(true);
            navigation = mob.getNavigation();
            cfg.enabled = true;
            cfg.allowModdedMobAsync = false;
            cfg.repathElisionEnabled = false;

            long dispatchBefore = counter("dispatched");
            installBefore = counter("installed");
            BlockPos target = helper.absolutePos(new BlockPos(7, 2, 2));
            check(navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                "Walk move must be accepted with Lithium loaded");
            check(PathWeaverRuntime.get().entitySink().isRegistered(mob.getId()),
                "Walk request did not create a real worker registration");
            check(counter("dispatched") == dispatchBefore + 1,
                "Walk request did not contribute one real dispatch");
            stage = 1;
        }

        private void awaitInstall() {
            boolean installed = !PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())
                && navigation.getPath() != null && counter("installed") >= installBefore + 1;
            if (!installed) {
                if (helper.getTick() >= 600) {
                    throw helper.assertionException(
                        "Lithium-loaded Walk request never installed a worker-computed path");
                }
                return;
            }
            cleanup();
            stage = 2;
            helper.succeed();
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.allowModdedMobAsync = oldModded;
            cfg.repathElisionEnabled = oldElision;
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }
    }

    private static long counter(String name) {
        try {
            Field field = PathWeaverRuntime.class.getDeclaredField(name);
            field.setAccessible(true);
            return ((AtomicLong) field.get(PathWeaverRuntime.get())).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect PathWeaverRuntime." + name, e);
        }
    }
}
