package dev.pathweaver.mixin;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.PathRequest;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import dev.pathweaver.gate.SafetyGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Feature A: dispatch the innermost {@code createPath} A* search to the worker pool when the mob's
 * evaluator is allowlisted. The region is built identically to vanilla (same radius formula) so the
 * async result is byte-for-byte the path vanilla would have produced; it is installed next tick via
 * vanilla's own {@code moveTo(path, speedModifier)}. On any guard hit or pool saturation we simply
 * do NOT cancel — vanilla runs synchronously, unchanged.
 */
@Mixin(net.minecraft.world.entity.ai.navigation.PathNavigation.class)
public abstract class PathNavigationMixin implements PWNavigation {

    @Shadow @org.spongepowered.asm.mixin.Final protected Mob mob;
    @Shadow @org.spongepowered.asm.mixin.Final protected Level level;
    @Shadow protected Path path;
    @Shadow protected NodeEvaluator nodeEvaluator;
    @Shadow @org.spongepowered.asm.mixin.Final private PathFinder pathFinder;
    @Shadow private BlockPos targetPos;
    @Shadow private float maxVisitedNodesMultiplier;
    @Shadow protected double speedModifier;

    @Shadow protected abstract boolean canUpdatePath();
    @Shadow public abstract boolean moveTo(Path path, double speed);

    @Inject(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void pathweaver$asyncCreatePath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward,
                                            int reachRange, float followRange,
                                            CallbackInfoReturnable<Path> cir) {
        PathWeaverConfig cfg = PathWeaverConfig.get();

        // Feature B: conservative repath elision. Independent of async + gate. Reuse the live path
        // when a requested target is within tolerance of the current target (widens vanilla's exact
        // match). tolerance 0 == vanilla behaviour.
        if (cfg.repathElisionEnabled
                && this.path != null && !this.path.isDone()
                && dev.pathweaver.elision.RepathTolerance.anyWithinTolerance(
                        targets, this.targetPos, cfg.repathToleranceBlocks)) {
            cir.setReturnValue(this.path);
            return;
        }

        // Feature A: async dispatch.
        if (!cfg.asyncEnabled || cfg.syncFallbackOnly) return;
        PathWeaverRuntime rt = PathWeaverRuntime.get();
        if (!rt.isRunning()) return;
        if (!(this.level instanceof ServerLevel)) return;                       // server-side only
        if (this.nodeEvaluator == null || !SafetyGate.isAllowed(this.nodeEvaluator.getClass())) return;

        // Replicate vanilla's cheap guards; on any of these, let vanilla run synchronously (no cancel).
        if (targets.isEmpty()) return;
        if (this.mob.getY() < this.level.getMinY()) return;
        if (!canUpdatePath()) return;
        if (this.path != null && !this.path.isDone() && targets.contains(this.targetPos)) return;

        // Build the region EXACTLY as vanilla does (guarantees async == sync).
        BlockPos mobPos = offsetUpward ? this.mob.blockPosition().above() : this.mob.blockPosition();
        int radius = (int) (followRange + (float) regionOffset);
        PathNavigationRegion region = new PathNavigationRegion(this.level,
            mobPos.offset(-radius, -radius, -radius), mobPos.offset(radius, radius, radius));

        // Capture everything the worker needs; it must not read live mob/world state beyond this.
        final Set<BlockPos> targetsCopy = new HashSet<>(targets);
        final PathFinder finder = this.pathFinder;
        final Mob theMob = this.mob;
        final float mult = this.maxVisitedNodesMultiplier;
        final int entityId = theMob.getId();
        final double dx = theMob.getX(), dy = theMob.getY(), dz = theMob.getZ();
        final long tick = ((ServerLevel) this.level).getServer().getTickCount();

        Callable<Path> search = () -> finder.findPath(region, theMob, targetsCopy, followRange, reachRange, mult);

        EntityInstallSink sink = rt.entitySink();
        sink.register(entityId, this);
        boolean accepted = rt.pool().submit(new PathRequest(entityId, tick, search,
            result -> rt.installer().enqueue(entityId, tick, result, dx, dy, dz)));

        if (!accepted) {
            sink.discard(entityId);   // pool saturated -> let vanilla run synchronously
            return;
        }
        // Keep moving on the current path this tick; the async result installs next tick.
        cir.setReturnValue(this.path);
    }

    // ---- PWNavigation duck ----

    @Override
    public void pathweaver$install(Path path) {
        // Vanilla's own install path: handles sameAs/trim/stuck bookkeeping. speedModifier already
        // holds the caller's intended speed (set when it called moveTo(oldPath, speed) this cycle).
        moveTo(path, this.speedModifier);
    }

    @Override
    public boolean pathweaver$stale(double dispatchX, double dispatchY, double dispatchZ) {
        if (this.mob == null || !this.mob.isAlive()) return true;
        double moveThreshold = PathWeaverConfig.get().stalenessMoveThreshold;
        return this.mob.distanceToSqr(dispatchX, dispatchY, dispatchZ) > moveThreshold * moveThreshold;
    }
}
