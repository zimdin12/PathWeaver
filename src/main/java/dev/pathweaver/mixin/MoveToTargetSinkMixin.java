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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Feature C: offload the villager-brain movement sink.
 *
 * <p>Brain mobs — villagers, piglins, axolotls, frogs, allays, camels, the warden, and about twenty
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

        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) return;
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        if (!runtime.isRunning()) return;

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) return;

        Optional<WalkTarget> walkTarget = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (walkTarget.isEmpty()) return;
        BlockPos asked = walkTarget.get().getTarget().currentBlockPosition();

        EntityInstallSink sink = runtime.entitySink();
        int entityId = mob.getId();

        Path landed = sink.takeBrainSinkPath(entityId, asked);
        if (landed != null) {
            pathweaver$supply(landed);
            return;
        }

        if (sink.hasPendingBrainSink(entityId, asked)) {
            cir.setReturnValue(false);
            return;
        }

        // Ask the navigation, then read what it did. Whether dispatch happens is decided behind the
        // safety gate, the origin gate, admission and the breaker; MobEligibility exists for
        // reporting and its own comment warns it can disagree with dispatch, so predicting is not an
        // option. Dispatch records its own slot at the point it registers, which is the only place
        // that knows a request was really admitted.
        Path immediate;
        duck.pathweaver$enterBrainSinkRequest(walkTarget.get().getSpeedModifier(), asked);
        try {
            immediate = navigation.createPath(asked, PATHWEAVER$VANILLA_REACH_RANGE);
        } finally {
            duck.pathweaver$exitBrainSinkRequest();
        }

        if (sink.hasPendingBrainSink(entityId, asked)) {
            cir.setReturnValue(false);
            return;
        }

        // Still registered, but no slot of ours: another request for this mob is in flight and
        // dispatch either preserved it or superseded it. `immediate` on those routes is the mob's
        // CURRENTLY INSTALLED path, not a search result -- handing it back would answer this
        // destination with the route to a different one. Waiting a tick costs nothing now that the
        // deferral no longer erases the walk target.
        if (sink.isRegistered(entityId)) {
            cir.setReturnValue(false);
            return;
        }

        // Dispatch was refused outright, so this really is vanilla's own synchronous answer -- a
        // path, or null meaning no route exists. Either way it is the value vanilla would have had,
        // so hand it to the real call site rather than searching for the same destination twice.
        pathweaver$supply(immediate);
    }

    @Unique
    private void pathweaver$supply(Path path) {
        pathweaver$suppliedPath = path;
        pathweaver$hasSuppliedPath = true;
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
        // Consumed once. Leaving it set would answer a later, different question with this path.
        pathweaver$suppliedPath = null;
        pathweaver$hasSuppliedPath = false;

        // Replay the tail the real createPath would have run. Only createPath writes targetPos and
        // reachRange; the moveTo(Path, double) that start() uses writes neither. Without this the
        // navigation ends up holding a route to one destination while targetPos names another, and
        // the next recomputePath() reads targetPos and walks the mob back to the old one.
        if (instance instanceof PWNavigation duck) {
            duck.pathweaver$replayCreatePathTail(supplied, reachRange);
        }
        return supplied;
    }
}
