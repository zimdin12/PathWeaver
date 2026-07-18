package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Critical safety linchpin: isolate the shared {@code PathTypeCache} from worker threads.
 *
 * <p>Vanilla {@code PathfindingContext(CollisionGetter, Mob)} does, verified against 26.1.2 bytecode:
 * {@code if (mob.level() instanceof ServerLevel sl) this.cache = sl.getPathTypeCache();} — grabbing
 * the LIVE, shared cache and ignoring the read-only region it was handed. {@code PathTypeCache.compute}
 * then WRITES its shared {@code long[] positions} / {@code PathType[] pathTypes} arrays with no sync,
 * so an off-thread search would corrupt the very cache that synchronous (non-async) mobs read.</p>
 *
 * <p>This redirect leaves the main thread untouched (returns the real shared cache) and, only when a
 * PathWeaver worker is running the search, substitutes a FRESH per-search {@code PathTypeCache}. The
 * off-thread search then recomputes path types from the passed read-only region into a thread-confined
 * cache — zero shared writes. Confirmed: {@code PathTypeCache} has a public no-arg constructor.</p>
 *
 * <p>Kept at {@code require = 1}: this is a hard safety invariant. If a future mapping drift stops it
 * applying, the game must fail loudly rather than silently run async without cache isolation.</p>
 */
@Mixin(PathfindingContext.class)
public class PathfindingContextMixin {

    @Redirect(
        method = "<init>(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/Mob;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getPathTypeCache()Lnet/minecraft/world/level/pathfinder/PathTypeCache;"
        ),
        require = 1
    )
    private PathTypeCache pathweaver$isolateCache(ServerLevel serverLevel) {
        if (PathWeaverThread.isWorker()) {
            // Thread-confined, per-search cache: recompute from the read-only region, never touch shared state.
            return new PathTypeCache();
        }
        return serverLevel.getPathTypeCache(); // main thread: unchanged vanilla behaviour.
    }
}
