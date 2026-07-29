package dev.pathweaver.gametest.mixin;

import dev.pathweaver.async.PathWeaverThread;
import dev.pathweaver.gametest.RabbitWorkerReachabilityProbe;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only live witness for Rabbit's two modified PathNavigation methods. */
@Mixin(PathNavigation.class)
public abstract class RabbitWorkerReachabilityProbeMixin {
    @Inject(method = "doStuckDetection(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"), require = 1, expect = 1)
    private void pathweaver$probeDoStuckDetection(Vec3 position, CallbackInfo ci) {
        if (PathWeaverThread.isWorker()) RabbitWorkerReachabilityProbe.recordWorkerEntry();
    }

    @Inject(method = "resetStuckTimeout()V", at = @At("HEAD"), require = 1, expect = 1)
    private void pathweaver$probeResetStuckTimeout(CallbackInfo ci) {
        if (PathWeaverThread.isWorker()) RabbitWorkerReachabilityProbe.recordWorkerEntry();
    }
}
