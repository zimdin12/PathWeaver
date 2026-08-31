package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.brain.BrainSinkDiagnostics;
import dev.pathweaver.brain.BrainSinkPolicy;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Feature C: offload the villager-brain movement sink.
 *
 * <p>Brain mobs — villagers, piglins, axolotls, frogs, allays, camels, and about twenty
 * other AI packages — never call {@code moveTo(x, y, z, speed)}. Every path they walk is computed by
 * {@code MoveToTargetSink.tryComputePath}, which calls {@code createPath} and reads the answer on the
 * next line, so the four ordinary dispatch sites never see them. They were not being refused; they
 * were invisible.
 *
 * <h2>Why the deferral is taken HERE and not inside tryComputePath</h2>
 *
 * The first version of this hook deferred by returning false from {@code tryComputePath}. That is
 * wrong, and vanilla's bytecode says so plainly. {@code checkExtraStartConditions} responds to a
 * false like this:
 *
 * <pre>{@code
 *  61: invokevirtual tryComputePath(Mob;WalkTarget;J)Z
 *  64: ifeq 83
 *  83: aload_3                                  // brain
 *  84: getstatic MemoryModuleType.WALK_TARGET
 *  87: invokevirtual Brain.eraseMemory
 * }</pre>
 *
 * A deferral therefore ERASED the mob's destination — every tick the search was outstanding, not
 * once. {@code MoveToTargetSink} is priority 1 in the villager core package and {@code Brain}
 * iterates priorities ascending, so every walk-target setter gated on {@code absent(WALK_TARGET)}
 * runs later in the same tick and takes the freed slot. For the random strollers that slot is filled
 * with a FRESH position, so the next tick asked about somewhere else, the parked answer could never
 * be collected, and the mob dispatched a new search every tick while never going anywhere.
 *
 * <p>Cancelling at the head of {@code checkExtraStartConditions} skips that entire body, erase
 * included. This is also why {@code tryComputePath} carries no deferral at all any more: the
 * {@code tick()} call site is harmless on a false, but a second decision point could be reached from
 * the start path and re-introduce exactly the bug above.
 *
 * <h2>Why the landed path goes back through vanilla</h2>
 *
 * When the result arrives it is handed to the real {@code createPath} call site rather than assigned,
 * so vanilla's own reachability test, its {@code CANT_REACH_WALK_TARGET_SINCE} handling and its
 * random-position fallback all run as they would have. Reimplementing that logic here is the obvious
 * shortcut and the obvious way to get it subtly wrong.
 *
 * @see dev.pathweaver.async.RequestOrigin#BRAIN_SINK
 */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {

    /** Vanilla calls {@code createPath(pos, 0)} here — offset 18 is {@code iconst_0}. */
    @Unique private static final int PATHWEAVER$VANILLA_REACH_RANGE = 0;


    /** Consecutive ticks this behaviour has deferred without collecting anything. */
    @Unique private final BrainSinkPolicy pathweaver$policy = new BrainSinkPolicy();


    /**
     * True while {@code checkExtraStartConditions} owns the decision for this call.
     *
     * <p>The two hooks were fighting. When the start check declines to defer -- at the liveness
     * bound, or having supplied nothing -- vanilla carries on into {@code tryComputePath}, whose hook
     * then ran the decision a SECOND time against a counter the bound had just reset, deferred, and
     * so returned the false that makes vanilla erase the walk target. The bound could never take
     * effect and the mob never got a path: measured at three failures in four runs.
     *
     * <p>Keying the stand-down on {@code hasSuppliedPath} is not enough, because the fall-through
     * path deliberately supplies nothing. One decision per start attempt is the property that
     * matters, so it is stated directly.
     */
    /**
     * The game tick on which the start check claimed the decision, or {@link Long#MIN_VALUE}.
     *
     * <p>A tick rather than a boolean, so a leaked claim cannot outlive the call it belongs to. As a
     * boolean it was released on the cancel path, the throw path and at RETURN -- but a foreign mixin
     * making VANILLA's body throw unwinds past all three, and the flag stayed set for the life of the
     * behaviour instance, permanently and silently standing the tick-route hook down. Comparing
     * against the current tick means the worst a leak can cost is the rest of one tick.
     */
    @Unique private long pathweaver$startCheckClaimTick = Long.MIN_VALUE;

    /**
     * Vanilla's throttle for a mob that cannot make progress: {@code stop()} sets it to
     * {@code random.nextInt(40)} whenever the navigation reports itself stuck.
     */
    @Shadow private int remainingCooldown;

    @Shadow private boolean reachedTarget(Mob mob, WalkTarget walkTarget) {
        throw new AssertionError();
    }

    /**
     * The path this call should use instead of searching.
     *
     * <p>Paired with an explicit flag rather than using null as the sentinel. Null is a legitimate
     * VALUE here — "dispatch was refused and vanilla proved there is no route" — and overloading it
     * to also mean "nothing supplied" made the wrap fall through to {@code original} and run a second
     * full synchronous search on exactly the refusal routes that matter: a safety-gate denial, a
     * modded mob refused by the origin gate, or an entity in its failure cooldown.
     */
    @Unique private Path pathweaver$suppliedPath;
    @Unique private boolean pathweaver$hasSuppliedPath;
    /**
     * True only for a path taken from the park.
     *
     * <p>The tail replay must not run on the refusal route. There, vanilla's real {@code createPath}
     * already ran and may have returned through its reuse short-circuit (offsets 41-75), which exits
     * BEFORE the tail at 193-225 -- so vanilla deliberately wrote neither {@code targetPos} nor
     * {@code reachRange} nor reset the stuck timeout. Replaying unconditionally would overwrite a
     * target vanilla chose to leave alone.
     */
    @Unique private boolean pathweaver$suppliedFromPark;

    @Inject(
        method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1
    )
    private void pathweaver$deferBeforeVanillaCanForgetTheTarget(
            ServerLevel level, Mob mob, CallbackInfoReturnable<Boolean> cir) {
        pathweaver$suppliedPath = null;
        pathweaver$hasSuppliedPath = false;
        pathweaver$suppliedFromPark = false;

        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) {
            BrainSinkDiagnostics.recordStartCheck(mob.getId(), "off");
            return;
        }
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        if (!runtime.isRunning()) {
            BrainSinkDiagnostics.recordStartCheck(mob.getId(), "notRunning");
            return;
        }

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) {
            BrainSinkDiagnostics.recordStartCheck(mob.getId(), "noDuck");
            return;
        }

        Optional<WalkTarget> walkTarget = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (walkTarget.isEmpty()) {
            BrainSinkDiagnostics.recordStartCheck(mob.getId(), "noWalkTarget");
            return;
        }
        BlockPos asked = walkTarget.get().getTarget().currentBlockPosition();

        // VANILLA'S OWN TWO GUARDS, REPRODUCED, because this inject sits above both of them and
        // skipping them is not free.
        //
        //   0-18  remainingCooldown > 0  -> decrement and refuse
        //  39-50  reachedTarget(...)     -> erase WALK_TARGET and refuse, no path computed
        //
        // Jumping the first one made the mod dispatch a search every other tick for a mob vanilla
        // had deliberately stopped pathing, and -- because the cancel returns before the decrement
        // at offsets 7-14 -- held the throttle open for roughly twice as long. The feature inverted
        // the exact guard that exists to stop a stuck mob pathfinding.
        //
        // Jumping the second dispatched a full A* to a block the mob was already standing on, and
        // withheld the arrival erase for a tick.
        //
        // Both must be checked BEFORE takeBrainSinkPath, not after: taking removes the slot, and if
        // vanilla then returns at either guard the wrap never runs and the answer is destroyed --
        // which is what turns a single wasted search into a loop.
        if (remainingCooldown > 0) {
            BrainSinkDiagnostics.recordStartCheck(mob.getId(), "cooldown");
            return;
        }
        if (reachedTarget(mob, walkTarget.get())) {
            BrainSinkDiagnostics.recordStartCheck(mob.getId(), "reached");
            return;
        }

        pathweaver$startCheckClaimTick = level.getGameTime();
        boolean defer;
        try {
            defer = pathweaver$decideDefers(mob, navigation, duck, walkTarget.get(), asked,
                level.getGameTime());
        } catch (Throwable failure) {
            // A throw is not a RETURN either, so the handler below will not run.
            pathweaver$startCheckClaimTick = Long.MIN_VALUE;
            throw failure;
        }
        BrainSinkDiagnostics.recordStartCheck(mob.getId(), defer ? "DEFER" : "ranVanillaOrSupplied");
        if (defer) {
            cir.setReturnValue(false);
            // Released HERE, because @At("RETURN") does not fire for an @Inject cancellation:
            // CallbackInjector.injectReturnCode constructs a NEW return for it, and injection points
            // were resolved against pre-injection bytecode, so that node is not in the handler's
            // list. Leaving it set was harmless only by accident of control flow -- a cancelled start
            // check leaves the behaviour STOPPED so tick() cannot run -- and it was written down as a
            // guarantee.
            pathweaver$startCheckClaimTick = Long.MIN_VALUE;
        }
        // Not deferring: vanilla's body now runs tryComputePath, and the claim must still be held
        // across it so the second hook stands down. try/finally here was tried and is WRONG for
        // exactly that reason -- it releases when THIS handler returns, which is before vanilla's
        // body runs, so the tick hook re-ran the decision and reintroduced the bug a31ad01 fixed.
        // Three game tests caught it. The RETURN handler is what covers this path.
    }

    /** Release the claim after vanilla's body has run. Covers the non-cancelled path only. */
    @Inject(
        method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;)Z",
        at = @At("RETURN"),
        require = 1
    )
    private void pathweaver$releaseDecisionClaim(ServerLevel level, Mob mob,
                                                 CallbackInfoReturnable<Boolean> cir) {
        pathweaver$startCheckClaimTick = Long.MIN_VALUE;
    }

    /**
     * Take, defer or dispatch. Returns true when the caller must report "no path this tick".
     *
     * <p>Shared by both call sites so they cannot drift apart. The two vanilla guards are NOT in
     * here: they belong to {@code checkExtraStartConditions} only, and {@code tick()} reaches
     * {@code tryComputePath} without them.
     */
    @Unique
    private boolean pathweaver$decideDefers(Mob mob, PathNavigation navigation, PWNavigation duck,
                                            WalkTarget walkTarget, BlockPos asked, long gameTime) {
        EntityInstallSink sink = PathWeaverRuntime.get().entitySink();
        int entityId = mob.getId();

        BrainSinkPolicy.Decision decision = pathweaver$policy.decide(
            entityId, asked, walkTarget.getSpeedModifier(), gameTime,
            new BrainSinkPolicy.SearchPort() {
                @Override public Path takeParked(int id, BlockPos at) {
                    return sink.takeBrainSinkPath(id, at);
                }
                @Override public boolean hasPending(int id, BlockPos at) {
                    return sink.hasPendingBrainSink(id, at);
                }
                @Override public boolean isRegistered(int id) {
                    return sink.isRegistered(id);
                }
                @Override public boolean acceptableToVanilla(Path path) {
                    // The two conditions moveTo(Path, double) refuses on: an already-finished path,
                    // and one that trims to no nodes.
                    return path != null && !path.isDone() && path.getNodeCount() > 0;
                }
                @Override public BrainSinkPolicy.Probe probe(double speed, BlockPos at) {
                    // A window already open on this navigation means something re-entered; the
                    // navigation refuses and the policy leaves the call to vanilla.
                    if (!duck.pathweaver$enterBrainSinkRequest(speed, at)) {
                        return BrainSinkPolicy.Probe.refused();
                    }
                    try {
                        return BrainSinkPolicy.Probe.ran(
                            navigation.createPath(at, PATHWEAVER$VANILLA_REACH_RANGE));
                    } finally {
                        duck.pathweaver$exitBrainSinkRequest();
                    }
                }
            });

        switch (decision.action()) {
            case DEFER -> {
                return true;
            }
            case SUPPLY_FROM_PARK -> pathweaver$supply(decision.path(), true);
            case SUPPLY_FROM_REFUSAL -> pathweaver$supply(decision.path(), false);
            case RUN_VANILLA -> { }
        }
        return false;
    }

    /**
     * The {@code tick()} re-path route, which is otherwise not offloaded at all.
     *
     * <p>Dropping this hook when the deferral moved upstream quietly halved the feature. Vanilla's
     * {@code tick()} calls {@code tryComputePath} whenever the walk target has drifted more than two
     * blocks (offsets 93-104), which is the dominant route for anything following a moving entity --
     * piglins, allays, frogs, temptation-followed mobs. With no hook there, nobody opens the
     * brain-sink window, {@code navigationRequestDepth} stays zero and every one of those searches
     * runs synchronously on the server thread.
     *
     * <p>A {@code false} here is harmless, which is why the deferral is safe on this route and not on
     * the other: {@code tick()} simply does not restart the behaviour, and the mob keeps walking the
     * path it already has.
     */
    @Inject(
        method = "tryComputePath(Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/world/entity/ai/memory/WalkTarget;J)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1
    )
    private void pathweaver$deferRepath(Mob mob, WalkTarget walkTarget, long gameTime,
                                        CallbackInfoReturnable<Boolean> cir) {
        // The start check has already decided for this call -- either it supplied a path the wrap is
        // about to consume, or it deliberately fell through so vanilla could search. Deciding again
        // here would override that, and did.
        if (pathweaver$startCheckClaimTick == gameTime) {
            BrainSinkDiagnostics.recordTickHook(mob.getId(), "standDown");
            return;
        }

        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) return;
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        if (!runtime.isRunning()) return;

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) return;

        BlockPos asked = walkTarget.getTarget().currentBlockPosition();
        boolean tickDefer = pathweaver$decideDefers(mob, navigation, duck, walkTarget, asked, gameTime);
        BrainSinkDiagnostics.recordTickHook(mob.getId(), tickDefer ? "DEFER" : "ranVanillaOrSupplied");
        if (tickDefer) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private void pathweaver$supply(Path path, boolean fromPark) {
        pathweaver$suppliedPath = path;
        pathweaver$hasSuppliedPath = true;
        pathweaver$suppliedFromPark = fromPark;
    }

    /**
     * Diagnostic only: the PATH memory's lifecycle, which is where the deadlock lives.
     *
     * <p>PATH is written by start() and tick() and erased by stop(). If it is present while the
     * behaviour is not running, the entry condition PATH VALUE_ABSENT can never be met again and the
     * mob is frozen for good. Recording start/stop pairs says whether a start went without its stop.
     *
     * <p>The RETURN hook on start() also answers whether vanilla's moveTo accepted the path we handed
     * it, which start() itself discards (offset 30 is a bare pop).
     */
    @Inject(
        method = "start(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;J)V",
        at = @At("RETURN"),
        require = 1
    )
    private void pathweaver$traceStart(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        BrainSinkDiagnostics.recordLifecycle(mob.getId(),
            "start(navPath=" + (mob.getNavigation().getPath() != null) + ")");
    }

    /**
     * Diagnostic: is the behaviour still being evaluated, and what is it answering?
     *
     * <p>The freeze shows one start with no matching stop. Either canStillUse keeps saying "yes" --
     * in which case it is lying about a navigation that is done -- or it is never called, in which
     * case the brain has stopped ticking this behaviour while its status is still RUNNING. Those
     * need opposite fixes, so the instrument distinguishes them rather than assuming.
     */
    @Inject(
        method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;J)Z",
        at = @At("RETURN"),
        require = 1
    )
    private void pathweaver$traceCanStillUse(ServerLevel level, Mob mob, long gameTime,
                                             CallbackInfoReturnable<Boolean> cir) {
        BrainSinkDiagnostics.recordLifecycle(mob.getId(),
            "csu=" + cir.getReturnValue() + "(done=" + mob.getNavigation().isDone() + ")");
    }

    @Inject(
        method = "stop(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;J)V",
        at = @At("RETURN"),
        require = 1
    )
    private void pathweaver$traceStop(ServerLevel level, Mob mob, long gameTime, CallbackInfo ci) {
        BrainSinkDiagnostics.recordLifecycle(mob.getId(), "stop");
    }

    @WrapOperation(
        method = "tryComputePath(Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/world/entity/ai/memory/WalkTarget;J)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;"
                + "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$useAlreadyComputedPath(PathNavigation instance, BlockPos target,
                                                   int reachRange, Operation<Path> original) {
        if (!pathweaver$hasSuppliedPath) return original.call(instance, target, reachRange);

        Path supplied = pathweaver$suppliedPath;
        boolean fromPark = pathweaver$suppliedFromPark;
        // Consumed once. Leaving it set would answer a later, different question with this path.
        pathweaver$suppliedPath = null;
        pathweaver$hasSuppliedPath = false;
        pathweaver$suppliedFromPark = false;

        // Replay the tail the real createPath would have run. Only createPath writes targetPos and
        // reachRange; the moveTo(Path, double) that start() uses writes neither. Without this the
        // navigation ends up holding a route to one destination while targetPos names another, and
        // the next recomputePath() reads targetPos and walks the mob back to the old one.
        if (fromPark && instance instanceof PWNavigation duck) {
            duck.pathweaver$replayCreatePathTail(supplied, reachRange);
        }
        return supplied;
    }
}
