package dev.pathweaver.mixin;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.PathWeaverConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the shared route cache when the world changes underneath it.
 *
 * <p>{@code sendBlockUpdated} is the one place vanilla itself treats as "a block visibly changed":
 * it is where the level invalidates its own {@code PathTypeCache} entry and where it asks every
 * navigating mob whether the change should make it recompute. Hooking the same call means the cache
 * learns about exactly the changes vanilla considers worth reacting to, rather than a set this mod
 * chose for itself.
 *
 * <p>This method decides nothing. It adapts the level's arguments and hands them to
 * {@link dev.pathweaver.cache.BlockChangeObserver}, which owns the whole rule and, unlike a mixin,
 * can be executed by a test. A hook holding half the rule is how the two switches drift apart, and a
 * hook holding a rule nothing can run is how a wrong one survives.
 *
 * <p>Cost is a config read, a section-coordinate pack and one array write. The method it joins
 * already iterates the level's navigating mobs, so this is not a new hot path.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockChangeMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), require = 1, expect = 1)
    private void pathweaver$noteBlockChange(BlockPos pos, BlockState oldState, BlockState newState,
                                            int flags, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        PathWeaverRuntime runtime = PathWeaverRuntime.get();
        dev.pathweaver.cache.BlockChangeObserver.observe(
            PathWeaverConfig.get(), runtime.isRunning(), runtime.resultCache(),
            level.dimension().hashCode(),
            SectionPos.asLong(pos), level.getServer().getTickCount());
    }
}
