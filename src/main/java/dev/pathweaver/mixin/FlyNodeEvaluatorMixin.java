package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Flying pathfinding remains synchronous in v0.2.0 because vanilla start-node selection consumes the
 * live mob RNG. These redirects are defensive main-thread pass-through guards; Fly is not part of the
 * supported worker callback-replay contract.
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
