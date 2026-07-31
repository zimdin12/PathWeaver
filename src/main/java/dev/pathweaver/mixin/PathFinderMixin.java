package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Runs a search's prologue and epilogue on the main thread instead of the worker.
 *
 * <p>{@code PathFinder.findPath} brackets the A* loop with {@code nodeEvaluator.prepare(region, mob)}
 * and {@code nodeEvaluator.done()}. Those two calls are where every vanilla evaluator touches the live
 * mob: {@code WalkNodeEvaluator} fires {@code onPathfindingStart}/{@code onPathfindingDone}, and
 * {@code AmphibiousNodeEvaluator} saves and overwrites three pathfinding malus values and restores two.
 * The A* loop between them only reads. That is the whole reason flying and amphibious mobs were
 * excluded from async pathfinding — not the search, just its two ends.
 *
 * <p>So the ends move rather than the search. When a worker is executing, both calls are skipped here;
 * {@code PathNavigationMixin} has already run {@code prepare} on the main thread before dispatch and
 * runs {@code done} on the main thread at install or discard, through the registration machinery that
 * guarantees exactly one completion across success, supersede, stop, shutdown and exception. On the
 * main thread {@code isWorker()} is false and vanilla is untouched, including every synchronous search.
 *
 * <p>This generalises what a per-class table of callback counts used to do for exactly two classes.
 * Running the evaluator's real {@code prepare}/{@code done} needs no knowledge of what they contain,
 * so a third-party evaluator's own prologue is handled by construction rather than by an entry
 * somebody has to remember to add.
 *
 * <p>Both redirects are {@code require = 1}: if a future Minecraft moves these calls, the mixin fails
 * loudly at load. That matters more than usual here, because silently failing to skip would let the
 * worker run a second {@code prepare} after the main thread's — and for the amphibious evaluator a
 * second prepare captures the already-overwritten malus as the value to restore, permanently
 * corrupting the mob's malus map. A loud failure at startup is the only acceptable failure mode.
 */
@Mixin(PathFinder.class)
public class PathFinderMixin {

    @Redirect(
        method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;"
                + "prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V"),
        require = 1,
        expect = 1
    )
    private void pathweaver$prepareOnMainThreadInstead(
            NodeEvaluator evaluator, PathNavigationRegion region, Mob mob) {
        if (!PathWeaverThread.isWorker()) evaluator.prepare(region, mob);
    }

    @Redirect(
        method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;done()V"),
        require = 1,
        expect = 1
    )
    private void pathweaver$finishOnMainThreadInstead(NodeEvaluator evaluator) {
        if (!PathWeaverThread.isWorker()) evaluator.done();
    }
}
