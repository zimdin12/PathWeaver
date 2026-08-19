package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.ForeignMixinScanner;
import dev.pathweaver.gate.SafetyGate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;


/** Live exact-positive and near-miss-denial witness for milestone-one compatibility tuples. */
public final class AuditedCompatibilityRoutingGameTest {
    private static final ForeignMixinScanner.TargetClaim SERVERCORE_CLAIM =
        new ForeignMixinScanner.TargetClaim(
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
            "net.minecraft.world.level.pathfinder.PathFinder");
    private static final ForeignMixinScanner.TargetClaim RABBIT_CLAIM =
        new ForeignMixinScanner.TargetClaim("net.litetex.rpf.mixin.EntityNavigationMixin",
            "net.minecraft.world.entity.ai.navigation.PathNavigation");

    public AuditedCompatibilityRoutingGameTest() {}

    // Generous by design; see the note in PathNavigationRoutingGameTest. A busy machine can
    // starve the worker pool for far longer than the async round trip needs when idle.
    @GameTest(maxTicks = 900)
    public void serverCoreAndRabbitExactTuplesDispatchWhileNearMissesDeny(GameTestHelper helper) {
        // The registered baseline routing test mutates the same global config/gate and has a hard
        // maxTicks of 160. Create this scenario only after it is terminal, then advance it
        // from one onEachTick state machine. No scheduled callback mutates GameTest's timer map.
        Scenario[] scenario = new Scenario[1];
        helper.onEachTick(() -> {
            if (helper.getTick() < 170) return;
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
        private final Set<Class<?>> oldDenials;
        private PathNavigation navigation;
        private Mob mob;
        private ForeignMixinScanner.ScanDecision exact;
        private ForeignMixinScanner.ScanDecision serverDrift;
        private ForeignMixinScanner.ScanDecision rabbitDrift;
        private long firstInstall;
        private long secondInstall;
        private int stage;
        private boolean cleaned;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.cfg = PathWeaverConfig.get();
            this.oldEnabled = cfg.enabled;
            this.oldModded = cfg.allowModdedMobAsync;
            this.oldTolerance = cfg.repathToleranceBlocks;
            this.oldMaxResultAge = cfg.maxResultAgeTicks;
            synchronized (SafetyGate.deniedBySafety) {
                this.oldDenials = Set.copyOf(SafetyGate.deniedBySafety);
            }
        }

        void tick() {
            try {
                if (stage == 0) begin();
                else if (stage == 1) awaitFirstInstall();
                else if (stage == 2) awaitSecondInstall();
            } catch (Throwable t) {
                cleanup();
                throw t;
            }
        }

        private void begin() {
            ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();
            check(helper, report.decision().failed() == 0,
                "live foreign-mixin discovery must be authoritative");
            ForeignMixinScanner.ActiveConfig serverCore = findConfig(report,
                "servercore", "servercore.common.mixins.json");
            ForeignMixinScanner.ActiveConfig rabbit = findConfig(report,
                "rabbit-pathfinding-fix", "rabbit-pathfinding-fix.mixins.json");
            check(helper, serverCore != null, "exact ServerCore config must be active in the live JVM");
            check(helper, rabbit != null, "exact rabbit config must be active in the live JVM");
            check(helper, serverCore.claims().contains(SERVERCORE_CLAIM),
                "live ServerCore config must contain its exact audited sensitive claim");
            check(helper, serverCore.pluginIdentity() != null
                    && serverCore.pluginIdentity().className().equals(
                        "me.wesley1808.servercore.mixin.ServerCoreMixinPlugin")
                    && serverCore.pluginIdentity().classSha256().equals(
                        "0e6ddc8d3c66c7e5826831845e0da41f6594b758a128d207419083b081e33cf6"),
                "live ServerCore config must retain its exact prepared-plugin identity and bytes");
            check(helper, rabbit.claims().contains(RABBIT_CLAIM),
                "live rabbit config must contain its exact audited sensitive claim");
            check(helper, rabbit.pluginIdentity() == null,
                "live rabbit config must retain its audited no-plugin bit");
            check(helper, report.auditedEvidence().verifies(serverCore, SERVERCORE_CLAIM),
                "live evidence must contain the exact ServerCore audit key");
            check(helper, report.auditedEvidence().verifies(rabbit, RABBIT_CLAIM),
                "live evidence must contain the exact rabbit audit key");

            exact = decide(report, serverCore, rabbit);
            check(helper, exact.denied().isEmpty(),
                "the two exact runtime-verified claims must admit both evaluator families");
            var serverNearMiss = new ForeignMixinScanner.ActiveConfig(serverCore.modId(),
                "1.5.20+near-miss", serverCore.configName(), serverCore.claims(),
                serverCore.pluginIdentity());
            var rabbitNearMiss = new ForeignMixinScanner.ActiveConfig(rabbit.modId(),
                "1.4.0-near-miss", rabbit.configName(), rabbit.claims(), rabbit.pluginIdentity());
            serverDrift = decide(report, serverNearMiss, rabbit);
            rabbitDrift = decide(report, serverCore, rabbitNearMiss);
            Set<Class<?>> denyAll = SafetyGate.allowlisted();
            check(helper, serverDrift.denied().equals(denyAll),
                "ServerCore version near-miss must deny every family");
            check(helper, rabbitDrift.denied().equals(denyAll),
                "rabbit version near-miss must deny every family");

            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 4; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            mob = helper.spawnWithNoFreeWill(VanillaTypes.mob(VanillaTypes.ZOMBIE), 1, 2, 2);
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
            apply(exact.denied());
            RabbitWorkerReachabilityProbe.reset();

            long dispatch = counter("dispatched");
            firstInstall = counter("installed");
            BlockPos target = helper.absolutePos(new BlockPos(6, 2, 2));
            check(helper, navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                "exact audited tuple move must be accepted");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(mob.getId()),
                "exact audited tuple must create a real worker registration");
            check(helper, counter("dispatched") == dispatch + 1,
                "exact audited tuple must contribute one real dispatch");
            stage = 1;
        }

        private void awaitFirstInstall() {
            if (ready(firstInstall)) {
                navigation.stop();
                apply(serverDrift.denied());
                long deniedDispatch = counter("dispatched");
                BlockPos target = helper.absolutePos(new BlockPos(8, 2, 1));
                check(helper, navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                    "ServerCore near-miss must fall through to vanilla sync pathing");
                check(helper, counter("dispatched") == deniedDispatch
                        && !PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())
                        && navigation.getPath() != null,
                    "ServerCore near-miss must produce zero async work");

                navigation.stop();
                apply(exact.denied());
                long dispatch = counter("dispatched");
                secondInstall = counter("installed");
                target = helper.absolutePos(new BlockPos(9, 2, 3));
                check(helper, navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                    "restored exact tuple move must be accepted");
                check(helper, counter("dispatched") == dispatch + 1,
                    "restored exact tuple must dispatch again");
                stage = 2;
            } else if (helper.getTick() >= 600) {
                throw helper.assertionException("exact ServerCore+rabbit request did not install");
            }
        }

        private void awaitSecondInstall() {
            if (ready(secondInstall)) {
                navigation.stop();
                apply(rabbitDrift.denied());
                long deniedDispatch = counter("dispatched");
                BlockPos target = helper.absolutePos(new BlockPos(10, 2, 2));
                check(helper, navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                    "rabbit near-miss must fall through to vanilla sync pathing");
                check(helper, counter("dispatched") == deniedDispatch
                        && !PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())
                        && navigation.getPath() != null,
                    "rabbit near-miss must produce zero async work");
                check(helper, RabbitWorkerReachabilityProbe.workerEntries() == 0,
                    "Rabbit-modified methods executed under the worker-thread marker");
                cleanup();
                stage = 3;
                helper.succeed();
            } else if (helper.getTick() >= 850) {
                throw helper.assertionException("restored exact tuple request did not install");
            }
        }

        private boolean ready(long installBefore) {
            return !PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())
                && navigation.getPath() != null && counter("installed") >= installBefore + 1;
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.allowModdedMobAsync = oldModded;
            cfg.repathToleranceBlocks = oldTolerance;
            cfg.maxResultAgeTicks = oldMaxResultAge;
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
                SafetyGate.deniedBySafety.addAll(oldDenials);
            }
        }
    }

    private static ForeignMixinScanner.ActiveConfig findConfig(
            ForeignMixinScanner.ScanReport report, String modId, String config) {
        return report.configs().stream().filter(c -> c.modId().equals(modId)
            && c.configName().equals(config)).findFirst().orElse(null);
    }

    private static ForeignMixinScanner.ScanDecision decide(ForeignMixinScanner.ScanReport report,
                                                            ForeignMixinScanner.ActiveConfig first,
                                                            ForeignMixinScanner.ActiveConfig second) {
        return ForeignMixinScanner.decide(List.of(first, second), List.of(),
            new ForeignMixinScanner.SwimExemptionEvidence(false, List.of()),
            report.auditedEvidence());
    }

    private static void apply(Set<Class<?>> denied) {
        synchronized (SafetyGate.deniedBySafety) {
            SafetyGate.deniedBySafety.clear();
            SafetyGate.deniedBySafety.addAll(denied);
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

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) throw helper.assertionException(message);
    }
}
