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
 * Live witness that the audited tier actually routes vanilla Walk searches off-thread with
 * Lithium and Diagonal Blocks loaded — between them, the reason PathWeaver was inert on real
 * modpacks.
 *
 * <p>The interesting failure this guards against is a vacuous pass: if either mod happened to
 * contribute no sensitive claim, every assertion below would succeed while proving nothing about
 * the exemption. So the test re-runs the production decision over each mod's own live configs
 * with the audited evidence withheld and requires that it denies. Walk is therefore demonstrably
 * allowed <em>because of</em> the audited exemptions, not in spite of those mods being present.
 */
public final class AuditedTierWalkRoutingGameTest {
    /** Every mod the audited tier claims to unlock must be present and must be doing work. */
    private static final List<String> AUDITED_MODS = List.of("lithium", "diagonalblocks");

    public AuditedTierWalkRoutingGameTest() {}

    @GameTest(maxTicks = 650)
    public void auditedTierRoutesWalkOffThreadWithAuditedModsLoaded(GameTestHelper helper) {
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
        private final int oldTolerance;
        private final int oldMaxResultAge;
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
            this.oldTolerance = cfg.repathToleranceBlocks;
            this.oldMaxResultAge = cfg.maxResultAgeTicks;
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
            for (String mod : AUDITED_MODS) {
                check(FabricLoader.getInstance().getModContainer(mod).isPresent(),
                    mod + " is not loaded, so this witness would prove nothing");
            }
            check(cfg.compatibilityTier == CompatibilityTier.AUDITED,
                "harness must run at compatibilityTier=AUDITED, found " + cfg.compatibilityTier);

            ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();
            check(report.decision().failed() == 0,
                "production scan must complete without failures: " + report.decision().diagnostics());

            for (String mod : AUDITED_MODS) {
                List<ForeignMixinScanner.ActiveConfig> configs = report.configs().stream()
                    .filter(config -> mod.equals(config.modId()))
                    .toList();
                check(!configs.isEmpty(), "no live mixin config was attributed to " + mod);

                // Non-vacuity: without the audited evidence, this mod's own live claims must deny.
                // Otherwise Walk being allowed below would say nothing about the exemption.
                var denialsWithoutEvidence = ForeignMixinScanner.decide(configs, List.of(),
                    ForeignMixinScanner.SwimExemptionEvidence.unverified(
                        "negative control withholds the Swim exemption"),
                    ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied();
                check(denialsWithoutEvidence.contains(WalkNodeEvaluator.class),
                    mod + " contributed no Walk-denying claim, so this witness is vacuous");

                // ...and with the live evidence the very same configs must be admitted.
                var denialsWithEvidence = ForeignMixinScanner.decide(configs, List.of(),
                    ForeignMixinScanner.SwimExemptionEvidence.unverified(
                        "Swim exemption is irrelevant to these tuples"),
                    report.auditedEvidence()).denied();
                // Name the offenders rather than just the families, so an unaudited mixin is
                // immediately identifiable instead of requiring a separate investigation.
                String uncovered = configs.stream()
                    .flatMap(config -> config.claims().stream()
                        .filter(claim -> !ForeignMixinScanner.denialsForTargets(
                            List.of(claim.target())).isEmpty())
                        .filter(claim -> !report.auditedEvidence().verifies(config, claim))
                        .map(claim -> claim.mixinClass() + " -> " + claim.target()))
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
                check(denialsWithEvidence.isEmpty(),
                    "audited evidence for " + mod + " did not clear its own claims: "
                        + denialsWithEvidence + "; unaudited: [" + uncovered + "]");

                // Asserted as a property, not a count. A mixin plugin decides at load time which
                // mixins actually apply, so the number of live claims is a function of the user
                // config; a fixed expected count would be brittle and would also pass for the
                // wrong reason if a claim disappeared. What must hold is that nothing sensitive
                // is left unaudited.
                check(uncovered.isEmpty(),
                    "sensitive " + mod + " claims without live audited evidence: ["
                        + uncovered + "]");
            }

            check(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "production gate must admit Walk at the audited tier with the audited mods loaded");

            for (int x = 0; x <= 14; x++) {
                for (int z = 0; z <= 4; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            mob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 2);
            mob.setOnGround(true);
            navigation = mob.getNavigation();
            cfg.enabled = true;
            cfg.allowModdedMobAsync = false;
        // Raised for the same reason as in PathNavigationRoutingGameTest: the shipped 40-tick
        // result age is two seconds, and a cold JVM's first worker round trip does not reliably
        // beat it. A result that misses it is discarded as stale and the mob never asks again, so
        // a test polling for an install waits for something that can no longer happen. Staleness
        // itself is covered by EntityInstallSinkTest.
            cfg.maxResultAgeTicks = 1200;
            cfg.repathToleranceBlocks = 0;

            long dispatchBefore = counter("dispatched");
            installBefore = counter("installed");
            BlockPos target = helper.absolutePos(new BlockPos(7, 2, 2));
            check(navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                "Walk move must be accepted with the audited mods loaded");
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
                        "audited-tier Walk request never installed a worker-computed path");
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
            cfg.repathToleranceBlocks = oldTolerance;
            cfg.maxResultAgeTicks = oldMaxResultAge;
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }
    }

    private static long counter(String name) {
        try {
            // Public accessors, not reflection: these counters are part of the runtime's API now,
            // and reaching past it meant a field rename broke three game tests at once.
            return switch (name) {
                case "dispatched" -> PathWeaverRuntime.get().dispatchedCount();
                case "installed" -> PathWeaverRuntime.get().installedCount();
                case "discarded" -> PathWeaverRuntime.get().discardedCount();
                default -> throw new AssertionError("unknown PathWeaver counter: " + name);
            };
        } catch (RuntimeException e) {
            throw new AssertionError("could not read PathWeaverRuntime." + name, e);
        }
    }
}
