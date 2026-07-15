package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.NavigationIdentity;
import dev.pathweaver.async.PathRequest;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.RequestTarget;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import dev.pathweaver.gate.SafetyGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
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
 * evaluator is allowlisted. The region uses vanilla's radius formula but is a live-backed view, not
 * an immutable copy, and the live mob remains an input. Completion is installed later through
 * {@code moveTo(path, speedModifier)} plus selected {@code createPath} bookkeeping. Dispatch-time
 * guard hits and pool rejection do not cancel, so that invocation continues synchronously.
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
    /**
     * Non-zero only while one of PathNavigation's genuine movement/recompute entry points is making
     * its virtual createPath call. Direct/query-only createPath calls remain synchronous by construction.
     */
    @Unique private int pathweaver$navigationRequestDepth;
    @Unique private long pathweaver$targetRevision;

    @WrapOperation(
        method = "recomputePath()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$armRecomputePath(PathNavigation instance, BlockPos target, int reachRange,
                                             Operation<Path> original) {
        pathweaver$navigationRequestDepth++;
        try {
            return original.call(instance, target, reachRange);
        } finally {
            pathweaver$navigationRequestDepth--;
        }
    }

    @WrapOperation(
        method = "moveTo(DDDD)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(DDDI)Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$armCoordinateMove(PathNavigation instance, double x, double y, double z,
                                              int reachRange, Operation<Path> original) {
        pathweaver$navigationRequestDepth++;
        try {
            return original.call(instance, x, y, z, reachRange);
        } finally {
            pathweaver$navigationRequestDepth--;
        }
    }

    @WrapOperation(
        method = "moveTo(DDDID)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(DDDI)Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$armCoordinateMoveWithReach(PathNavigation instance,
                                                       double x, double y, double z, int reachRange,
                                                       Operation<Path> original) {
        pathweaver$navigationRequestDepth++;
        try {
            return original.call(instance, x, y, z, reachRange);
        } finally {
            pathweaver$navigationRequestDepth--;
        }
    }

    @WrapOperation(
        method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$armEntityMove(PathNavigation instance, Entity target, int reachRange,
                                          Operation<Path> original) {
        pathweaver$navigationRequestDepth++;
        try {
            return original.call(instance, target, reachRange);
        } finally {
            pathweaver$navigationRequestDepth--;
        }
    }

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
        // Only the four wrapped genuine-navigation call sites may opt into elision or async dispatch.
        // All direct/external createPath calls — including unknown mod queries — stay vanilla sync.
        if (pathweaver$navigationRequestDepth == 0) return;

        PathWeaverConfig cfg = PathWeaverConfig.get();
        PathWeaverRuntime rt = PathWeaverRuntime.get();
        final int entityId = this.mob.getId();
        EntityInstallSink sink = rt.entitySink();
        RequestTarget requestTarget = RequestTarget.of(
            targets, regionOffset, offsetUpward, reachRange, followRange);
        boolean intentAdvanced = false;

        // A repeated request for the same semantic target shares the accepted pending operation. A
        // materially different request cancels the old registration before sync/async routing continues.
        EntityInstallSink.PendingDecision pendingDecision = this.level instanceof ServerLevel
            ? sink.pendingDecision(entityId, this, requestTarget)
            : EntityInstallSink.PendingDecision.NONE;
        if (pendingDecision == EntityInstallSink.PendingDecision.PRESERVE) {
            cir.setReturnValue(this.path);
            return;
        } else if (pendingDecision == EntityInstallSink.PendingDecision.SUPERSEDE) {
            intentAdvanced = sink.supersede(entityId);
            if (intentAdvanced) pathweaver$targetRevision++;
        }

        // Feature B: opt-in repath elision. A zero tolerance disables this injection entirely; the
        // v0.2 changed-block/endpoint validity work is required before it can become a default.
        if (cfg.repathElisionEnabled && cfg.repathToleranceBlocks > 0
                && this.path != null && !this.path.isDone()
                && dev.pathweaver.elision.RepathTolerance.anyWithinTolerance(
                        targets, this.targetPos, cfg.repathToleranceBlocks)) {
            cir.setReturnValue(this.path);
            return;
        }

        // Feature A: async dispatch.
        if (!cfg.asyncEnabled || cfg.syncFallbackOnly) return;
        if (!rt.isRunning()) return;
        if (!(this.level instanceof ServerLevel)) return;                       // server-side only
        if (this.nodeEvaluator == null || !SafetyGate.isAllowed(this.nodeEvaluator.getClass())) return;

        // Replicate vanilla's cheap guards; on any of these, let vanilla run synchronously (no cancel).
        if (targets.isEmpty()) return;
        if (this.mob.getY() < this.level.getMinY()) return;
        if (!canUpdatePath()) return;
        if (this.path != null && !this.path.isDone() && targets.contains(this.targetPos)) return;

        final Mob theMob = this.mob;
        final long tick = ((ServerLevel) this.level).getServer().getTickCount();

        // FIX 4: this entity's last async search failed and it's in cooldown -> run vanilla sync this tick.
        if (sink.shouldForceSync(entityId, tick)) return;

        // A same-target pending operation returned above; anything still registered is conservatively sync.
        if (sink.isRegistered(entityId)) {
            return;
        }

        // Everything below can fail on unusual mods/data; degrade to sync rather than escape into the
        // entity tick (FIX 6a). If we've already registered in the sink, unwind that registration.
        boolean registered = false;
        RequestKey requestKey = null;
        try {
            // FIX 2c: force-resolve the mob's step-height attribute on the MAIN thread so the worker's
            // read of maxUpStep() hits a clean cached value instead of lazily writing it off-thread.
            theMob.maxUpStep();

            // Use vanilla's bounds formula. The region is still backed by live chunks, so matching
            // construction does not guarantee a temporally identical result.
            BlockPos mobPos = offsetUpward ? theMob.blockPosition().above() : theMob.blockPosition();
            int radius = (int) (followRange + (float) regionOffset);
            PathNavigationRegion region = new PathNavigationRegion(this.level,
                mobPos.offset(-radius, -radius, -radius), mobPos.offset(radius, radius, radius));

            // The PathFinder + NodeEvaluator hold per-search scratch state (open-set, node pool,
            // PathfindingContext) and are not reusable across threads. A fresh pair isolates that
            // scratch state; it does not isolate the live region/mob inputs. Copy the supported flags.
            NodeEvaluator freshEval = dev.pathweaver.async.EvaluatorCloner.cloneWithConfig(this.nodeEvaluator);
            int maxNodes = ((PathFinderAccessor) (Object) this.pathFinder).pathweaver$getMaxVisitedNodes();
            final PathFinder finder = new PathFinder(freshEval, maxNodes);

            // Copy request scalars/targets. The search still reads live chunks plus the live mob's
            // position, malus map, hitbox and level. Install-distance staleness only rejects some old
            // results; it cannot make those reads immutable. PathfindingContextMixin isolates the
            // known shared PathTypeCache write.
            final Set<BlockPos> targetsCopy = new HashSet<>(targets);
            final float mult = this.maxVisitedNodesMultiplier;
            final float fRange = followRange;
            final int rRange = reachRange;
            final double dx = theMob.getX(), dy = theMob.getY(), dz = theMob.getZ();

            Callable<Path> search = () -> finder.findPath(region, theMob, targetsCopy, fRange, rRange, mult);

            requestKey = rt.nextRequestKey(entityId);
            final RequestKey submittedKey = requestKey;
            if (!intentAdvanced) pathweaver$targetRevision++;
            sink.register(requestKey, this, requestTarget);
            registered = true;
            boolean accepted = rt.pool().submit(new PathRequest(submittedKey, tick, search,
                result -> rt.installer().enqueue(submittedKey, tick, result, dx, dy, dz)));

            if (!accepted) {
                sink.discard(requestKey); // pool saturated -> let vanilla run synchronously
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
            if (registered) sink.discard(requestKey);
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

        // Replay selected createPath bookkeeping needed by genuine navigation/recompute requests.
        // Query-only createPath calls cannot reach this async install path because routing depth stays zero.
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
        if (this.mob == null || !this.mob.isAlive() || this.mob.isRemoved()
                || this.mob.getNavigation() != (Object) this || this.mob.level() != this.level) return true;
        double moveThreshold = PathWeaverConfig.get().stalenessMoveThreshold;
        return this.mob.distanceToSqr(dispatchX, dispatchY, dispatchZ) > moveThreshold * moveThreshold;
    }

    @Override
    public NavigationIdentity pathweaver$identity() {
        return new NavigationIdentity(this.mob.getUUID(), this.mob.level(), this.mob.level().dimension(),
            this, this.path, this.pathweaver$targetRevision);
    }

    @Inject(method = "stop()V", at = @At("HEAD"), require = 1, expect = 1)
    private void pathweaver$invalidateStoppedRequest(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        pathweaver$targetRevision++;
        PathWeaverRuntime.get().entitySink().cancel(this.mob.getId(), this);
    }

    @Override
    public void pathweaver$onPathfindingDone() {
        if (this.pathweaver$callbackStarted) {
            this.pathweaver$callbackStarted = false;
            this.mob.onPathfindingDone();
        }
    }
}
