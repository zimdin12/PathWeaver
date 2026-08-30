package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feature C: offload the villager-brain movement sink.
 *
 * <p>Brain mobs — villagers, piglins, axolotls, frogs, allays — never call
 * {@code moveTo(x, y, z, speed)}. Every movement path they take is computed by
 * {@code MoveToTargetSink.tryComputePath}, which calls {@code createPath} and reads the answer on
 * the next line, so the four ordinary dispatch sites never see them at all. They were not being
 * refused; they were invisible.
 *
 * <h2>Why this cannot hook the createPath call</h2>
 *
 * The obvious implementation — let the existing dispatch return null and carry on — is actively
 * harmful, and vanilla's bytecode says so. When {@code createPath} returns null,
 * {@code tryComputePath} does not simply report failure: it writes
 * {@code CANT_REACH_WALK_TARGET_SINCE} onto the brain and then runs a SECOND synchronous search, to
 * a random position near the target via {@code DefaultRandomPos.getPosTowards}. A mob whose search
 * had merely been deferred would therefore mark its destination unreachable and set off somewhere
 * random — more pathfinding than vanilla does, and visibly broken villagers.
 *
 * <p>So the deferral is taken at the top of {@code tryComputePath} instead, where reporting "no path
 * this tick" costs exactly one tick of delay and touches no memory. {@code tick()} treats the same
 * false as "do not restart yet", which is the intended meaning.
 *
 * <h2>Why the landed path goes back through vanilla</h2>
 *
 * When the result arrives it is handed to the real {@code createPath} call site rather than assigned
 * directly, so vanilla's own reachability test, its {@code CANT_REACH_WALK_TARGET_SINCE} handling and
 * its random-position fallback all run exactly as they would have. Reimplementing that logic here is
 * the obvious shortcut and the obvious way to get it subtly wrong.
 *
 * @see dev.pathweaver.async.RequestOrigin#BRAIN_SINK
 */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin {

    /**
     * The path this call should use instead of searching, or null to search normally.
     *
     * <p>Written at HEAD and consumed by the wrap within the same synchronous call, so it cannot
     * outlive the invocation that set it even if a mob type were to share one behaviour instance.
     * Cleared at HEAD before any decision for the same reason.
     */
    @Unique private Path pathweaver$suppliedPath;

    @Inject(method = "tryComputePath", at = @At("HEAD"), cancellable = true)
    private void pathweaver$deferToWorker(Mob mob, WalkTarget walkTarget, long gameTime,
                                          CallbackInfoReturnable<Boolean> cir) {
        pathweaver$suppliedPath = null;

        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) return;
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        if (!runtime.isRunning()) return;

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) return;

        BlockPos asked = walkTarget.getTarget().currentBlockPosition();
        EntityInstallSink sink = runtime.entitySink();
        int entityId = mob.getId();

        Path landed = sink.takeBrainSinkPath(entityId, asked);
        if (landed != null) {
            // Fall through into vanilla, which will read this through the wrap below.
            pathweaver$suppliedPath = landed;
            return;
        }

        if (sink.hasPendingBrainSink(entityId, asked)) {
            // The one behaviour difference this feature has: no path this tick, and deliberately no
            // memory write and no fallback search, because the question has not been answered yet
            // rather than answered "unreachable".
            cir.setReturnValue(false);
            return;
        }

        // Whether dispatch happens is decided deep inside the navigation mixin, behind the safety
        // gate, the origin gate, admission and the breaker. Rather than predict it -- MobEligibility
        // exists for reporting and its own comment warns it can disagree with dispatch -- ask the
        // navigation and then read what actually happened.
        //
        // A registration already present means dispatch will refuse anyway (it declines while one is
        // in flight), so the before/after comparison cannot mistake someone else's request for ours.
        boolean alreadyRegistered = sink.isRegistered(entityId);
        Path immediate;
        duck.pathweaver$enterBrainSinkRequest(walkTarget.getSpeedModifier());
        try {
            immediate = navigation.createPath(asked, 0);
        } finally {
            duck.pathweaver$exitBrainSinkRequest();
        }

        if (!alreadyRegistered && sink.isRegistered(entityId)) {
            sink.noteBrainSinkDispatch(entityId, asked);
            cir.setReturnValue(false);
            return;
        }

        // Dispatch was refused, so vanilla computed this synchronously just now. Hand that result to
        // the real call site instead of letting it search a second time for the same destination.
        // `immediate` may legitimately be null -- no route exists -- and vanilla's unreachable
        // handling is then exactly the behaviour we want, because nothing was deferred.
        pathweaver$suppliedPath = immediate;
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
        Path supplied = pathweaver$suppliedPath;
        pathweaver$suppliedPath = null;
        // Consumed once. Leaving it set would answer a later, different question with this path.
        if (supplied != null) return supplied;
        return original.call(instance, target, reachRange);
    }
}
