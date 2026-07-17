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
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Live contract proof for query-only versus genuine-navigation routing. */
public final class PathNavigationRoutingGameTest {
    public PathNavigationRoutingGameTest() {}

    @GameTest(maxTicks = 160)
    public void queryCallsStaySyncWhileMovementAndRecomputeDispatch(GameTestHelper helper) {
        PathWeaverConfig cfg = PathWeaverConfig.get();
        boolean oldAsync = cfg.asyncEnabled;
        boolean oldFallback = cfg.syncFallbackOnly;
        boolean oldElision = cfg.repathElisionEnabled;
        int oldTolerance = cfg.repathToleranceBlocks;
        Set<Class<?>> oldDenials;
        synchronized (SafetyGate.deniedBySafety) {
            oldDenials = Set.copyOf(SafetyGate.deniedBySafety);
        }
        Runnable teardown = () -> {
            restore(cfg, oldAsync, oldFallback, oldElision, oldTolerance);
            synchronized (SafetyGate.deniedBySafety) {
                SafetyGate.deniedBySafety.clear();
                SafetyGate.deniedBySafety.addAll(oldDenials);
            }
        };

        try {
            ForeignMixinScanner.ScanReport scan = ForeignMixinScanner.lastScanReport();
            check(helper, scan.decision().failed() == 0,
                "live scanner discovery must complete without fallback denial");
            check(helper, scan.decision().scanned() > 0,
                "live scanner must inspect prepared foreign configs");
            ForeignMixinScanner.ActiveConfig fabricPathHooks = scan.configs().stream()
                .filter(c -> c.modId().equals("fabric-content-registries-v0")
                    && c.configName().equals("fabric-content-registries-v0.mixins.json"))
                .findFirst().orElse(null);
            check(helper, fabricPathHooks != null,
                "scanner must attribute Fabric content-registry pathfinding hooks exactly");
            check(helper, fabricPathHooks.claims().containsAll(Set.of(
                new ForeignMixinScanner.TargetClaim(
                    "net.fabricmc.fabric.mixin.content.registry.PathfindingContextMixin",
                    "net.minecraft.world.level.pathfinder.PathfindingContext"),
                new ForeignMixinScanner.TargetClaim(
                    "net.fabricmc.fabric.mixin.content.registry.WalkNodeEvaluatorMixin",
                    "net.minecraft.world.level.pathfinder.WalkNodeEvaluator"))),
                "scanner must retain concrete mixin identities and sensitive targets");
            check(helper, oldDenials.containsAll(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class)),
                "live scanner must fail closed on Fabric's pathfinding registry hooks");
            SafetyGate.deniedBySafety.clear();
            cfg.asyncEnabled = true;
            cfg.syncFallbackOnly = false;
            cfg.repathToleranceBlocks = 0;

        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 5; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        }

        Mob coordinateMob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 1);
        Mob entityMob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 4);
        Mob targetMob = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 6, 2, 4);
        coordinateMob.setOnGround(true);
        entityMob.setOnGround(true);
        targetMob.setOnGround(true);
        PathNavigation queryNav = coordinateMob.getNavigation();
        PathNavigation entityNav = entityMob.getNavigation();
        BlockPos target = helper.absolutePos(new BlockPos(6, 2, 1));
        long baseDispatched = runtimeCounter("dispatched");
        long baseInstalled = runtimeCounter("installed");
        long baseDiscarded = runtimeCounter("discarded");

        // Belt-and-suspenders teardown for a framework-level timeout that bypasses a scheduled poll.
        helper.onEachTick(() -> {
            if (helper.getTick() >= 159) teardown.run();
        });

            Path beforePath = queryNav.getPath();
            double beforeSpeed = speedModifier(queryNav);

            Path blockPath = queryNav.createPath(target, 1);
            Path blockWithLengthPath = queryNav.createPath(target, 1, 32);
            Path entityPath = queryNav.createPath(targetMob, 1);

            check(helper, blockPath != null, "direct BlockPos query must return its real path immediately");
            check(helper, blockWithLengthPath != null,
                "direct BlockPos+length query must return its real path immediately");
            check(helper, entityPath != null, "direct Entity query must return its real path immediately");
            check(helper, !PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                "direct queries must not register async work");
            check(helper, PathWeaverRuntime.get().pool().inFlight() == 0,
                "direct queries must not dispatch worker work");
            check(helper, queryNav.getPath() == beforePath, "query must not replace navigation.getPath()");
            check(helper, Double.compare(speedModifier(queryNav), beforeSpeed) == 0,
                "query must not mutate navigation speed");

            boolean coordinateAccepted = queryNav.moveTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            boolean entityAccepted = entityNav.moveTo(targetMob, 1.0);

            // Current v0.1.x placeholder contract: with no old path, accepted async work still returns
            // false from high-level moveTo. A later epoch/result slice will model accepted-pending.
            check(helper, !coordinateAccepted, "coordinate move currently exposes accepted-pending as false");
            check(helper, !entityAccepted, "entity move currently exposes accepted-pending as false");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                "coordinate move must dispatch one async request");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(entityMob.getId()),
                "entity move must dispatch one async request");
            check(helper, runtimeCounter("dispatched") == baseDispatched + 2,
                "exactly the coordinate + entity movement requests must dispatch");

            setTimeLastRecompute(queryNav, -100L);
            queryNav.recomputePath();
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                "recompute must replace same-target accepted pending work");
            check(helper, runtimeCounter("dispatched") == baseDispatched + 3,
                "pending recompute must dispatch one fresh request from current world facts");
            check(helper, runtimeCounter("discarded") == baseDiscarded + 1,
                "pending recompute must account exactly one superseded request");

            pollUntil(helper, 80, () -> helper.getTick() >= 25
                    && !PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId())
                    && !PathWeaverRuntime.get().entitySink().isRegistered(entityMob.getId())
                    && queryNav.getPath() != null
                    && entityNav.getPath() != null
                    && runtimeCounter("installed") == baseInstalled + 2,
                "explicit movement requests did not both install before the deadline", teardown, () -> {
                queryNav.recomputePath();
                check(helper, PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                    "recomputePath must arm and dispatch async path creation");
                check(helper, runtimeCounter("dispatched") == baseDispatched + 4,
                    "recompute must contribute exactly one additional dispatch");
                pollUntil(helper, 150, () ->
                        !PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId())
                        && queryNav.getPath() != null
                        && runtimeCounter("installed") == baseInstalled + 3,
                    "recompute request did not install before the deadline", teardown, () -> {
                        cfg.asyncEnabled = false;
                        cfg.repathElisionEnabled = true;
                        cfg.repathToleranceBlocks = 1;
                        Path reusable = queryNav.getPath();
                        BlockPos drifted = target.offset(1, 0, 0);
                        check(helper, reusable != null && reusable.canReach(),
                            "Feature B baseline must be a reached live path");
                        check(helper, queryNav.moveTo(drifted.getX() + 0.5, drifted.getY(),
                            drifted.getZ() + 0.5, 1.0),
                            "valid one-block target drift must reuse the live path");
                        check(helper, queryNav.getPath() == reusable,
                            "valid drift must preserve exact live path identity");
                        check(helper, drifted.equals(targetPos(queryNav)),
                            "valid drift must advance navigation target intent for later recompute");

                        setTimeLastRecompute(queryNav, -100L);
                        queryNav.recomputePath();
                        check(helper, queryNav.getPath() != null,
                            "recompute must produce a replacement path");
                        check(helper, queryNav.getPath() != reusable,
                            "recompute/changed-block invalidation must bypass tolerance reuse");
                        teardown.run();
                        helper.succeed();
                    });
            });
        } catch (Throwable t) {
            teardown.run();
            throw t;
        }
    }

    private static void pollUntil(GameTestHelper helper, long deadline, BooleanSupplier ready,
                                  String timeoutMessage, Runnable teardown, Runnable onReady) {
        helper.runAfterDelay(1, () -> {
            try {
                if (ready.getAsBoolean()) {
                    onReady.run();
                } else if (helper.getTick() >= deadline) {
                    throw helper.assertionException(timeoutMessage);
                } else {
                    pollUntil(helper, deadline, ready, timeoutMessage, teardown, onReady);
                }
            } catch (Throwable t) {
                teardown.run();
                throw t;
            }
        });
    }

    private static void setTimeLastRecompute(PathNavigation navigation, long tick) {
        try {
            Field field = PathNavigation.class.getDeclaredField("timeLastRecompute");
            field.setAccessible(true);
            field.setLong(navigation, tick);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set PathNavigation.timeLastRecompute", e);
        }
    }

    private static BlockPos targetPos(PathNavigation navigation) {
        try {
            Field field = PathNavigation.class.getDeclaredField("targetPos");
            field.setAccessible(true);
            return (BlockPos) field.get(navigation);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect PathNavigation.targetPos", e);
        }
    }

    private static double speedModifier(PathNavigation navigation) {
        try {
            Field field = PathNavigation.class.getDeclaredField("speedModifier");
            field.setAccessible(true);
            return field.getDouble(navigation);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect PathNavigation.speedModifier", e);
        }
    }

    private static long runtimeCounter(String name) {
        try {
            Field field = PathWeaverRuntime.class.getDeclaredField(name);
            field.setAccessible(true);
            return ((AtomicLong) field.get(PathWeaverRuntime.get())).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect PathWeaverRuntime." + name, e);
        }
    }

    private static void restore(PathWeaverConfig cfg, boolean async, boolean fallback,
                                boolean elision, int tolerance) {
        cfg.asyncEnabled = async;
        cfg.syncFallbackOnly = fallback;
        cfg.repathElisionEnabled = elision;
        cfg.repathToleranceBlocks = tolerance;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) throw helper.assertionException(message);
    }
}
