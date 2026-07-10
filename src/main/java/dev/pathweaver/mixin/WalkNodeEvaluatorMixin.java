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
 * PathWeaver worker is executing the search we skip these calls here; {@code PathNavigationMixin} fires
 * the balanced start/done pair on the MAIN thread instead (at dispatch / at install-or-discard).</p>
 *
 * <p>On the main thread {@code isWorker()} is false, so vanilla behaviour is unchanged. Non-critical
 * ({@code require = 0} via config default): if this ever fails to apply, the only cost is that a modded
 * callback runs off-thread — the search itself stays cache-isolated by the required FIX 1 redirect.</p>
 */
@Mixin(WalkNodeEvaluator.class)
public class WalkNodeEvaluatorMixin {

    @Redirect(
        method = "prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;onPathfindingStart()V")
    )
    private void pathweaver$skipStartOffThread(Mob mob) {
        if (!PathWeaverThread.isWorker()) mob.onPathfindingStart();
    }

    @Redirect(
        method = "done()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;onPathfindingDone()V")
    )
    private void pathweaver$skipDoneOffThread(Mob mob) {
        if (!PathWeaverThread.isWorker()) mob.onPathfindingDone();
    }
}
