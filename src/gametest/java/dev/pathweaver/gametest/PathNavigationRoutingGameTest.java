package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.PathOutcome;
import dev.pathweaver.async.PathRequest;
import dev.pathweaver.async.PathWeaverThread;
import dev.pathweaver.async.PathWorkerPool;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.ForeignMixinScanner;
import dev.pathweaver.gate.SafetyGate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Live contract proof for query-only versus genuine-navigation routing. */
public final class PathNavigationRoutingGameTest {
    public PathNavigationRoutingGameTest() {}

    // Deadlines are wall-clock-sensitive: an async install needs a worker round trip plus a
    // main-thread drain, and the pool is sized from core count. On a machine that is busy (a cold
    // CI agent, or a build running in parallel) that round trip can take far longer than the
    // handful of ticks it needs when idle. These budgets are deliberately generous -- a passing
    // run still finishes as soon as the work lands, so the slack costs nothing and only stops the
    // suite reporting a scheduling delay as a product failure.
    @GameTest(maxTicks = 1400)
    public void queryCallsStaySyncWhileMovementAndRecomputeDispatch(GameTestHelper helper) {
        PathWeaverConfig cfg = PathWeaverConfig.get();
        boolean oldEnabled = cfg.enabled;
        boolean oldModdedMobOverride = cfg.allowModdedMobAsync;
        int oldTolerance = cfg.repathToleranceBlocks;
        int oldMaxResultAge = cfg.maxResultAgeTicks;
        Set<Class<?>> oldDenials;
        synchronized (SafetyGate.deniedBySafety) {
            oldDenials = Set.copyOf(SafetyGate.deniedBySafety);
        }
        // The shipped 40-tick result age is two seconds, and this suite kept failing about one clean
        // build in five because a cold JVM's first worker round trip does not always beat it. When it
        // does not, the result is discarded as stale and the mob never asks again -- so the test polls
        // for an install that can no longer happen, and widening the poll deadline (which is what was
        // tried before) cannot help. Raising the age bound removes the race without weakening
        // anything: this test asserts routing semantics, and staleness has its own dedicated
        // coverage in EntityInstallSinkTest.
        cfg.maxResultAgeTicks = 1200;
        Runnable teardown = () -> {
            cfg.maxResultAgeTicks = oldMaxResultAge;
            restore(cfg, oldEnabled, oldModdedMobOverride, oldTolerance);
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
            check(helper, scan.configs().stream().noneMatch(c -> c.modId().equals("pathweaver")),
                "PathWeaver's production config must be excluded from reported foreign claims");
            ForeignMixinScanner.ActiveConfig fabricPathHooks = scan.configs().stream()
                .filter(c -> c.modId().equals("fabric-content-registries-v0")
                    && c.configName().equals("fabric-content-registries-v0.mixins.json"))
                .findFirst().orElse(null);
            ForeignMixinScanner.ActiveConfig fabricInteractionHooks = scan.configs().stream()
                .filter(c -> c.modId().equals("fabric-events-interaction-v0")
                    && c.configName().equals("fabric-events-interaction-v0.mixins.json"))
                .findFirst().orElse(null);
            boolean contentRegistriesDebugDisabled = java.util.Arrays.stream(
                    System.getProperty("fabric.debug.disableModIds", "").split(","))
                .map(String::trim)
                .anyMatch("fabric-content-registries-v0"::equals);
            if (contentRegistriesDebugDisabled) {
                check(helper, fabricPathHooks == null,
                    "Loader debug exclusion must remove the nested content-registry config");
            } else {
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
            }
            check(helper, fabricInteractionHooks != null,
                "full Fabric API must expose its interaction BlockStateBase transformation");
            check(helper, fabricInteractionHooks.claims().contains(
                new ForeignMixinScanner.TargetClaim(
                    "net.fabricmc.fabric.mixin.event.interaction.BlockBehaviourBlockStateBaseMixin",
                    "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")),
                "scanner must retain the second full-FAPI BlockStateBase claim");
            // Every allowlisted family, not a fixed pair: failing closed means nothing is left
            // dispatching, so this assertion has to grow whenever the allowlist does.
            check(helper, oldDenials.equals(SafetyGate.allowlisted()),
                "stock full Fabric API must fail closed for every family until its separate "
                    + "interaction BlockStateBase mixin is independently audited");
            SafetyGate.deniedBySafety.clear();
            cfg.enabled = true;
            cfg.allowModdedMobAsync = false;
            cfg.repathToleranceBlocks = 0;

        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 5; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        }

        Mob coordinateMob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 1);
        Mob zeroSpeedMob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 2);
        Mob nanSpeedMob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 3);
        Mob entityMob = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 4);
        Mob targetMob = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 6, 2, 4);
        coordinateMob.setOnGround(true);
        zeroSpeedMob.setOnGround(true);
        nanSpeedMob.setOnGround(true);
        entityMob.setOnGround(true);
        targetMob.setOnGround(true);
        PathNavigation queryNav = coordinateMob.getNavigation();
        PathNavigation zeroSpeedNav = zeroSpeedMob.getNavigation();
        PathNavigation nanSpeedNav = nanSpeedMob.getNavigation();
        PathNavigation entityNav = entityMob.getNavigation();
        BlockPos target = helper.absolutePos(new BlockPos(6, 2, 1));
        BlockPos zeroTarget = helper.absolutePos(new BlockPos(6, 2, 2));
        BlockPos nanTarget = helper.absolutePos(new BlockPos(6, 2, 3));
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
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, -0.25);
            boolean zeroAccepted = zeroSpeedNav.moveTo(
                zeroTarget.getX() + 0.5, zeroTarget.getY(), zeroTarget.getZ() + 0.5, 0.0);
            boolean nanAccepted = nanSpeedNav.moveTo(
                nanTarget.getX() + 0.5, nanTarget.getY(), nanTarget.getZ() + 0.5, Double.NaN);
            boolean entityAccepted = entityNav.moveTo(targetMob, 1.0);
            boolean refreshedEntityAccepted = entityNav.moveTo(targetMob, -0.25);

            check(helper, coordinateAccepted, "accepted deferred coordinate move must report success");
            check(helper, zeroAccepted, "accepted zero-speed move must report success");
            check(helper, nanAccepted, "accepted NaN-speed move must report success");
            check(helper, entityAccepted, "accepted deferred entity move must report success");
            check(helper, refreshedEntityAccepted, "same-target pending move must remain accepted");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                "coordinate move must dispatch one async request");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(entityMob.getId()),
                "entity move must dispatch one async request");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(zeroSpeedMob.getId()),
                "zero-speed move must dispatch one async request");
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(nanSpeedMob.getId()),
                "NaN-speed move must dispatch one async request");
            check(helper, runtimeCounter("dispatched") == baseDispatched + 4,
                "exactly four distinct movement requests must dispatch; same-target refresh must not");

            coordinateMob.setOnGround(false);
            setTimeLastRecompute(queryNav, -100L);
            queryNav.recomputePath();
            check(helper, !PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                "airborne recompute must supersede pre-change pending work before canUpdatePath rejects replacement");
            check(helper, runtimeCounter("dispatched") == baseDispatched + 4,
                "airborne recompute must not dispatch while vanilla canUpdatePath is false");
            check(helper, runtimeCounter("discarded") == baseDiscarded + 1,
                "airborne recompute must account the superseded pre-change request");

            coordinateMob.setOnGround(true);

            // These mobs use WalkNodeEvaluator, whose prepare/done are onPathfindingStart/Done hooks
            // rather than a malus save/restore pair, so the owed-epilogue dispatch guard deliberately
            // does not apply to them and supersede-then-redispatch within a tick still works. The
            // guard is scoped to AmphibiousNodeEvaluator and its Frog subclass; see
            // EntityInstallSink.owesEpilogue.
            check(helper, queryNav.moveTo(target.getX() + 0.5, target.getY(),
                target.getZ() + 0.5, -0.25),
                "movement must be accepted again after the airborne recompute supersedes old work");
            check(helper, runtimeCounter("dispatched") == baseDispatched + 5,
                "post-airborne movement must dispatch one replacement request");

            setTimeLastRecompute(queryNav, -100L);
            queryNav.recomputePath();
            check(helper, PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                "recompute must replace same-target accepted pending work");
            check(helper, runtimeCounter("dispatched") == baseDispatched + 6,
                "pending recompute must dispatch one fresh request from current world facts");
            check(helper, runtimeCounter("discarded") == baseDiscarded + 2,
                "pending recompute must account both superseded requests exactly");

            pollUntil(helper, 600, () -> helper.getTick() >= 25
                    && !PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId())
                    && !PathWeaverRuntime.get().entitySink().isRegistered(entityMob.getId())
                    && !PathWeaverRuntime.get().entitySink().isRegistered(zeroSpeedMob.getId())
                    && !PathWeaverRuntime.get().entitySink().isRegistered(nanSpeedMob.getId())
                    && queryNav.getPath() != null
                    && entityNav.getPath() != null
                    && zeroSpeedNav.getPath() != null
                    && nanSpeedNav.getPath() != null
                    && runtimeCounter("installed") == baseInstalled + 4,
                "explicit movement requests did not all install before the deadline", teardown, () -> {
                check(helper, sameDoubleBits(speedModifier(queryNav), -0.25),
                    "recompute replacement must preserve the accepted movement speed exactly");
                check(helper, sameDoubleBits(speedModifier(zeroSpeedNav), 0.0),
                    "zero speed must survive deferred installation exactly");
                check(helper, sameDoubleBits(speedModifier(nanSpeedNav), Double.NaN),
                    "NaN speed must survive deferred installation exactly");
                check(helper, sameDoubleBits(speedModifier(entityNav), -0.25),
                    "same-target pending refresh must replace the captured install speed");
                zeroSpeedNav.stop();
                nanSpeedNav.stop();
                entityNav.stop();
                queryNav.recomputePath();
                check(helper, PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId()),
                    "recomputePath must arm and dispatch async path creation");
                check(helper, runtimeCounter("dispatched") == baseDispatched + 7,
                    "recompute must contribute exactly one additional dispatch");
                pollUntil(helper, 1300, () ->
                        !PathWeaverRuntime.get().entitySink().isRegistered(coordinateMob.getId())
                        && queryNav.getPath() != null
                        && runtimeCounter("installed") == baseInstalled + 5,
                    "recompute request did not install before the deadline", teardown, () -> {
                        cfg.enabled = true;
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

                        cfg.enabled = false;
                        long beforeMasterOffDispatch = runtimeCounter("dispatched");
                        BlockPos masterOffTarget = drifted.offset(1, 0, 0);
                        check(helper, queryNav.moveTo(masterOffTarget.getX() + 0.5, masterOffTarget.getY(),
                            masterOffTarget.getZ() + 0.5, 1.0),
                            "master OFF must fall through to vanilla synchronous routing");
                        check(helper, queryNav.getPath() != reusable,
                            "master OFF must gate repath elision as well as async dispatch");
                        check(helper, runtimeCounter("dispatched") == beforeMasterOffDispatch,
                            "eligible master OFF must contribute zero new async dispatches");
                        check(helper, !PathWeaverRuntime.get().entitySink()
                                .isRegistered(coordinateMob.getId()),
                            "eligible master OFF must not create a worker registration");
                        cfg.enabled = true;
                        synchronized (SafetyGate.deniedBySafety) {
                            SafetyGate.deniedBySafety.clear();
                            SafetyGate.deniedBySafety.addAll(oldDenials);
                        }

                        double oldX = coordinateMob.getX();
                        double oldY = coordinateMob.getY();
                        double oldZ = coordinateMob.getZ();
                        BlockPos beforeRejectedTarget = targetPos(queryNav);
                        coordinateMob.setPos(oldX, coordinateMob.level().getMinY() - 1.0, oldZ);
                        check(helper, !queryNav.moveTo(target.getX() + 0.5, target.getY(),
                            target.getZ() + 0.5, 1.0),
                            "below-minY vanilla precondition must win over tolerance reuse");
                        check(helper, java.util.Objects.equals(beforeRejectedTarget, targetPos(queryNav)),
                            "rejected below-minY request must not advance target intent");
                        coordinateMob.setPos(oldX, oldY, oldZ);
                        coordinateMob.setOnGround(true);

                        setTimeLastRecompute(queryNav, -100L);
                        long beforeFinalDispatch = runtimeCounter("dispatched");
                        long beforeFinalInstall = runtimeCounter("installed");
                        queryNav.recomputePath();
                        check(helper, queryNav.getPath() != null,
                            "denied recompute must complete through vanilla synchronously");
                        check(helper, runtimeCounter("dispatched") == beforeFinalDispatch,
                            "stock full-FAPI Walk denial must prevent a recompute dispatch");
                        check(helper, runtimeCounter("installed") == beforeFinalInstall,
                            "synchronous denied recompute must not report an async install");
                        startExactSwimProof(helper, cfg, teardown);
                    });
            });
        } catch (Throwable t) {
            teardown.run();
            throw t;
        }
    }

    private static void startExactSwimProof(GameTestHelper helper, PathWeaverConfig cfg,
                                            Runnable teardown) {
        check(helper, SafetyGate.deniedBySafety.contains(WalkNodeEvaluator.class),
            "normal full-FAPI must retain the independent Walk denial");
        check(helper, SafetyGate.deniedBySafety.contains(SwimNodeEvaluator.class),
            "normal full-FAPI must deny Swim through its separately unaudited interaction mixin");
        // The content-registry tuple itself is audited, but aggregate Fabric API also ships an
        // independent BlockStateBase interaction mixin. Keep production fail-closed; clear only
        // that Swim denial here to retain a live routing/cache-isolation witness for the prototype.
        synchronized (SafetyGate.deniedBySafety) {
            SafetyGate.deniedBySafety.remove(SwimNodeEvaluator.class);
        }
        cfg.enabled = true;
        cfg.allowModdedMobAsync = false;

        // Sealed on all four sides. Left open, the water drains within a tick and the delayed check
        // below finds a cod sitting on dry stone -- at which point vanilla correctly refuses to
        // produce a swim path, moveTo returns false, and the failure reads as though the gate or the
        // dispatch were broken. It failed about one clean build in five that way, and the diagnostic
        // that finally caught it just said inWater=false.
        for (int x = 0; x <= 8; x++) {
            for (int z = 7; z <= 11; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
                boolean wall = x == 0 || x == 8 || z == 7 || z == 11;
                helper.setBlock(x, 2, z, wall ? Blocks.STONE : Blocks.WATER);
                helper.setBlock(x, 3, z, wall ? Blocks.STONE : Blocks.WATER);
            }
        }
        Mob swimmer = helper.spawnWithNoFreeWill(EntityType.COD, 1, 2, 8);
        PathNavigation navigation = swimmer.getNavigation();
        check(helper, nodeEvaluator(navigation).getClass() == SwimNodeEvaluator.class,
            "live proof requires the exact SwimNodeEvaluator class, not a subclass or Amphibious");

        // Wait for the cod to actually be waterborne rather than assuming it is after one tick.
        //
        // A fixed one-tick delay failed roughly one clean build in five, always with inWater=false:
        // a water-bound navigation refuses to path at all when its mob is not in water, so moveTo
        // returned false and the failure read as though the gate or the dispatch were broken. It was
        // neither. Sealing the pool cut the rate but did not remove it, because the real defect is
        // that the test asserted a precondition instead of establishing one. What this proves is
        // async routing for a swimming mob; being in water is a precondition, and waiting for it is
        // correct test design rather than a weakened assertion.
        pollUntilWaterborne(helper, swimmer, teardown, () -> {
            try {
                proveRuntimeCacheIsolation(helper, swimmer);
                long beforeDispatch = runtimeCounter("dispatched");
                long beforeInstall = runtimeCounter("installed");
                BlockPos target = helper.absolutePos(new BlockPos(6, 2, 8));
                boolean swimAccepted = navigation.moveTo(target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 1.0);
                // This fails on roughly one clean build in five and the bare message says nothing
                // about why. Everything that could plausibly make moveTo return false is reported
                // here rather than guessed at afterwards.
                check(helper, swimAccepted, "test-cleared exact Swim movement must be accepted"
                    + " [allowedSwim=" + SafetyGate.isAllowed(SwimNodeEvaluator.class)
                    + ", deniedNow=" + SafetyGate.deniedBySafety.size()
                    + ", alive=" + swimmer.isAlive()
                    + ", inWater=" + swimmer.isInWater()
                    + ", removed=" + swimmer.isRemoved()
                    + ", enabled=" + cfg.enabled
                    + ", registered=" + PathWeaverRuntime.get().entitySink().isRegistered(swimmer.getId())
                    + ", dispatchedDelta=" + (runtimeCounter("dispatched") - beforeDispatch)
                    + ", path=" + (navigation.getPath() == null ? "null"
                        : navigation.getPath().getNodeCount() + " nodes")
                    + ", pos=" + swimmer.blockPosition() + ", target=" + target
                    + ", blockAtMob=" + helper.getLevel().getBlockState(swimmer.blockPosition())
                    + ", fluidAtMob=" + helper.getLevel().getFluidState(swimmer.blockPosition())
                    + ", blockAtTarget=" + helper.getLevel().getBlockState(target)
                    + ", exactY=" + swimmer.getY()
                    + "]");
                check(helper, PathWeaverRuntime.get().entitySink().isRegistered(swimmer.getId()),
                    "test-cleared exact Swim must create a real worker registration");
                check(helper, runtimeCounter("dispatched") == beforeDispatch + 1,
                    "test-cleared exact Swim must contribute one real async dispatch");

                pollUntil(helper, 1300, () ->
                        !PathWeaverRuntime.get().entitySink().isRegistered(swimmer.getId())
                            && navigation.getPath() != null
                            && runtimeCounter("installed") == beforeInstall + 1,
                    "exact Swim request did not install before the deadline", teardown, () -> {
                        navigation.stop();
                        cfg.enabled = false;
                        long offDispatch = runtimeCounter("dispatched");
                        BlockPos offTarget = helper.absolutePos(new BlockPos(6, 2, 10));
                        check(helper, navigation.moveTo(offTarget.getX() + 0.5, offTarget.getY(),
                            offTarget.getZ() + 0.5, 1.0),
                            "master OFF exact Swim must fall through to vanilla synchronous routing");
                        check(helper, runtimeCounter("dispatched") == offDispatch,
                            "master OFF must prevent a new exact Swim dispatch");
                        check(helper, !PathWeaverRuntime.get().entitySink().isRegistered(swimmer.getId()),
                            "master OFF must not register exact Swim work");
                        check(helper, navigation.getPath() != null,
                            "master OFF exact Swim must still produce a synchronous vanilla path");
                        teardown.run();
                        helper.succeed();
                    });
            } catch (Throwable t) {
                teardown.run();
                throw t;
            }
        });
    }

    private static void proveRuntimeCacheIsolation(GameTestHelper helper, Mob swimmer) {
        PathWorkerPool pool = new PathWorkerPool();
        pool.start(1, 1);
        try {
            ServerLevel level = (ServerLevel) swimmer.level();
            PathTypeCache shared = level.getPathTypeCache();
            PathfindingContext mainContext = new PathfindingContext(level, swimmer);
            check(helper, contextCache(mainContext) == shared,
                "main-thread PathfindingContext must retain the ServerLevel shared cache");

            BlockPos center = swimmer.blockPosition();
            PathNavigationRegion region = new PathNavigationRegion(level,
                center.offset(-8, -8, -8), center.offset(8, 8, 8));
            SwimNodeEvaluator evaluator = new SwimNodeEvaluator(false);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<PathTypeCache> workerCache = new AtomicReference<>();
            AtomicReference<PathOutcome> outcome = new AtomicReference<>();
            AtomicBoolean callbackMarked = new AtomicBoolean(true);
            boolean submitted = pool.submit(new PathRequest(new RequestKey(1L, 1L, swimmer.getId()),
                0L, () -> {
                    evaluator.prepare(region, swimmer);
                    try {
                        workerCache.set(contextCache(evaluatorContext(evaluator)));
                    } finally {
                        evaluator.done();
                    }
                    return null;
                }, result -> {
                    outcome.set(result);
                    callbackMarked.set(PathWeaverThread.isWorker());
                    done.countDown();
                }));
            check(helper, submitted, "cache-isolation witness must enter a real PathWorkerPool search");
            try {
                check(helper, done.await(5, TimeUnit.SECONDS),
                    "cache-isolation witness did not terminate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw helper.assertionException("cache-isolation witness interrupted");
            }
            check(helper, outcome.get() != null && outcome.get().status() == PathOutcome.Status.NO_PATH,
                "cache-isolation witness search must terminate without failure");
            check(helper, workerCache.get() != null && workerCache.get() != shared,
                "async exact Swim context must use a fresh cache, never ServerLevel's shared cache");
            check(helper, !callbackMarked.get(),
                "PathWorkerPool must clear its worker marker before completion delivery");
            check(helper, pool.inFlight() == 0,
                "cache-isolation witness must balance worker capacity");
        } finally {
            pool.shutdown();
        }
    }

    private static PathfindingContext evaluatorContext(NodeEvaluator evaluator) {
        try {
            Field field = NodeEvaluator.class.getDeclaredField("currentContext");
            field.setAccessible(true);
            return (PathfindingContext) field.get(evaluator);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect NodeEvaluator.currentContext", e);
        }
    }

    private static PathTypeCache contextCache(PathfindingContext context) {
        try {
            Field field = PathfindingContext.class.getDeclaredField("cache");
            field.setAccessible(true);
            return (PathTypeCache) field.get(context);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect PathfindingContext.cache", e);
        }
    }

    private static Object nodeEvaluator(PathNavigation navigation) {
        try {
            Field field = PathNavigation.class.getDeclaredField("nodeEvaluator");
            field.setAccessible(true);
            return field.get(navigation);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect PathNavigation.nodeEvaluator", e);
        }
    }


    /**
     * Run {@code body} on the first tick the mob is genuinely in water, or fail saying it never was.
     *
     * <p>Bounded, so a genuinely dry mob still fails the suite rather than hanging it.
     */
    private static void pollUntilWaterborne(GameTestHelper helper, Mob swimmer, Runnable teardown,
                                            Runnable body) {
        helper.runAfterDelay(1, () -> {
            try {
                if (swimmer.isInWater()) {
                    body.run();
                } else if (swimmer.tickCount == 0) {
                    // The cod is alive, unremoved, and standing in a water source, but the server
                    // has never ticked it -- tickCount stays 0 for the full 200-tick budget in about
                    // one clean build in twenty. Its in-water flag is cached and only refreshed
                    // during an entity tick, and a water-bound navigation refuses to path at all
                    // while that flag is false, so moveTo returned false and the failure read as a
                    // broken gate. Ticking it here establishes the precondition the test needs
                    // rather than waiting for a scheduler that may never get to it. Nothing about
                    // the async assertions below is relaxed by this.
                    swimmer.tick();
                    pollUntilWaterborne(helper, swimmer, teardown, body);
                } else if (helper.getTick() >= 200) {
                    throw helper.assertionException("the cod never became waterborne, so the swim "
                        + "proof could not start [pos=" + swimmer.blockPosition()
                        + ", block=" + helper.getLevel().getBlockState(swimmer.blockPosition())
                        + ", fluid=" + helper.getLevel().getFluidState(swimmer.blockPosition())
                        + ", tickCount=" + swimmer.tickCount
                        + ", alive=" + swimmer.isAlive() + ", removed=" + swimmer.isRemoved()
                        + ", y=" + swimmer.getY() + "]");
                } else {
                    pollUntilWaterborne(helper, swimmer, teardown, body);
                }
            } catch (Throwable t) {
                teardown.run();
                throw t;
            }
        });
    }

    private static void pollUntil(GameTestHelper helper, long deadline, BooleanSupplier ready,
                                  String timeoutMessage, Runnable teardown, Runnable onReady) {
        helper.runAfterDelay(1, () -> {
            try {
                if (ready.getAsBoolean()) {
                    onReady.run();
                } else if (helper.getTick() >= deadline) {
                    // Say what the counters were doing. A bare "did not install" cost a full
                    // investigation to attribute once already: the request had been dispatched and
                    // then discarded as stale, which reads identically to "still waiting" unless
                    // the numbers are in the message.
                    throw helper.assertionException(timeoutMessage
                        + " [dispatched=" + runtimeCounter("dispatched")
                        + ", installed=" + runtimeCounter("installed")
                        + ", discarded=" + runtimeCounter("discarded")
                        + ", maxResultAgeTicks=" + PathWeaverConfig.get().maxResultAgeTicks + "]");
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

    private static boolean sameDoubleBits(double actual, double expected) {
        return Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(expected);
    }

    private static long runtimeCounter(String name) {
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

    private static void restore(PathWeaverConfig cfg, boolean enabled,
                                boolean moddedMobOverride, int tolerance) {
        cfg.enabled = enabled;
        cfg.allowModdedMobAsync = moddedMobOverride;
        cfg.repathToleranceBlocks = tolerance;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) throw helper.assertionException(message);
    }
}
