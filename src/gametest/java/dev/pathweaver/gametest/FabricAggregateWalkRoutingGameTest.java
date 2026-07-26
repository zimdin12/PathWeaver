package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.FabricAggregateCompatibilityProbe;
import dev.pathweaver.gate.FabricLandPathRegistryLatch;
import dev.pathweaver.gate.ForeignMixinScanner;
import dev.pathweaver.gate.SafetyGate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/** Non-vacuous stock aggregate-Fabric Walk activation and late-provider denial witness. */
public final class FabricAggregateWalkRoutingGameTest {
    public FabricAggregateWalkRoutingGameTest() {}

    @GameTest(maxTicks = 650)
    public void exactAggregateFabricDispatchesUntilProviderRegistration(GameTestHelper helper) {
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
        private long bypassBefore;
        private long lateInstallBefore;
        private long lateDiscardBefore;
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
                else if (stage == 1) awaitInitialInstallThenDispatchLateResult();
                else if (stage == 2) awaitLateDiscardThenProveFutureDenial();
            } catch (Throwable t) {
                cleanup();
                throw t;
            }
        }

        private void begin() {
            String harnessId = "pathweaver_gametest_aggregate";
            check(FabricLoader.getInstance().getModContainer(harnessId).isPresent(),
                "the dedicated aggregate-Fabric harness identity is not loaded");
            check(FabricAggregateCompatibilityProbe.harnessContributesNoSensitiveClaims(harnessId),
                "the aggregate-Fabric harness contributes an active sensitive mixin claim");
            ForeignMixinScanner.ScanReport report = ForeignMixinScanner.lastScanReport();
            check(report.decision().failed() == 0,
                "production prepared-config scan must complete without failures");
            check(report.configs().stream().anyMatch(c -> c.modId().equals(
                    "fabric-content-registries-v0")),
                "live content-registries config must be active");
            check(report.configs().stream().anyMatch(c -> c.modId().equals(
                    "fabric-events-interaction-v0")),
                "live events-interaction config must be active");

            FabricAggregateCompatibilityProbe.Result probe =
                FabricAggregateCompatibilityProbe.inspect();
            check(probe.preparedOwnershipExact(), String.join("; ", probe.diagnostics()));
            check(probe.productionDecisionAllowsBoth(), String.join("; ", probe.diagnostics()));
            check(probe.exactEvidenceComplete(), String.join("; ", probe.diagnostics()));
            check(probe.allPreparedNearMissesDeny(), String.join("; ", probe.diagnostics()));
            check(probe.alteredModuleBytesDeny(), String.join("; ", probe.diagnostics()));
            check(probe.alteredClassBytesDeny(), String.join("; ", probe.diagnostics()));
            check(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "the unmodified production gate must admit exact aggregate-Fabric Walk");
            check(!FabricLandPathRegistryLatch.providerRegistrationObserved(),
                "stock aggregate Fabric must begin with no registered land provider");
            check(FabricLandPathRegistryLatch.allowsWalkDispatch(),
                "exact verified hooks plus sealed-empty registry must admit Walk");

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
            bypassBefore = FabricLandPathRegistryLatch.workerProviderLookupBypasses();
            BlockPos target = helper.absolutePos(new BlockPos(7, 2, 2));
            check(navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                "exact aggregate-Fabric Walk move must be accepted");
            check(PathWeaverRuntime.get().entitySink().isRegistered(mob.getId()),
                "exact aggregate-Fabric Walk must create a real worker registration");
            check(counter("dispatched") == dispatchBefore + 1,
                "exact aggregate-Fabric Walk must contribute one real dispatch");
            stage = 1;
        }

        private void awaitInitialInstallThenDispatchLateResult() {
            boolean installed = !PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())
                && navigation.getPath() != null && counter("installed") >= installBefore + 1;
            if (!installed) {
                if (helper.getTick() >= 500) {
                    throw helper.assertionException(
                        "exact aggregate-Fabric Walk request did not install");
                }
                return;
            }
            check(FabricLandPathRegistryLatch.workerProviderLookupBypasses() > bypassBefore,
                "real worker search did not exercise the live-map bypass hook");

            navigation.stop();
            long lateDispatchBefore = counter("dispatched");
            lateInstallBefore = counter("installed");
            lateDiscardBefore = counter("discarded");
            BlockPos lateTarget = helper.absolutePos(new BlockPos(10, 2, 3));
            check(navigation.moveTo(lateTarget.getX() + 0.5, lateTarget.getY(),
                    lateTarget.getZ() + 0.5, 1.0),
                "second sealed-empty Walk move must be accepted");
            check(counter("dispatched") == lateDispatchBefore + 1,
                "late-registration witness must contribute one real dispatch");
            check(PathWeaverRuntime.get().entitySink().isRegistered(mob.getId()),
                "late-registration witness did not capture an in-flight Walk request");

            LandPathTypeRegistry.register(Blocks.STRUCTURE_BLOCK,
                PathType.DAMAGING, PathType.DAMAGING);
            check(FabricLandPathRegistryLatch.providerRegistrationObserved(),
                "real provider registration did not publish before the registry mutation");
            check(!FabricLandPathRegistryLatch.allowsWalkDispatch(),
                "monotonic provider latch must deny future Walk dispatch");
            stage = 2;
        }

        private void awaitLateDiscardThenProveFutureDenial() {
            if (PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())) {
                if (helper.getTick() >= 550) {
                    throw helper.assertionException(
                        "provider registration did not discard the captured in-flight Walk result");
                }
                return;
            }
            check(counter("installed") == lateInstallBefore,
                "captured sealed-empty result installed after provider registration");
            check(counter("discarded") >= lateDiscardBefore + 1,
                "captured sealed-empty result did not contribute a terminal discard");

            navigation.stop();
            long deniedDispatch = counter("dispatched");
            BlockPos target = helper.absolutePos(new BlockPos(13, 2, 2));
            check(navigation.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0),
                "provider-present request must fall through to vanilla synchronous pathing");
            check(counter("dispatched") == deniedDispatch,
                "provider-present request created async work");
            check(!PathWeaverRuntime.get().entitySink().isRegistered(mob.getId()),
                "provider-present request retained an async registration");
            check(navigation.getPath() != null,
                "provider-present denial must still produce a real synchronous path");
            cleanup();
            stage = 3;
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
