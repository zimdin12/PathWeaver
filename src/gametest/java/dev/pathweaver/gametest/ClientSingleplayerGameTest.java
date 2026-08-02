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
                    if (s.label().equals("drowned")) malusInFlight = mob.getPathfindingMalus(PathType.WALKABLE);
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
                report.add("dispatchedDelta=" + (PathWeaverRuntime.get().dispatchedCount() - dispatchedBefore));
                report.add("drownedMalus " + malusBefore + " -> " + malusInFlight);
                return report;
            });
            for (String line : results) System.out.println("PW_CLIENT " + line);

            context.waitTicks(60);

            List<String> after = singleplayer.getServer().computeOnServer(server -> List.of(
                "installed=" + PathWeaverRuntime.get().installedCount(),
                "discarded=" + PathWeaverRuntime.get().discardedCount(),
                "dispatched=" + PathWeaverRuntime.get().dispatchedCount()));
            for (String line : after) System.out.println("PW_CLIENT " + line);

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
            System.out.println("PW_CLIENT screenshot="
                + context.takeScreenshot("pathweaver-config-general"));

            context.setScreen(() -> null);
            context.waitTicks(5);
            System.out.println("PW_CLIENT DONE");
        }
    }
}
