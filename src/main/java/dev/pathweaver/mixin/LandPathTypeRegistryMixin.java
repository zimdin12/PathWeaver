package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import dev.pathweaver.gate.CertifiedLandProviders;
import dev.pathweaver.gate.FabricLandPathRegistryLatch;
import net.fabricmc.fabric.api.registry.LandPathTypeRegistry;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Publishes provider mutation before the live map changes and keeps workers out of that map.
 *
 * <p>A static registration is certified instead of denied. Its provider cannot read the world, so
 * every answer it can give is precomputed here on the main thread and frozen; the worker reads the
 * frozen table and the mod's code never runs off-thread. A dynamic registration does receive the
 * world, so it still denies.
 */
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
        // Certification runs before the live map is mutated, so a worker can never observe a block
        // that is registered but not yet frozen. If it cannot be completed the latch still denies,
        // because a partial table would answer some states and silently diverge on the rest.
        if (!CertifiedLandProviders.certify(block, provider)) {
            FabricLandPathRegistryLatch.beforeProviderMutation();
        }
    }

    @Inject(
        method = "registerDynamic(Lnet/minecraft/world/level/block/Block;Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$DynamicPathTypeProvider;)V",
        at = @At(value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        require = 1,
        expect = 1)
    private static void pathweaver$beforeDynamicProviderMutation(
            Block block, LandPathTypeRegistry.DynamicPathTypeProvider provider, CallbackInfo ci) {
        // A dynamic provider receives the world, so it normally denies. One exception is carried by an
        // exact audit proving the provider never loads the world or position it is handed, which makes
        // it precomputable like a static one.
        //
        // The tier is deliberately not read here. Mods register blocks from their own initializer,
        // which can run before PathWeaver has loaded its config -- Farmer's Delight does exactly that
        // -- so this would see the fail-closed default and deny whatever the operator had chosen. The
        // audit result is published instead, and the tier decides at dispatch.
        if (CertifiedLandProviders.certifyAuditedDynamic(block, provider)) {
            FabricLandPathRegistryLatch.auditedDynamicProviderRegistered();
            return;
        }
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
        if (!PathWeaverThread.isWorker()) return;
        FabricLandPathRegistryLatch.recordWorkerProviderLookupBypass();
        // Serve the frozen answer for certified blocks. Returning null here would mean "no rule
        // exists", which is the wrong answer once a mod has registered one, and is exactly how a
        // mob would be routed over a block the mod marked dangerous.
        cir.setReturnValue(CertifiedLandProviders.isCertified(block)
            ? CertifiedLandProviders.frozenProvider()
            : null);
    }
}
