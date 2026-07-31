package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.CompatibilityTier;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.FabricAggregateCompatibilityProbe;
import dev.pathweaver.gate.CertifiedLandProviders;
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
        private final int oldTolerance;
        private final int oldMaxResultAge;
        private final CompatibilityTier oldTier;
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
            this.oldTolerance = cfg.repathToleranceBlocks;
            this.oldMaxResultAge = cfg.maxResultAgeTicks;
            this.oldTier = cfg.compatibilityTier;
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
        // Raised for the same reason as in PathNavigationRoutingGameTest: the shipped 40-tick
        // result age is two seconds, and a cold JVM's first worker round trip does not reliably
        // beat it. A result that misses it is discarded as stale and the mob never asks again, so
        // a test polling for an install waits for something that can no longer happen. Staleness
        // itself is covered by EntityInstallSinkTest.
            cfg.maxResultAgeTicks = 1200;
            cfg.repathToleranceBlocks = 0;

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

            // The fixed-enum registration overload used here builds Fabric's own pure lambda, so
            // its answers are precomputed on the main thread and frozen. That must NOT deny: the
            // whole point is that an ordinary mod adding a path-type rule no longer switches
            // PathWeaver off. Note this is a property of that overload, not of the interface: a
            // hand-written StaticPathTypeProvider can close over mutable state, which is why
            // certification generally is an assumption honoured at AUDITED rather than a proof.
            LandPathTypeRegistry.register(Blocks.STRUCTURE_BLOCK,
                PathType.DAMAGING, PathType.DAMAGING);
            check(CertifiedLandProviders.isCertified(Blocks.STRUCTURE_BLOCK),
                "static provider registration was not certified");
            check(!FabricLandPathRegistryLatch.providerRegistrationObserved(),
                "a certified static provider must not trip the denial latch");
            check(FabricLandPathRegistryLatch.allowsWalkDispatch(),
                "Walk must keep dispatching after a certified static registration");
            check(SafetyGate.isAllowed(WalkNodeEvaluator.class),
                "the production gate must still admit Walk after certification");

            // The frozen answer must match what the mod actually returns, or a worker would route
            // a mob over a block the mod marked dangerous.
            check(CertifiedLandProviders.frozenProvider().getPathType(
                    Blocks.STRUCTURE_BLOCK.defaultBlockState(), false) == PathType.DAMAGING,
                "frozen answer does not match the registered static rule");

            // A provider may legitimately decline for some states, meaning "use vanilla". The
            // immutable map factories reject null values, so an earlier version of certification
            // turned a perfectly valid provider into a failure and denied instead.
            LandPathTypeRegistry.register(Blocks.GLASS,
                (state, isNeighbour) -> isNeighbour ? PathType.DAMAGING : null);
            check(CertifiedLandProviders.isCertified(Blocks.GLASS),
                "a provider returning null for some states must still certify");
            check(CertifiedLandProviders.frozenProvider().getPathType(
                    Blocks.GLASS.defaultBlockState(), false) == null,
                "declined answer was not preserved as null");
            check(CertifiedLandProviders.frozenProvider().getPathType(
                    Blocks.GLASS.defaultBlockState(), true) == PathType.DAMAGING,
                "neighbour answer lost while freezing a partially declining provider");
            check(CertifiedLandProviders.isCertified(Blocks.STRUCTURE_BLOCK),
                "certifying a second block dropped the first block's frozen answers");
            check(FabricLandPathRegistryLatch.allowsWalkDispatch(),
                "a declining static provider must not deny Walk");

            // A provider that throws must leave nothing behind: a partial table would answer some
            // states and silently diverge from vanilla on the rest.
            check(!CertifiedLandProviders.certify(Blocks.SAND, (state, isNeighbour) -> {
                throw new IllegalStateException("provider blew up");
            }), "a throwing provider must not report successful certification");
            check(!CertifiedLandProviders.isCertified(Blocks.SAND),
                "a throwing provider left a partial table behind");

            // A dynamic provider does receive the world, so it is not certifiable and must still
            // deny -- including invalidating the request already in flight.
            LandPathTypeRegistry.registerDynamic(Blocks.BEDROCK,
                (state, level, pos, isNeighbour) -> PathType.DAMAGING);
            check(FabricLandPathRegistryLatch.providerRegistrationObserved(),
                "dynamic provider registration did not publish before the registry mutation");
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

            // The tier is frozen at scan time, so writing it here -- which is exactly what saving
            // the settings screen does -- must NOT change what this process admits. Before the
            // freeze, raising the tier mid-session re-opened per-request gates while startup
            // denials stayed as they were, which let a session started at ALL keep dispatching
            // after being saved to STRICT. Assert the write is inert rather than that it works.
            CompatibilityTier previousTier = cfg.compatibilityTier;
            cfg.compatibilityTier = CompatibilityTier.UNSAFE;
            try {
                navigation.stop();
                long beforeAll = counter("dispatched");
                BlockPos allTarget = helper.absolutePos(new BlockPos(11, 2, 3));
                navigation.moveTo(allTarget.getX() + 0.5, allTarget.getY(),
                    allTarget.getZ() + 0.5, 1.0);
                check(counter("dispatched") == beforeAll,
                    "a tier written after the scan must not waive the land-provider gate; "
                        + "the tier is frozen until restart");
                check(navigation.getPath() != null,
                    "the request must still produce a real synchronous path");
            } finally {
                cfg.compatibilityTier = previousTier;
            }

            cleanup();
            stage = 3;
            helper.succeed();
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            cfg.enabled = oldEnabled;
            cfg.allowModdedMobAsync = oldModded;
            cfg.repathToleranceBlocks = oldTolerance;
            cfg.maxResultAgeTicks = oldMaxResultAge;
            cfg.compatibilityTier = oldTier;
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
