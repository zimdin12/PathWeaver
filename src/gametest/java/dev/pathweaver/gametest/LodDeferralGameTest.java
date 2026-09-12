package dev.pathweaver.gametest;

import dev.pathweaver.config.PathWeaverConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;

import java.lang.reflect.Field;

/**
 * Distance LOD DELAYS a route refresh. It must not DROP one.
 *
 * <p>Vanilla's recomputePath refuses to search twice inside 21 ticks, and when it refuses it sets
 * {@code hasDelayedRecomputation}, so {@code PathNavigation.tick} retries until the search happens. The
 * first LOD hook cancelled at HEAD without setting that flag. Nothing retried the cancelled refresh, so
 * a block changed once near a distant mob's route was never acted on at all. The distance-LOD campaign
 * found it indirectly, as a ratio lower than the refresh floor allows, and the bytecode explained it;
 * this is the test that watches it happen in a running server.
 *
 * <p>Two phases, in order, on one mob, and the first is not optional:
 *
 * <ol>
 *   <li><b>Control, LOD off.</b> One collision-changing block change on the route, inside vanilla's own
 *       window. The route must be recomputed at about tick 21. If it is not, this scenario cannot see a
 *       recompute at all, and a failure in phase 2 would mean nothing.</li>
 *   <li><b>LOD on.</b> The same single change inside LOD's 40-tick window. The refresh must be held
 *       pending, visible the very next tick as the flag vanilla uses, and must happen at about tick 40.
 *       Not sooner, which would mean nothing was throttled; not never, which is the defect.</li>
 * </ol>
 *
 * <p>There are no players in a game-test world, so every mob is beyond any LOD distance. The mob moves
 * at speed 0 so its route stays live and untouched by anything but the recompute under test, and the
 * mod's async path is off so the recompute is synchronous and lands on the tick it happens.
 */
public final class LodDeferralGameTest {
    public LodDeferralGameTest() {}

    /** The framework can place one @GameTest method twice; see WorkerFailureBreakerGameTest. */
    private static final java.util.concurrent.atomic.AtomicBoolean CLAIMED =
        new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean COMPLETED =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static final int VANILLA_FLOOR = 21;
    private static final int LOD_INTERVAL = 40;

    @GameTest(maxTicks = 600)
    public void aThrottledRefreshIsDelayedNotDropped(GameTestHelper helper) {
        if (!CLAIMED.compareAndSet(false, true)) {
            helper.onEachTick(() -> {
                if (COMPLETED.get()) {
                    helper.succeed();
                } else if (helper.getTick() >= 560) {
                    throw helper.assertionException("no instance of this test ever ran the scenario, "
                        + "so a green result here would mean nothing");
                }
            });
            return;
        }
        Scenario[] scenario = new Scenario[1];
        helper.onEachTick(() -> {
            if (helper.getTick() < 20) return;
            if (scenario[0] == null) scenario[0] = new Scenario(helper);
            scenario[0].tick();
        });
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final PathWeaverConfig cfg = PathWeaverConfig.get();
        private final boolean oldEnabled = cfg.enabled;
        private final boolean oldLod = cfg.lodEnabled;
        private final int oldInterval = cfg.lodIntervalTicks;
        private final int oldDistance = cfg.lodMinDistanceBlocks;
        private Mob mob;
        private PathNavigation nav;
        private Path before;
        private long event;
        private int stage;
        private final BlockPos onRoute = new BlockPos(5, 2, 2);

        Scenario(GameTestHelper helper) { this.helper = helper; }

        void tick() {
            try {
                switch (stage) {
                    case 0 -> build();
                    case 1 -> controlChange();
                    case 2 -> awaitControlRecompute();
                    case 3 -> lodChange();
                    case 4 -> refreshIsPending();
                    case 5 -> awaitLodRecompute();
                    default -> { }
                }
            } catch (Throwable failure) {
                cleanup();
                throw failure;
            }
        }

        private void build() {
            cfg.enabled = false;
            cfg.lodEnabled = false;
            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 4; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            }
            mob = helper.spawnWithNoFreeWill(VanillaTypes.mob(VanillaTypes.ZOMBIE), 1, 2, 2);
            mob.setOnGround(true);
            nav = mob.getNavigation();
            BlockPos target = helper.absolutePos(new BlockPos(10, 2, 2));
            nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.0);
            stage = 1;
        }

        private void controlChange() {
            check(nav.getPath() != null && !nav.isDone(), "precondition: the mob has no live route to refresh");
            before = nav.getPath();
            stampNow();
            event = helper.getTick();
            helper.setBlock(onRoute, Blocks.STONE_SLAB);   // a collision change, so vanilla looks at routes; a name both versions have
            stage = 2;
        }

        private void awaitControlRecompute() {
            long waited = helper.getTick() - event;
            if (nav.getPath() == before) {
                if (waited > VANILLA_FLOOR + 20) {
                    throw helper.assertionException("CONTROL FAILED: with LOD off, vanilla never recomputed "
                        + "the route " + waited + " ticks after a block change on it. This scenario cannot "
                        + "see a recompute, so nothing it says about LOD would mean anything.");
                }
                return;
            }
            check(waited >= VANILLA_FLOOR - 3 && waited <= VANILLA_FLOOR + 9,
                "control: vanilla recomputed after " + waited + " ticks, expected about " + VANILLA_FLOOR
                    + "; the stamp or the change did not behave as this test assumes");
            stage = 3;
        }

        private void lodChange() {
            cfg.lodEnabled = true;
            cfg.lodIntervalTicks = LOD_INTERVAL;
            cfg.lodMinDistanceBlocks = 16;
            check(!delayedFlag(), "precondition: a refresh is already pending before the LOD change");
            before = nav.getPath();
            stampNow();
            event = helper.getTick();
            helper.setBlock(onRoute, Blocks.AIR);
            stage = 4;
        }

        private void refreshIsPending() {
            check(delayedFlag(), "LOD throttled the refresh and did not keep it pending: "
                + "hasDelayedRecomputation is false, so nothing will ever retry it and the block change is "
                + "dropped rather than delayed");
            stage = 5;
        }

        private void awaitLodRecompute() {
            long waited = helper.getTick() - event;
            if (nav.getPath() == before) {
                if (waited > LOD_INTERVAL + 30) {
                    throw helper.assertionException("LOD dropped the refresh: " + waited + " ticks after a "
                        + "block change on the route, with a " + LOD_INTERVAL + "-tick interval, the route was "
                        + "never recomputed");
                }
                return;
            }
            check(waited >= LOD_INTERVAL - 3,
                "LOD did not throttle: the route was recomputed after " + waited + " ticks, inside the "
                    + LOD_INTERVAL + "-tick interval");
            check(waited <= LOD_INTERVAL + 9,
                "the throttled refresh came " + waited + " ticks after the change, far past the "
                    + LOD_INTERVAL + "-tick interval");
            cleanup();
            COMPLETED.set(true);
            helper.succeed();
        }

        private void stampNow() {
            try {
                Field f = PathNavigation.class.getDeclaredField("timeLastRecompute");
                f.setAccessible(true);
                f.setLong(nav, helper.getLevel().getGameTime());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not set PathNavigation.timeLastRecompute", e);
            }
        }

        private boolean delayedFlag() {
            try {
                Field f = PathNavigation.class.getDeclaredField("hasDelayedRecomputation");
                f.setAccessible(true);
                return f.getBoolean(nav);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not read PathNavigation.hasDelayedRecomputation", e);
            }
        }

        private void check(boolean condition, String message) {
            if (!condition) throw helper.assertionException(message);
        }

        private void cleanup() {
            cfg.enabled = oldEnabled;
            cfg.lodEnabled = oldLod;
            cfg.lodIntervalTicks = oldInterval;
            cfg.lodMinDistanceBlocks = oldDistance;
            if (mob != null) mob.discard();
        }
    }
}
