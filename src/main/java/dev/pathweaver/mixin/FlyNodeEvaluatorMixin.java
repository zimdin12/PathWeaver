package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FIX 2a for flying mobs: same as {@link WalkNodeEvaluatorMixin}. Verified on 26.1.2:
 * {@code FlyNodeEvaluator.prepare} calls {@code mob.onPathfindingStart()} and {@code done()} calls
 * {@code mob.onPathfindingDone()}. Skipped on worker threads; replayed on the main thread by
 * {@code PathNavigationMixin}.
 */
@Mixin(FlyNodeEvaluator.class)
public class FlyNodeEvaluatorMixin {

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
