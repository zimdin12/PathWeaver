package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import dev.pathweaver.gate.FabricLandPathRegistryLatch;
import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publishes provider mutation before the live map changes and keeps workers out of that map. */
@Mixin(LandPathTypeRegistry.class)
abstract class LandPathTypeRegistryMixin {
    @Inject(
        method = "register(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$StaticPathTypeProvider;)V",
        at = @At(value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        require = 1,
        expect = 1)
    private static void pathweaver$beforeStaticProviderMutation(
            Block block, LandPathTypeRegistry.StaticPathTypeProvider provider, CallbackInfo ci) {
        FabricLandPathRegistryLatch.beforeProviderMutation();
    }

    @Inject(
        method = "registerDynamic(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider;)V",
        at = @At(value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        require = 1,
        expect = 1)
    private static void pathweaver$beforeDynamicProviderMutation(
            Block block, LandPathTypeRegistry.DynamicPathTypeProvider provider, CallbackInfo ci) {
        FabricLandPathRegistryLatch.beforeProviderMutation();
    }

    @Inject(
        method = "getPathTypeProvider(Lnet/minecraft/world/level/block/Block;)Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$PathTypeProvider;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1)
    private static void pathweaver$keepWorkerOutOfLiveProviderMap(
            Block block,
            CallbackInfoReturnable<LandPathTypeRegistry.PathTypeProvider> cir) {
        if (PathWeaverThread.isWorker()) {
            FabricLandPathRegistryLatch.recordWorkerProviderLookupBypass();
            cir.setReturnValue(null);
        }
    }
}
