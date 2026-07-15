package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FIX 2a: keep the live-mob entity callbacks off the worker thread.
 *
 * <p>Verified on 26.1.2: {@code WalkNodeEvaluator.prepare} calls {@code mob.onPathfindingStart()} and
 * {@code done()} calls {@code mob.onPathfindingDone()}. Both are no-ops in vanilla {@code Mob}, but a
 * mod may override them to mutate live entity state — which must never run on a worker thread. When a
 * PathWeaver worker is executing the search we skip these calls here; {@code PathNavigationMixin}
 * replays callbacks on the MAIN thread for accepted requests. The request registration owns exact-once
 * completion across success, discard, clear, shutdown, and exception paths.</p>
 *
 * <p>On the main thread {@code isWorker()} is false, so these redirects call the original callback.
 * Both redirects are required: allowing Walk async without them could run a modded callback's entity
 * mutation on a worker.</p>
 */
@Mixin(WalkNodeEvaluator.class)
public class WalkNodeEvaluatorMixin {

    @Redirect(
        method = "prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;onPathfindingStart()V"),
        require = 1,
        expect = 1
    )
    private void pathweaver$skipStartOffThread(Mob mob) {
        if (!PathWeaverThread.isWorker()) mob.onPathfindingStart();
    }

    @Redirect(
        method = "done()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;onPathfindingDone()V"),
        require = 1,
        expect = 1
    )
    private void pathweaver$skipDoneOffThread(Mob mob) {
        if (!PathWeaverThread.isWorker()) mob.onPathfindingDone();
    }
}
