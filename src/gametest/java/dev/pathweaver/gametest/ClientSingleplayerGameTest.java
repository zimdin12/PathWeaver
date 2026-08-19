package dev.pathweaver.gametest;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.gate.SafetyGate;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * The surface every other test in this project misses: a real client, running a real singleplayer
 * world.
 *
 * <p>Everything else here runs against a dedicated server. Singleplayer is not that — it is an
 * integrated server sharing a process with a client that is rendering, and it is how most people who
 * install this mod will actually run it. Two things are only reachable from here:
 *
 * <ul>
 *   <li>whether searches dispatch and install on the integrated server at all, and
 *   <li>whether the settings screen renders, which is client-only code that a dedicated-server test
 *       cannot load. A previous release shipped a config screen showing raw enum constants because
 *       nothing ever drew it, and 0.4.0 renamed a tier and rewrote every tooltip in that screen.
 * </ul>
 *
 * <p>Only the General category is captured. It holds every setting 0.4.0 changed — the master
 * switch, the modded-mob toggle and the compatibility tier — and screenshotting it caught two labels
 * that rendered truncated, including an "(unsafe)" marker that was being cut off exactly where it
 * mattered. Cloth's category tabs are not buttons this API can click, so the other two categories
 * are covered only by the contract test asserting each has a translated name and at least one
 * visible option. They contain no strings this release touched.
 */
public final class ClientSingleplayerGameTest implements FabricClientGameTest {

    // Captured on the server thread, asserted on the client thread. Same JVM: this is an integrated
    // server, so there is no serialisation boundary between the two.
    private static volatile int acceptedCount;
    private static volatile long dispatchedDelta;
    private static volatile long installedBefore;
    private static volatile int drownedId = -1;
    private static volatile float drownedMalusBefore = Float.NaN;
    private static volatile float drownedMalusInFlight = Float.NaN;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("client singleplayer: " + message);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(20);

            List<String> results = singleplayer.getServer().computeOnServer(server -> {
                List<String> report = new ArrayList<>();
                ServerLevel level = server.overworld();

                report.add("runtime running=" + PathWeaverRuntime.get().isRunning());
                report.add("workers=" + PathWeaverRuntime.get().pool().threads());

                // NOT pinned by the runGameTest tier seeding -- the client harness runs under a
                // different task and run directory, so it takes the shipped default (UNSAFE).
                // This used to read "the scan should open the gate on its own", which was true while
                // the default was AUDITED and is not now: the gate opens because checking is off.
                // Reported rather than asserted, so nothing here silently depends on which it was.
                synchronized (SafetyGate.deniedBySafety) {
                    report.add("deniedFamilies=" + SafetyGate.deniedBySafety.size());
                }

                PathWeaverConfig cfg = PathWeaverConfig.get();
                cfg.enabled = true;
                cfg.maxResultAgeTicks = 1200;
                synchronized (SafetyGate.deniedBySafety) {
                    SafetyGate.deniedBySafety.clear();
                }

                BlockPos origin = new BlockPos(0, 100, 0);
                for (int dx = -12; dx <= 12; dx++) {
                    for (int dz = -12; dz <= 12; dz++) {
                        level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 2);
                        for (int dy = 1; dy <= 4; dy++) {
                            level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }

                record Subject(String label, EntityType<?> type) { }
                long dispatchedBefore = PathWeaverRuntime.get().dispatchedCount();
                installedBefore = PathWeaverRuntime.get().installedCount();
                List<Mob> spawned = new ArrayList<>();
                float malusBefore = Float.NaN;
                float malusInFlight = Float.NaN;

                for (Subject s : List.of(
                        new Subject("zombie", EntityType.ZOMBIE),
                        new Subject("bee", EntityType.BEE),
                        new Subject("drowned", EntityType.DROWNED))) {
                    var entity = s.type().create(level, EntitySpawnReason.COMMAND);
                    if (!(entity instanceof Mob mob)) continue;
                    BlockPos at = origin.offset(-10, 1, spawned.size() * 3 - 3);
                    mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
                    mob.setPersistenceRequired();
                    if (!level.addFreshEntity(mob)) continue;
                    // Ground navigation refuses to path for a mob that is not on the ground, and a
                    // freshly added entity has not ticked, so its ground flag is still false. The
                    // zombie failed here while the bee and drowned passed, because flying and
                    // amphibious navigations have no such precondition -- which reads like a
                    // walk-specific bug and is not one. The server game tests already do this.
                    mob.setOnGround(true);
                    spawned.add(mob);

                    if (s.label().equals("drowned")) malusBefore = mob.getPathfindingMalus(PathType.WALKABLE);
                    BlockPos target = origin.offset(10, 1, 0);
                    boolean accepted = mob.getNavigation().moveTo(
                        target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                    if (s.label().equals("drowned")) {
                        malusInFlight = mob.getPathfindingMalus(PathType.WALKABLE);
                        drownedId = mob.getId();
                    }
                    if (accepted) acceptedCount++;
                    report.add(s.label() + " accepted=" + accepted
                        + " registered=" + PathWeaverRuntime.get().entitySink().isRegistered(mob.getId())
                        + " alive=" + mob.isAlive() + " removed=" + mob.isRemoved()
                        + " onGround=" + mob.onGround() + " tick=" + mob.tickCount
                        + " pos=" + mob.blockPosition()
                        + " below=" + level.getBlockState(mob.blockPosition().below())
                        + " path=" + (mob.getNavigation().getPath() == null ? "null"
                            : mob.getNavigation().getPath().getNodeCount() + "n")
                        + " difficulty=" + level.getDifficulty());
                }
                dispatchedDelta = PathWeaverRuntime.get().dispatchedCount() - dispatchedBefore;
                drownedMalusBefore = malusBefore;
                drownedMalusInFlight = malusInFlight;
                report.add("dispatchedDelta=" + dispatchedDelta);
                report.add("drownedMalus " + malusBefore + " -> " + malusInFlight);
                return report;
            });
            for (String line : results) System.out.println("PW_CLIENT " + line);

            // Everything above this point used to be report-only. The test spawned three mobs,
            // moved them, drew the settings screen, printed what happened and passed unconditionally
            // -- so the one path no other harness covers, singleplayer on an integrated server, had
            // no failing coverage at all. Making PathNavigationMixin return immediately on an
            // integrated server, or AutoConfigClient.getConfigScreen return null, would both have
            // been reported and both have passed.
            check(acceptedCount == 3,
                "all three mobs must accept a move on an integrated server, got " + acceptedCount);
            check(dispatchedDelta >= 3,
                "the integrated server must dispatch off-thread; dispatchedDelta=" + dispatchedDelta);
            check(drownedMalusInFlight == 6.0F,
                "the amphibious prologue must run on the main thread before dispatch, in-flight "
                    + "WALKABLE cost was " + drownedMalusInFlight);

            context.waitTicks(60);

            List<String> after = singleplayer.getServer().computeOnServer(server -> List.of(
                "installed=" + PathWeaverRuntime.get().installedCount(),
                "discarded=" + PathWeaverRuntime.get().discardedCount(),
                "dispatched=" + PathWeaverRuntime.get().dispatchedCount()));
            for (String line : after) System.out.println("PW_CLIENT " + line);

            List<String> settled = singleplayer.getServer().computeOnServer(server -> {
                long installedNow = PathWeaverRuntime.get().installedCount();
                boolean stillPending =
                    PathWeaverRuntime.get().entitySink().isRegistered(drownedId);
                float malusNow = Float.NaN;
                var level = server.overworld();
                if (level.getEntity(drownedId) instanceof Mob drowned) {
                    malusNow = drowned.getPathfindingMalus(PathType.WALKABLE);
                }
                return List.of(String.valueOf(installedNow), String.valueOf(stillPending),
                    String.valueOf(malusNow));
            });
            long installedNow = Long.parseLong(settled.get(0));
            boolean stillPending = Boolean.parseBoolean(settled.get(1));
            float malusNow = Float.parseFloat(settled.get(2));

            check(installedNow > installedBefore,
                "at least one search must install on the integrated server; installed went "
                    + installedBefore + " -> " + installedNow);
            check(!stillPending,
                "the drowned's request must reach a terminal state within 60 ticks");
            check(malusNow == drownedMalusBefore,
                "the amphibious epilogue must give the mob its pathfinding cost back: expected "
                    + drownedMalusBefore + ", found " + malusNow);

            // The settings screen, drawn by the real client on the real render thread. Client-only
            // code that no dedicated-server test can even class-load.
            context.setScreen(() -> me.shedaniel.autoconfig.AutoConfigClient
                .getConfigScreen(PathWeaverConfig.class, null).get());
            // Park the cursor in a corner. Left at screen centre it hovers the first entry and its
            // tooltip covers the rows underneath, which is exactly the part worth looking at.
            context.getInput().setCursorPos(4.0, 4.0);
            context.waitTicks(20);
            String screenName = context.computeOnClient(client ->
                client.screen == null ? "<none>" : client.screen.getClass().getName());
            System.out.println("PW_CLIENT configScreen=" + screenName);
            check(!"<none>".equals(screenName),
                "the settings screen must actually render; this is the only test that draws it");
            System.out.println("PW_CLIENT screenshot="
                + context.takeScreenshot("pathweaver-config-general"));

            // Scroll to the bottom of the same category and capture it too.
            //
            // 0.6.1 adds two settings, and both land below the fold of the shot above -- so the one
            // test that draws this screen was drawing everything EXCEPT the release's new options.
            // That is not hypothetical here: this release very nearly shipped a jar whose lang file
            // was stale, and the tooltip it got wrong belongs to one of these two fields.
            //
            // Driven through mouseScrolled, which is the path a scroll wheel takes, rather than by
            // setting the list's scroll field: the point is to render what a user renders.
            context.runOnClient(client -> {
                for (int i = 0; i < 12; i++) {
                    client.screen.mouseScrolled(
                        client.getWindow().getGuiScaledWidth() / 2.0,
                        client.getWindow().getGuiScaledHeight() / 2.0,
                        0.0, -6.0);
                }
            });
            context.waitTicks(5);
            System.out.println("PW_CLIENT screenshot="
                + context.takeScreenshot("pathweaver-config-general-scrolled"));

            // The other two categories, if Cloth exposes their tabs as clickable buttons. Reported
            // rather than asserted: this is a rendering sweep looking for clipped labels, and a
            // Cloth version that lays tabs out differently should not turn into a red test.
            for (String category : new String[] {
                    "Worker capacity (restart required)", "Repath and result validity"}) {
                if (context.tryClickScreenButton(category)) {
                    context.waitTicks(5);
                    System.out.println("PW_CLIENT screenshot="
                        + context.takeScreenshot("pathweaver-config-"
                            + category.replaceAll("[^A-Za-z]+", "-").toLowerCase()));
                } else {
                    System.out.println("PW_CLIENT category-tab-not-clickable=" + category);
                }
            }

            context.setScreen(() -> null);
            context.waitTicks(5);
            System.out.println("PW_CLIENT DONE");
        }
    }
}
