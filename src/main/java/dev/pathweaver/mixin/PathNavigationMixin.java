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
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 * vanilla's own {@code moveTo(path, speedModifier)} plus a replay of {@code createPath}'s tail. On any
 * guard hit or pool saturation we simply do NOT cancel — vanilla runs synchronously, unchanged.
 */
@Mixin(net.minecraft.world.entity.ai.navigation.PathNavigation.class)
public abstract class PathNavigationMixin implements PWNavigation {

    @Shadow @org.spongepowered.asm.mixin.Final protected Mob mob;
    @Shadow @org.spongepowered.asm.mixin.Final protected Level level;
    @Shadow protected Path path;
    @Shadow protected NodeEvaluator nodeEvaluator;
    @Shadow @org.spongepowered.asm.mixin.Final private PathFinder pathFinder;
    @Shadow private BlockPos targetPos;
    @Shadow private int reachRange;
    @Shadow private float maxVisitedNodesMultiplier;
    @Shadow protected double speedModifier;

    @Shadow protected abstract boolean canUpdatePath();
    @Shadow public abstract boolean moveTo(Path path, double speed);
    // Target method is private; a private stub (not `private abstract`, which is illegal Java) shadows it.
    @Shadow private void resetStuckTimeout() { throw new AssertionError(); }

    // ---- per-in-flight capture (main thread only; the isRegistered guard ensures one search at a time) ----
    @Unique private int pathweaver$pendingReachRange;
    @Unique private boolean pathweaver$callbackStarted;
    // FIX 3a: the goal sets the intended speed by calling moveTo(path, speed) right AFTER createPath
    // returns, so this.speedModifier is stale (or 0) at dispatch. Remember the last requested speed via
    // moveTo so the installed path never moves at speed 0.
    @Unique private double pathweaver$lastRequestedSpeed = 1.0;

    /** FIX 3a: capture the caller's intended speed on every moveTo(path, speed), before its null-check. */
    @Inject(
        method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z",
        at = @At("HEAD")
    )
    private void pathweaver$captureSpeed(Path path, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (speed > 0.0) pathweaver$lastRequestedSpeed = speed;
    }

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

        final Mob theMob = this.mob;
        final int entityId = theMob.getId();
        final long tick = ((ServerLevel) this.level).getServer().getTickCount();
        EntityInstallSink sink = rt.entitySink();

        // FIX 4: this entity's last async search failed and it's in cooldown -> run vanilla sync this tick.
        if (sink.shouldForceSync(entityId, tick)) return;

        // Avoid stacking a second search on a mob that already has one in flight; keep current path.
        if (sink.isRegistered(entityId)) {
            cir.setReturnValue(this.path);
            return;
        }

        // Everything below can fail on unusual mods/data; degrade to sync rather than escape into the
        // entity tick (FIX 6a). If we've already registered in the sink, unwind that registration.
        boolean registered = false;
        try {
            // FIX 2c: force-resolve the mob's step-height attribute on the MAIN thread so the worker's
            // read of maxUpStep() hits a clean cached value instead of lazily writing it off-thread.
            theMob.maxUpStep();

            // Build the region EXACTLY as vanilla does (guarantees async == sync).
            BlockPos mobPos = offsetUpward ? theMob.blockPosition().above() : theMob.blockPosition();
            int radius = (int) (followRange + (float) regionOffset);
            PathNavigationRegion region = new PathNavigationRegion(this.level,
                mobPos.offset(-radius, -radius, -radius), mobPos.offset(radius, radius, radius));

            // CRITICAL: the PathFinder + NodeEvaluator hold per-search scratch state (open-set, node
            // pool, PathfindingContext) and are NOT safe to reuse across threads. Build a fresh,
            // isolated finder so the worker shares zero mutable state with the main thread. Config flags
            // are copied from the mob's evaluator so results match vanilla exactly.
            NodeEvaluator freshEval = dev.pathweaver.async.EvaluatorCloner.cloneWithConfig(this.nodeEvaluator);
            int maxNodes = ((PathFinderAccessor) (Object) this.pathFinder).pathweaver$getMaxVisitedNodes();
            final PathFinder finder = new PathFinder(freshEval, maxNodes);

            // Capture everything the worker needs; it must not read live mutable world state beyond this.
            // NOTE (block-read caveat): the vanilla findPath below still reads the LIVE mob's position,
            // malus map and hitbox during the search. Those are reads (not writes) and are bounded by
            // the staleness discard at install; the PathTypeCache (the one WRITE hazard) is isolated by
            // PathfindingContextMixin. A full mob-state snapshot is deliberately not attempted here.
            final Set<BlockPos> targetsCopy = new HashSet<>(targets);
            final float mult = this.maxVisitedNodesMultiplier;
            final float fRange = followRange;
            final int rRange = reachRange;
            final double dx = theMob.getX(), dy = theMob.getY(), dz = theMob.getZ();

            Callable<Path> search = () -> finder.findPath(region, theMob, targetsCopy, fRange, rRange, mult);

            sink.register(entityId, this);
            registered = true;
            boolean accepted = rt.pool().submit(new PathRequest(entityId, tick, search,
                result -> rt.installer().enqueue(entityId, tick, result, dx, dy, dz)));

            if (!accepted) {
                sink.discard(entityId);   // pool saturated -> let vanilla run synchronously
                return;
            }

            // FIX 3b/3c: capture the intended reachRange for install, and set targetPos optimistically to
            // the dispatched target so recomputePath() and Feature B work during the 1-tick in-flight
            // window (vanilla would have null targetPos until install, killing both).
            this.pathweaver$pendingReachRange = reachRange;
            this.targetPos = targetsCopy.iterator().next();

            // FIX 2b: replay the live-mob onPathfindingStart on the MAIN thread (the evaluator skips it
            // off-thread). Only for evaluators that vanilla calls it for: Walk + Fly (not Swim). Balanced
            // by onPathfindingDone at install/discard.
            if (pathweaver$callsCallbacks(this.nodeEvaluator.getClass())) {
                theMob.onPathfindingStart();
                this.pathweaver$callbackStarted = true;
            }

            rt.markDispatched();
            // Keep moving on the current path this tick; the async result installs next tick.
            cir.setReturnValue(this.path);
        } catch (Throwable t) {
            if (registered) sink.discard(entityId); // unwind; onPathfindingDone is a no-op if not started.
            // No cir.cancel(): fall through so vanilla computes the path synchronously this tick.
        }
    }

    @Unique
    private static boolean pathweaver$callsCallbacks(Class<?> evaluatorClass) {
        // Verified 26.1.2: WalkNodeEvaluator + FlyNodeEvaluator call onPathfindingStart/Done in
        // prepare/done; SwimNodeEvaluator does not. (Amphibious is not async-eligible.)
        return evaluatorClass == WalkNodeEvaluator.class || evaluatorClass == FlyNodeEvaluator.class;
    }

    // ---- PWNavigation duck ----

    @Override
    public void pathweaver$install(Path path) {
        // Vanilla's own install path: handles sameAs/trim/stuck bookkeeping. Use the caller's real
        // intended speed (captured via moveTo), never a stale 0.
        double speed = pathweaver$lastRequestedSpeed > 0.0 ? pathweaver$lastRequestedSpeed : this.speedModifier;
        moveTo(path, speed);

        // FIX 3b: replay the tail of vanilla createPath so async mobs behave like sync ones -
        // recomputePath() repaths around new obstacles, stuck-timeout resets, and Feature B stays live.
        BlockPos target = path.getTarget();
        if (target != null) {
            this.targetPos = target;
            this.reachRange = this.pathweaver$pendingReachRange;
            resetStuckTimeout();
        }
        pathweaver$onPathfindingDone();
    }

    @Override
    public boolean pathweaver$stale(double dispatchX, double dispatchY, double dispatchZ) {
        if (this.mob == null || !this.mob.isAlive()) return true;
        double moveThreshold = PathWeaverConfig.get().stalenessMoveThreshold;
        return this.mob.distanceToSqr(dispatchX, dispatchY, dispatchZ) > moveThreshold * moveThreshold;
    }

    @Override
    public void pathweaver$onPathfindingDone() {
        if (this.pathweaver$callbackStarted) {
            this.pathweaver$callbackStarted = false;
            this.mob.onPathfindingDone();
        }
    }
}
