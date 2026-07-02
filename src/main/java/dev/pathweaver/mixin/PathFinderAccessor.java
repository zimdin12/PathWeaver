package dev.pathweaver.mixin;

import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the private {@code maxVisitedNodes} so async searches can build an identically-sized finder. */
@Mixin(PathFinder.class)
public interface PathFinderAccessor {
    @Accessor("maxVisitedNodes")
    int pathweaver$getMaxVisitedNodes();
}
