package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the live mob's randomness off the worker thread.
 *
 * <p>Verified on 26.1.2: {@code FlyNodeEvaluator} reads {@code Mob.getRandom()} in exactly one place,
 * {@code iteratePathfindingStartNodeCandidatePositions}, reached only from {@code getStart()}. It uses
 * the draw to pick an arbitrary start candidate near the mob. That single read is the entire reason
 * flying mobs pathed synchronously — a worker calling it would advance a {@code RandomSource} the
 * server thread is also using, which is a data race on shared entity state.
 *
 * <p>A worker gets its own source instead. Vanilla's contract here is that the candidate is chosen
 * arbitrarily, so a different arbitrary choice is still a correct search; nothing downstream depends
 * on which candidate was drawn. What changes is that the mob's own RNG sequence is not advanced while
 * a search runs off-thread. Minecraft makes no reproducibility guarantee about that sequence, and the
 * alternative is a real race, so the trade is the right way round.
 *
 * <p>On the main thread {@code isWorker()} is false and the mob's own randomness is used, so every
 * synchronous search behaves exactly as vanilla does.
 */
@Mixin(FlyNodeEvaluator.class)
public class FlyNodeEvaluatorMixin {

    @Redirect(
        method = "iteratePathfindingStartNodeCandidatePositions(Lnet/minecraft/world/entity/Mob;)"
            + "Ljava/lang/Iterable;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;getRandom()Lnet/minecraft/util/RandomSource;"),
        require = 1,
        expect = 1
    )
    private RandomSource pathweaver$workerLocalRandom(Mob mob) {
        return PathWeaverThread.isWorker() ? PathWeaverThread.workerRandom() : mob.getRandom();
    }
}
