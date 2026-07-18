package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.EvaluatorCallbackContract;
import dev.pathweaver.async.NavigationIdentity;
import dev.pathweaver.async.PathRequest;
import dev.pathweaver.async.RequestKey;
import dev.pathweaver.async.RequestTarget;
import dev.pathweaver.async.SearchStartGate;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import dev.pathweaver.gate.SafetyGate;
import dev.pathweaver.gate.MobOriginGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
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
 * {@code moveTo(path, capturedSpeed)} plus selected {@code createPath} bookkeeping. Dispatch-time
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
    @Unique private int pathweaver$pendingDoneCallbacks;
    // Movement callers supply speed outside createPath. Capture it at those approved callers and
    // bind the exact double (including 0, negative values, and NaN) to the accepted registration.
    @Unique private double pathweaver$requestSpeed = 1.0;
    @Unique private double pathweaver$pendingInstallSpeed = 1.0;
    @Unique private double pathweaver$recomputeRequestSpeed = 1.0;
    @Unique private boolean pathweaver$acceptedDeferred;
    /**
     * Non-zero only while one of PathNavigation's genuine movement/recompute entry points is making
     * its virtual createPath call. Direct/query-only createPath calls remain synchronous by construction.
     */
    @Unique private int pathweaver$navigationRequestDepth;
    @Unique private long pathweaver$targetRevision;
    @Unique private boolean pathweaver$recomputeInvalidated;

    @Inject(method = "moveTo(DDDD)Z", at = @At("HEAD"), require = 1, expect = 1)
    private void pathweaver$captureCoordinateSpeed(double x, double y, double z, double speed,
                                                    CallbackInfoReturnable<Boolean> cir) {
        pathweaver$beginMovement(speed);
    }

    @Inject(method = "moveTo(DDDID)Z", at = @At("HEAD"), require = 1, expect = 1)
    private void pathweaver$captureCoordinateReachSpeed(double x, double y, double z, int reach,
                                                         double speed,
                                                         CallbackInfoReturnable<Boolean> cir) {
        pathweaver$beginMovement(speed);
    }

    @Inject(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"),
            require = 1, expect = 1)
    private void pathweaver$captureEntitySpeed(Entity entity, double speed,
                                                CallbackInfoReturnable<Boolean> cir) {
        pathweaver$beginMovement(speed);
    }

    @Inject(method = {
            "moveTo(DDDD)Z",
            "moveTo(DDDID)Z",
            "moveTo(Lnet/minecraft/world/entity/Entity;D)Z"
        }, at = @At("RETURN"), cancellable = true, require = 3, expect = 3)
    private void pathweaver$deferredMovementResult(CallbackInfoReturnable<Boolean> cir) {
        if (pathweaver$acceptedDeferred) cir.setReturnValue(true);
        pathweaver$acceptedDeferred = false;
    }

    @Unique
    private void pathweaver$beginMovement(double speed) {
        pathweaver$requestSpeed = speed;
        pathweaver$acceptedDeferred = false;
    }

    @Inject(
        method = "recomputePath()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;canUpdatePath()Z"),
        require = 1,
        expect = 1
    )
    private void pathweaver$supersedeBeforeRecomputeGuard(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.level instanceof ServerLevel) {
            EntityInstallSink sink = PathWeaverRuntime.get().entitySink();
            int entityId = this.mob.getId();
            this.pathweaver$recomputeRequestSpeed = sink.isRegistered(entityId, this)
                ? this.pathweaver$pendingInstallSpeed : this.speedModifier;
            if (sink.supersede(entityId)) this.pathweaver$targetRevision++;
        }
    }

    @WrapOperation(
        method = "recomputePath()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$armRecomputePath(PathNavigation instance, BlockPos target, int reachRange,
                                              Operation<Path> original) {
        this.pathweaver$navigationRequestDepth++;
        this.pathweaver$recomputeInvalidated = true;
        this.pathweaver$requestSpeed = this.pathweaver$recomputeRequestSpeed;
        try {
            return original.call(instance, target, reachRange);
        } finally {
            this.pathweaver$recomputeInvalidated = false;
            this.pathweaver$navigationRequestDepth--;
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


    @Inject(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1
    )
    private void pathweaver$asyncCreatePath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward,
                                            int reachRange, float followRange,
                                            CallbackInfoReturnable<Path> cir) {
        // Only the four wrapped genuine-navigation call sites may opt into elision or async dispatch.
        // All direct/external createPath calls — including unknown mod queries — stay vanilla sync.
        if (pathweaver$navigationRequestDepth == 0) return;

        // Preserve vanilla's cheap preconditions before either tolerance elision or async routing.
        if (targets.isEmpty()) return;
        if (this.mob.getY() < this.level.getMinY()) return;
        if (!canUpdatePath()) return;

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
            ? sink.pendingDecision(entityId, this, requestTarget, this.pathweaver$recomputeInvalidated)
            : EntityInstallSink.PendingDecision.NONE;
        if (pendingDecision == EntityInstallSink.PendingDecision.PRESERVE) {
            this.pathweaver$pendingInstallSpeed = this.pathweaver$requestSpeed;
            this.pathweaver$acceptedDeferred = true;
            cir.setReturnValue(this.path);
            return;
        } else if (pendingDecision == EntityInstallSink.PendingDecision.SUPERSEDE) {
            intentAdvanced = sink.supersede(entityId);
            if (intentAdvanced) pathweaver$targetRevision++;
        }

        // This vanilla fast-path must precede tolerance reuse. Pending supersession remains above it
        // so a different accepted intent cannot later overwrite the path returned here.
        if (this.path != null && !this.path.isDone() && targets.contains(this.targetPos)) return;

        // Feature B remains opt-in. Recompute (including changed-block invalidation) always bypasses
        // tolerance reuse; ordinary target drift must satisfy endpoint, reach and navigation validity.
        if (cfg.repathElisionEnabled && cfg.repathToleranceBlocks > 0 && this.path != null) {
            Path currentPath = this.path;
            net.minecraft.world.level.pathfinder.Node endpoint = currentPath.getEndNode();
            var current = new dev.pathweaver.elision.RepathTolerance.CurrentPath(
                currentPath.getTarget(),
                endpoint == null ? null : new BlockPos(endpoint.x, endpoint.y, endpoint.z),
                currentPath.canReach(), currentPath.isDone(), true,
                this.pathweaver$recomputeInvalidated, this.reachRange);
            BlockPos reusableTarget = dev.pathweaver.elision.RepathTolerance.reusableTarget(
                targets, current, reachRange, cfg.repathToleranceBlocks);
            if (reusableTarget != null) {
                if (!reusableTarget.equals(this.targetPos)) {
                    this.targetPos = reusableTarget;
                    this.pathweaver$targetRevision++;
                }
                cir.setReturnValue(currentPath);
                return;
            }
        }

        // Feature A: async dispatch.
        if (!cfg.asyncEnabled || cfg.syncFallbackOnly) return;
        if (!rt.isRunning()) return;
        if (!(this.level instanceof ServerLevel)) return;                       // server-side only
        if (this.nodeEvaluator == null || !SafetyGate.isAllowed(this.nodeEvaluator.getClass())) return;

        final Mob theMob = this.mob;
        if (!MobOriginGate.isAllowed(theMob.getClass(), cfg.allowModdedMobAsync)) return;
        final long tick = ((ServerLevel) this.level).getServer().getTickCount();

        // This entity's last async search failed and it's in cooldown -> run vanilla sync this tick.
        if (sink.shouldForceSync(entityId, tick)) return;

        // A same-target pending operation returned above; anything still registered is conservatively sync.
        if (sink.isRegistered(entityId)) {
            return;
        }

        // Everything below can fail on unusual mods/data; degrade to sync rather than escape into the
        // entity tick. If we've already registered in the sink, unwind that registration.
        boolean registered = false;
        RequestKey requestKey = null;
        SearchStartGate startGate = null;
        boolean authorizeSearch = false;
        try {
            // Force-resolve the mob's step-height attribute on the MAIN thread so the worker's
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

            final SearchStartGate requestStartGate = new SearchStartGate();
            startGate = requestStartGate;
            Callable<Path> search = () -> requestStartGate.awaitStart()
                ? finder.findPath(region, theMob, targetsCopy, fRange, rRange, mult)
                : null;

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
            rt.markDispatched();

            // Capture the intended reachRange for install, and set targetPos optimistically to
            // the dispatched target so recomputePath() and Feature B work during the 1-tick in-flight
            // window (vanilla would have null targetPos until install, killing both).
            this.pathweaver$pendingReachRange = reachRange;
            this.pathweaver$pendingInstallSpeed = this.pathweaver$requestSpeed;
            this.targetPos = targetsCopy.iterator().next();

            // Replay the exact vanilla per-evaluator callback contract on the MAIN thread. Walk has one
            // start/done pair; Swim has none. Set the balance bit before invoking untrusted mod code so
            // even a throwing start is terminally balanced by the outer discard path.
            EvaluatorCallbackContract callbackContract =
                EvaluatorCallbackContract.forAsyncEvaluator(this.nodeEvaluator.getClass());
            this.pathweaver$pendingDoneCallbacks = callbackContract.doneCount();
            for (int i = 0; i < callbackContract.startCount(); i++) {
                theMob.onPathfindingStart();
            }

            // Keep moving on the current path this tick; the async result installs next tick.
            this.pathweaver$acceptedDeferred = true;
            cir.setReturnValue(this.path);
            authorizeSearch = true;
        } catch (Throwable t) {
            if (registered) sink.discard(requestKey);
            // No cir.cancel(): fall through so vanilla computes the path synchronously this tick.
        } finally {
            // Every main-thread exit releases an accepted worker. Only a fully completed setup opens
            // the search; rejection or any setup/start-callback failure releases it as cancelled.
            if (startGate != null) {
                if (authorizeSearch) startGate.open();
                else startGate.cancel();
            }
        }
    }


    // ---- PWNavigation duck ----

    @Override
    public void pathweaver$install(Path path) {
        // Vanilla's own install path: handles sameAs/trim/stuck bookkeeping. Use the caller's real
        // intended speed bound to this registration, including vanilla-valid non-positive/NaN values.
        moveTo(path, this.pathweaver$pendingInstallSpeed);

        // Replay selected createPath bookkeeping needed by genuine navigation/recompute requests.
        // Query-only createPath calls cannot reach this async install path because routing depth stays zero.
        BlockPos target = path.getTarget();
        if (target != null) {
            this.targetPos = target;
            this.reachRange = this.pathweaver$pendingReachRange;
            resetStuckTimeout();
        }
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
        int callbacks = this.pathweaver$pendingDoneCallbacks;
        this.pathweaver$pendingDoneCallbacks = 0;
        for (int i = 0; i < callbacks; i++) {
            this.mob.onPathfindingDone();
        }
    }
}
