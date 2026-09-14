package dev.pathweaver.frameprobe.mixin;

import dev.pathweaver.frameprobe.PathCalls;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

// Priority 100: runs before PathWeaver's own HEAD inject, which can cancel, so a call is counted
// whether PathWeaver dispatches it, answers it or leaves it to vanilla.
@Mixin(value = PathNavigation.class, priority = 100)
public abstract class PathNavigationCallMixin {
    @Shadow @Final protected Mob mob;

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"), require = 1)
    private void pwprobe$count(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int reachRange,
                               float followRange, CallbackInfoReturnable<Path> cir) {
        if (this.mob.level().isClientSide()) return;
        PathCalls.entered(this.mob);
    }
}
