package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.async.NavigationIdentity;
import dev.pathweaver.async.PathRequest;
import dev.pathweaver.async.PathWeaverThread;
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
    @Shadow public abstract void stop();
    @Shadow private int reachRange;
    @Shadow private float maxVisitedNodesMultiplier;
    @Shadow protected double speedModifier;

    @Shadow protected abstract boolean canUpdatePath();
    @Shadow public abstract boolean moveTo(Path path, double speed);
    // Target method is private; a private stub (not `private abstract`, which is illegal Java) shadows it.
    @Shadow private void resetStuckTimeout() { throw new AssertionError(); }

    // ---- per-in-flight capture (main thread only; the isRegistered guard ensures one search at a time) ----
    @Unique private int pathweaver$pendingReachRange;
    /** The optimistic targetPos this navigation wrote at dispatch, or null when none is pending. */
    @Unique private BlockPos pathweaver$optimisticTargetPos;
    /** The targetPos to restore if the pending request never installs a path. */
    @Unique private BlockPos pathweaver$targetPosBeforeDispatch;
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
    /**
     * The navigation's target as it stood when {@code recomputePath} was entered.
     *
     * <p>Only the target. An earlier version of this paragraph claimed the path was captured here
     * too; it never was, and the sentence outlived two rewrites of the code it described.
     *
     * <p>Captured at the {@code canUpdatePath()} injection point, which is upstream of everything
     * vanilla does next. Superseding an in-flight request there rolls the optimistic
     * {@code targetPos} back to the pre-dispatch value, so without this the recompute would run
     * against the destination the caller had already abandoned — silently discarding a move its goal
     * was told succeeded.
     *
     * <p>It is consumed in two places, and vanilla has FOUR exits, not two. Getting that count wrong
     * is how 0.5.2 shipped a fix that covered one branch and how 0.5.1 shipped one that corrupted
     * another, so the whole table is written out rather than summarised:
     *
     * <pre>{@code
     *  16: ifle 73            cooldown not elapsed -- the injection never fires, nothing captured
     *  20: canUpdatePath()    <- @Inject here
     *  23: ifeq 73            false: hasDelayedRecomputation = true; path and targetPos untouched
     *  26: getfield targetPos
     *  30: ifnull 78          return; createPath is never called
     *  33: path = null
     *  48: createPath(...)    <- @WrapOperation here
     * }</pre>
     *
     * <table>
     *   <caption>What happens to a claim C</caption>
     *   <tr><th>path at guard</th><th>canUpdatePath</th><th>outcome</th></tr>
     *   <tr><td>null</td><td>false</td><td>guard applies C; delayed retry will search for it</td></tr>
     *   <tr><td>null</td><td>true</td><td>guard applies C; wrap re-applies it; vanilla searches C</td></tr>
     *   <tr><td>non-null</td><td>true</td><td>wrap applies C after vanilla nulls path at 33</td></tr>
     *   <tr><td>non-null</td><td>false</td><td><b>C is dropped</b> — see below</td></tr>
     * </table>
     *
     * <p>Every one of those leaves {@code targetPos} and {@code path} naming the same destination, so
     * the 0.5.1 pairing cannot recur. But the last row is not complete, and saying otherwise here is
     * what a reviewer caught: with a route already installed and vanilla declining to recompute, the
     * claim is dropped and the mob keeps walking to the destination it abandoned until its goal
     * re-issues. Re-applying C here is exactly the 0.5.1 bug, so the fix is not a condition change —
     * it needs the request to carry its origin so the delayed recompute can pick the claim up, which
     * is the same change DESIGN.md §13 needs and is deliberately not being rushed into a patch.
     *
     * <p>This is not a regression: 0.5.0 dropped the claim on all three of those branches.
     */
    @Unique private BlockPos pathweaver$recomputeTargetClaim;

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

    /**
     * Cancel the in-flight request as soon as {@code recomputePath} is entered, whether or not
     * vanilla goes on to recompute anything.
     *
     * <p>This injection point looks wrong and is not, so the reasoning is recorded here — it has now
     * been raised twice. Against the 26.1.2 bytecode, this sits before three separate ways for
     * vanilla to then do nothing:
     *
     * <pre>{@code
     * if (gameTime - timeLastRecompute > 20 && canUpdatePath()) {   // <- injected at this INVOKE
     *     if (targetPos == null) return;                            // <- plain return
     *     path = createPath(targetPos, reachRange);                 // <- the only actual recompute
     * } else hasDelayedRecomputation = true;
     * }</pre>
     *
     * <p>The objection is that superseding here throws away a search on ticks where nothing replaces
     * it. That is true and it is deliberate. <strong>What invalidates the pending work is the world
     * change that caused {@code recomputePath} to be called, not whether vanilla can act on it this
     * tick.</strong> The in-flight search was computed against the pre-change world; installing it
     * later would route the mob through geometry that has since changed. Deferring the cancel until
     * vanilla reaches {@code createPath} would keep known-stale work alive across every tick where
     * {@code canUpdatePath()} is false — precisely the mid-jump and delayed-recompute cases — and
     * then install it.
     *
     * <p>So the cost is a wasted search on those ticks, and the alternative is a wrong path. Vanilla
     * sets {@code hasDelayedRecomputation} on the rejecting branch and retries, so the mob is not
     * stranded. {@code PathNavigationRoutingGameTest} pins this for the airborne case by name.
     */
    @Inject(
        method = "recomputePath()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;canUpdatePath()Z"),
        require = 1,
        expect = 1
    )
    private void pathweaver$supersedeBeforeRecomputeGuard(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Captured before the supersede below, because that supersede rolls the optimistic target
        // back, and vanilla reads targetPos two bytecodes after this injection point.
        this.pathweaver$recomputeTargetClaim = this.targetPos;
        if (this.level instanceof ServerLevel) {
            EntityInstallSink sink = PathWeaverRuntime.get().entitySink();
            int entityId = this.mob.getId();
            this.pathweaver$recomputeRequestSpeed = sink.isRegistered(entityId, this)
                ? this.pathweaver$pendingInstallSpeed : this.speedModifier;
            if (sink.supersede(entityId)) this.pathweaver$targetRevision++;
        }
        // Vanilla has THREE exits from here, not two, and 0.5.2 only got two of them right.
        //
        //    20: canUpdatePath()      <- this injection point
        //    23: ifeq 73              <- false: hasDelayedRecomputation = true, path untouched
        //    26: getfield targetPos
        //    30: ifnull 78            <- RETURN. createPath is never called.
        //    33: path = null
        //    48: createPath(...)      <- the @WrapOperation below
        //
        // The supersede above rolls the optimistic target back, so when the pre-dispatch target was
        // null -- an idle mob that was handed its first destination asynchronously -- targetPos is
        // null again by offset 26 and vanilla returns at 78. The wrap never runs, the claim is
        // dropped, and the mob is left with neither a path nor a target while its goal was already
        // told the move succeeded. It re-issues after its own cooldown, so this is a stall of a few
        // ticks rather than the indefinite stale path 0.5.1 produced, but it is a stall PathWeaver
        // caused and vanilla would not have.
        //
        // Re-applying here is what 0.5.1 did and it is what made `targetPos` and `path` describe
        // different destinations. The hazard needs BOTH halves of that pairing: vanilla's reuse
        // short-circuit is `path != null && !path.isDone() && targets.contains(targetPos)`. With no
        // path installed there is no route for a re-applied target to contradict, so the guard below
        // is not a heuristic -- it is the precondition of the bug, negated. `path` is still whatever
        // was installed at this point; vanilla does not clear it until offset 33.
        if (this.pathweaver$recomputeTargetClaim != null && this.path == null) {
            this.targetPos = this.pathweaver$recomputeTargetClaim;
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
        // Re-apply the claimed destination HERE, not in the guard above.
        //
        // 0.5.1 applied it in the guard, which injects at the canUpdatePath() INVOKE -- upstream of
        // the branch. On the false branch (a ground mob mid-jump) vanilla jumps straight to setting
        // hasDelayedRecomputation: it never reads targetPos and, critically, never nulls `path`. So
        // the guard's re-apply left targetPos naming the claimed destination while `path` still held
        // the route to the old one. That is exactly the pairing rollbackOptimisticTarget exists to
        // prevent: the next createPath for the claimed target hits the vanilla reuse short-circuit
        // (path != null && !path.isDone() && targets.contains(targetPos)) and is handed the stale
        // path, self-sustainingly, until that path completes or the navigation is stopped.
        //
        // This wrap runs only where vanilla actually recomputes, and `path` is null by the time it is
        // consulted (offset 33), so the pairing hazard cannot exist here. It does NOT cover the
        // targetPos == null early return at offset 30, which never reaches createPath at all -- the
        // guard above handles that one, under the condition that makes it safe.
        if (this.pathweaver$recomputeTargetClaim != null) {
            this.targetPos = this.pathweaver$recomputeTargetClaim;
            target = this.pathweaver$recomputeTargetClaim;
        }
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

        // Master OFF stops every new PathWeaver intervention. Same-target work accepted before OFF
        // returned above and drains through its existing registration; changed intent was balanced above.
        if (!cfg.enabled) return;

        // Feature B remains opt-in. Recompute (including changed-block invalidation) always bypasses
        // tolerance reuse; ordinary target drift must satisfy endpoint, reach and navigation validity.
        if (cfg.repathToleranceBlocks > 0 && this.path != null) {
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
        if (!rt.isRunning()) return;
        if (!(this.level instanceof ServerLevel)) return;                       // server-side only
        if (this.nodeEvaluator == null || !SafetyGate.isAllowed(this.nodeEvaluator.getClass())) return;
        // The gate checks the evaluator; it must also check the PathFinder, because dispatch builds
        // its own `new PathFinder(...)` and so ignores whatever createPathFinder(int) returned. A mod
        // shipping a PathFinder subclass that overrides findPath/getBestH paired with a stock
        // WalkNodeEvaluator passes the exact-class evaluator allowlist, and its mobs would then run
        // the mod's A* on every synchronous fallback and vanilla's A* on every async dispatch --
        // routing that flips with worker-pool load, which nobody can report reproducibly.
        if (this.pathFinder == null || this.pathFinder.getClass() != PathFinder.class) return;
        // ALL means all: the operator has asked for no compatibility checking whatsoever, so the
        // land-provider gate is waived too. It is a correctness gate rather than a thread-safety
        // one -- with it waived a mob can be routed over a block a mod marked dangerous -- but
        // leaving it armed made "ignore every check" silently still refuse to run Walk, which is
        // not what the setting says and not what an operator choosing it expects.
        //
        // Clearing the flag here waives the dispatch gate and the install-time re-check together,
        // because both are driven from it.
        // Shared with the startup banner and /pathweaver status through SafetyGate, so a reporting
        // site cannot answer a more optimistic question than the one asked here.
        final boolean requiresEmptyLandRegistry =
            SafetyGate.requiresEmptyLandRegistry(this.nodeEvaluator.getClass());
        if (requiresEmptyLandRegistry
                && !dev.pathweaver.gate.FabricLandPathRegistryLatch.allowsWalkDispatch()) return;

        final Mob theMob = this.mob;
        if (!MobOriginGate.isAllowed(theMob.getClass(), cfg.moddedMobAsyncAllowed())) return;
        final long tick = ((ServerLevel) this.level).getServer().getTickCount();

        // This entity's last async search failed and it's in cooldown -> run vanilla sync this tick.
        if (sink.shouldForceSync(entityId, tick)) return;

        // A same-target pending operation returned above; anything still registered is conservatively sync.
        if (sink.isRegistered(entityId)) {
            return;
        }

        // Registration is not the only thing that has to be clear. supersede()/cancel() drop the
        // registration but deliberately keep the epilogue owed, because the worker may still be
        // inside the search -- so without this, the same navigation could start a second search while
        // the first one's done() was still outstanding. Two prepare() calls against one live mob, and
        // AmphibiousNodeEvaluator's prepare/done are a save/restore pair on that mob: the second
        // prepare captures the first's search-time costs as "the old values" and, since epilogues run
        // in completion order, the mob keeps 6.0/4.0 forever. Sync nests correctly, so fall back.
        if (sink.owesEpilogue(entityId)) {
            return;
        }

        // Everything below can fail on unusual mods/data; degrade to sync rather than escape into the
        // entity tick. If we've already registered in the sink, unwind that registration.
        boolean registered = false;
        RequestKey requestKey = null;
        SearchStartGate startGate = null;
        boolean authorizeSearch = false;
        try {
            // Resolve the mob's step height on the MAIN thread and hand the VALUE to the worker.
            //
            // This used to pre-resolve the attribute here and let the worker call maxUpStep() itself,
            // on the theory that a warm cache made the worker's call a pure read. It does not.
            // maxUpStep() is AttributeInstance.getValue(), which is
            //   if (dirty) { cachedValue = calculateValue(); dirty = false; }
            // on plain non-volatile fields, and WalkNodeEvaluator reaches it from inside the A* loop.
            // Pre-resolving only clears `dirty` at this instant; the request is in flight for at
            // least a tick, and equipment, a potion effect or a mod re-dirties it inside that window.
            // WalkNodeEvaluatorMixin redirects the call to this captured value instead.
            final float capturedStepHeight = theMob.maxUpStep();
            // Same treatment, same reason: getMaxFallDistance() reads getMaxHealth() for a mob with a
            // target, which is AttributeInstance.getValue() -- and WalkNodeEvaluator reaches it from
            // inside the A* loop via findAcceptedNode -> tryFindFirstGroundNodeBelow.
            final int capturedMaxFall = theMob.getMaxFallDistance();

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
            // Publish the captured step height for the duration of this search only. Pooled worker
            // threads outlive a search, so a value left behind would be silently reused by whatever
            // mob ran next on that thread -- which is the same class of bug as the race it fixes.
            Callable<Path> search = () -> {
                if (!requestStartGate.awaitStart()) return null;
                // Both sets INSIDE the try. If the second throws -- only an OutOfMemoryError can,
                // since it boxes an int and may allocate a ThreadLocalMap entry -- the first would
                // otherwise stay published on a pooled thread that outlives this search, and the
                // next mob scheduled onto it would inherit another mob's step height. That is
                // precisely the failure both these javadocs spend paragraphs warning about, so it
                // should not have a window at all.
                try {
                    PathWeaverThread.setWorkerStepHeight(capturedStepHeight);
                    PathWeaverThread.setWorkerMaxFallDistance(capturedMaxFall);
                    return finder.findPath(region, theMob, targetsCopy, fRange, rRange, mult);
                } finally {
                    PathWeaverThread.clearWorkerStepHeight();
                    PathWeaverThread.clearWorkerMaxFallDistance();
                }
            };

            requestKey = rt.nextRequestKey(entityId);
            final RequestKey submittedKey = requestKey;
            if (!intentAdvanced) pathweaver$targetRevision++;
            sink.register(requestKey, this, requestTarget, requiresEmptyLandRegistry);
            registered = true;
            boolean accepted = rt.pool().submit(new PathRequest(submittedKey, tick, search,
                result -> rt.installer().enqueue(submittedKey, tick, result, dx, dy, dz),
                rt.installer()::enqueueDiscard));

            if (!accepted) {
                // Nothing reached a worker, so this is an admission statistic rather than waste.
                sink.discard(requestKey, dev.pathweaver.async.RequestOutcome.POOL_SATURATED);
                return;
            }
            rt.markDispatched();

            // Capture the intended reachRange for install, and set targetPos optimistically to
            // the dispatched target so recomputePath() and Feature B work during the 1-tick in-flight
            // window (vanilla would have null targetPos until install, killing both).
            this.pathweaver$pendingReachRange = reachRange;
            this.pathweaver$pendingInstallSpeed = this.pathweaver$requestSpeed;
            // Remember what to restore if this request never installs a path. Without this the
            // navigation is left with targetPos naming the new target while path still holds the
            // previous one, and vanilla's reuse short-circuit then hands back the stale path.
            this.pathweaver$targetPosBeforeDispatch = this.targetPos;
            this.targetPos = targetsCopy.iterator().next();
            this.pathweaver$optimisticTargetPos = this.targetPos;

            // Run the search's prologue HERE, on the main thread, instead of on the worker.
            // PathFinderMixin skips the worker's own call, so this happens exactly once. Everything
            // an evaluator does to the live mob happens in prepare/done -- the walk callbacks, the
            // amphibious malus save-and-overwrite -- so running the real method is both more faithful
            // than replaying a remembered callback count and the reason flying and amphibious mobs
            // can dispatch at all.
            //
            // The obligation to call done() is recorded only once prepare has fully returned. A
            // prepare that throws part-way has written an unknown subset of its state, and the
            // amphibious epilogue restores from fields its prologue was supposed to have filled:
            // finishing a half-started evaluator would write zeroed malus values onto the mob
            // permanently. An unbalanced onPathfindingDone is the milder failure, and vanilla itself
            // leaves that pair unbalanced whenever getStart() returns null.
            // Scoped so the context this builds is isolated from the level's shared PathTypeCache:
            // the worker about to use it writes through that cache, and PathfindingContextMixin
            // decides by asking whether the search runs off-thread, not which thread is running.
            boolean outerPrologue = dev.pathweaver.async.PathWeaverThread.enterAsyncPrologue();
            try {
                freshEval.prepare(region, theMob);
            } finally {
                dev.pathweaver.async.PathWeaverThread.exitAsyncPrologue(outerPrologue);
            }
            sink.armEpilogue(submittedKey, freshEval, requestStartGate);

            // Keep moving on the current path this tick; the async result installs next tick.
            //
            // KNOWN GAP on the recompute seam, deliberately not patched here -- see DESIGN.md 13.
            // `this.path` is already null when this runs, because vanilla nulls it immediately before
            // calling createPath, so a recompute-originated dispatch leaves the mob pathless until
            // the result drains. Returning the pre-null path was tried and is worse: a non-null
            // this.path re-enables the vanilla reuse short-circuit and Feature B elision on a seam
            // where neither may fire, which broke the exact-Swim witness in the routing game test.
            this.pathweaver$acceptedDeferred = true;
            cir.setReturnValue(this.path);
            authorizeSearch = true;
        } catch (Throwable t) {
            // Record it even when nothing was registered yet. `registered` is not set until the
            // request reaches the sink, and everything before that -- the attribute captures, the
            // region, the evaluator clone, the PathFinder, the target copy -- is inside this try.
            // A throw from any of them used to produce no outcome, no counter and no log line
            // anywhere, so a deterministic setup failure meant the mod did nothing forever while
            // reporting itself as fully working: "PathWeaver is ACTIVE", dispatched=0, no outcome
            // rows, silence in the log. That is the failure mode this project's own comments call
            // worse than having no diagnostic at all.
            //
            // The reachable trigger is a third-party evaluator whose no-argument constructor throws
            // when invoked outside the mod's own construction path: canClone proves a constructor
            // RESOLVES, never that it RUNS.
            if (registered) {
                sink.discard(requestKey, dev.pathweaver.async.RequestOutcome.SETUP_FAILED);
            } else {
                rt.markOutcome(dev.pathweaver.async.RequestOutcome.SETUP_FAILED_PRE_DISPATCH);
            }
            if (rt.claimSetupFailureLog()) {
                try {
                    dev.pathweaver.PathWeaver.LOG.warn("PathWeaver could not set up an async search "
                        + "and fell back to synchronous pathfinding for {}. Logged once per server "
                        + "session; the setup-failure rows in /pathweaver status keep counting.",
                        this.mob.getType().toShortString(), t);
                } catch (Throwable ignored) {
                    // Falling back to sync stays the outcome even if logging is compromised.
                }
            }
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
        // A real path is now installed, so there is nothing optimistic left to undo.
        this.pathweaver$optimisticTargetPos = null;
        this.pathweaver$targetPosBeforeDispatch = null;
    }

    @Override
    public void pathweaver$abortFailedInstall() {
        // moveTo may have set a new or partial path before a foreign injection threw. Clear it
        // rather than leave it paired with a restored older target; the next request recomputes.
        try {
            stop();
        } catch (Throwable stopFailure) {
            this.path = null;
        }
        pathweaver$rollbackOptimisticTarget();
    }

    @Override
    public void pathweaver$rollbackOptimisticTarget() {
        BlockPos optimistic = this.pathweaver$optimisticTargetPos;
        if (optimistic != null && optimistic.equals(this.targetPos)) {
            // Still ours: no newer request has claimed targetPos, so restoring is safe.
            this.targetPos = this.pathweaver$targetPosBeforeDispatch;
        }
        this.pathweaver$optimisticTargetPos = null;
        this.pathweaver$targetPosBeforeDispatch = null;
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

}
