package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
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

    /**
     * How many ticks in a row this behaviour may answer "not yet" before it must answer for real.
     *
     * <p>A LIVENESS BOUND, and without it the feature can stall a mob indefinitely. The slot is keyed
     * on the exact destination asked for, and several vanilla behaviours rewrite that destination
     * every tick. {@code AnimalPanic.tick} is the worst: it overwrites WALK_TARGET with a FRESH
     * random position on every tick the navigation is idle, with no {@code absent(WALK_TARGET)} gate.
     * A deferral leaves the mob with no path, so {@code isDone()} stays true, so the destination
     * re-rolls, so the parked answer is never for the question being asked -- and the animal stands
     * still for the whole 5-6 second panic while dispatching one full A* per tick. A burning goat
     * never reaches water.
     *
     * <p>Two is enough to be useful and small enough to be safe: the common case lands in one tick,
     * and anything that has not answered in two gets vanilla's synchronous answer instead. Progress
     * is then guaranteed by construction rather than by hoping the destination holds still.
     */
    @Unique private static final int PATHWEAVER$MAX_CONSECUTIVE_DEFERRALS = 2;

    /** Consecutive ticks this behaviour has deferred without collecting anything. */
    @Unique private int pathweaver$consecutiveDeferrals;

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
    @Unique private boolean pathweaver$startCheckOwnsDecision;

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
        cancellable = true
    )
    private void pathweaver$deferBeforeVanillaCanForgetTheTarget(
            ServerLevel level, Mob mob, CallbackInfoReturnable<Boolean> cir) {
        pathweaver$suppliedPath = null;
        pathweaver$hasSuppliedPath = false;
        pathweaver$suppliedFromPark = false;

        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) return;
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        if (!runtime.isRunning()) return;

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) return;

        Optional<WalkTarget> walkTarget = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (walkTarget.isEmpty()) return;
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
        if (remainingCooldown > 0) return;
        if (reachedTarget(mob, walkTarget.get())) return;

        pathweaver$startCheckOwnsDecision = true;
        if (pathweaver$decideDefers(mob, navigation, duck, walkTarget.get(), asked)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Release the decision claim however this call ended, including the cancelled path.
     *
     * <p>{@code @At("RETURN")} fires for an {@code @Inject} cancellation too, so a deferred tick
     * clears the flag as reliably as one that fell through.
     */
    @Inject(
        method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/Mob;)Z",
        at = @At("RETURN")
    )
    private void pathweaver$releaseDecisionClaim(ServerLevel level, Mob mob,
                                                 CallbackInfoReturnable<Boolean> cir) {
        pathweaver$startCheckOwnsDecision = false;
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
                                            WalkTarget walkTarget, BlockPos asked) {
        EntityInstallSink sink = PathWeaverRuntime.get().entitySink();
        int entityId = mob.getId();

        Path landed = sink.takeBrainSinkPath(entityId, asked);
        if (landed != null) {
            pathweaver$consecutiveDeferrals = 0;
            pathweaver$supply(landed, true);
            return false;
        }

        // Liveness. Checked after the collection attempt, so a landed answer is never refused, and
        // before dispatch, so a mob that is about to be answered synchronously does not also start a
        // search nobody will collect.
        if (pathweaver$consecutiveDeferrals >= PATHWEAVER$MAX_CONSECUTIVE_DEFERRALS) {
            pathweaver$consecutiveDeferrals = 0;
            return false;
        }

        if (sink.hasPendingBrainSink(entityId, asked)) {
            pathweaver$consecutiveDeferrals++;
            return true;
        }

        // Ask the navigation, then read what it did. Whether dispatch happens is decided behind the
        // safety gate, the origin gate, admission and the breaker; MobEligibility exists for
        // reporting and its own comment warns it can disagree with dispatch, so predicting is not an
        // option. Dispatch records its own slot at the point it registers, which is the only place
        // that knows a request was really admitted.
        Path immediate;
        duck.pathweaver$enterBrainSinkRequest(walkTarget.getSpeedModifier(), asked);
        try {
            immediate = navigation.createPath(asked, PATHWEAVER$VANILLA_REACH_RANGE);
        } finally {
            duck.pathweaver$exitBrainSinkRequest();
        }

        if (sink.hasPendingBrainSink(entityId, asked)) {
            pathweaver$consecutiveDeferrals++;
            return true;
        }

        // Still registered, but no slot of ours: another request for this mob is in flight and
        // dispatch either preserved it or superseded it. `immediate` on those routes is the mob's
        // CURRENTLY INSTALLED path, not a search result -- handing it back would answer this
        // destination with the route to a different one. Waiting a tick costs nothing now that the
        // deferral no longer erases the walk target.
        if (sink.isRegistered(entityId)) {
            pathweaver$consecutiveDeferrals++;
            return true;
        }

        // Dispatch was refused outright, so this really is vanilla's own synchronous answer -- a
        // path, or null meaning no route exists. Either way it is the value vanilla would have had,
        // so hand it to the real call site rather than searching for the same destination twice.
        pathweaver$consecutiveDeferrals = 0;
        pathweaver$supply(immediate, false);
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
    @Inject(method = "tryComputePath", at = @At("HEAD"), cancellable = true)
    private void pathweaver$deferRepath(Mob mob, WalkTarget walkTarget, long gameTime,
                                        CallbackInfoReturnable<Boolean> cir) {
        // The start check has already decided for this call -- either it supplied a path the wrap is
        // about to consume, or it deliberately fell through so vanilla could search. Deciding again
        // here would override that, and did.
        if (pathweaver$startCheckOwnsDecision) return;

        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) return;
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        if (!runtime.isRunning()) return;

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) return;

        BlockPos asked = walkTarget.getTarget().currentBlockPosition();
        if (pathweaver$decideDefers(mob, navigation, duck, walkTarget, asked)) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private void pathweaver$supply(Path path, boolean fromPark) {
        pathweaver$suppliedPath = path;
        pathweaver$hasSuppliedPath = true;
        pathweaver$suppliedFromPark = fromPark;
    }

    @WrapOperation(
        method = "tryComputePath",
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
