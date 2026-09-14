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
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * Publishes provider mutation before the live map changes and keeps workers out of that map.
 *
 * <p>A static registration is certified instead of denied: every answer it can give is precomputed
 * here on the main thread and frozen, so the worker reads a table and the mod's code never runs
 * off-thread. That is not a proof of inertness — the signature shows only that the provider is not
 * handed the world, not that its answer is stable — so a certified provider is honoured above the
 * strict tier only. A dynamic registration does receive the world and denies unless an exact audit
 * covers it.
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
        if (CertifiedLandProviders.certify(block, provider)) {
            // Certified, not proven inert: the signature shows the provider is not handed the world,
            // which is not the same as showing its answer cannot change. It may close over mutable
            // state, so the frozen table is an assumption about provider semantics and is honoured
            // above the strict tier only. The tier is applied at dispatch, because registration runs
            // before PathWeaver has loaded its config.
            FabricLandPathRegistryLatch.certifiedProviderRegistered();
        } else {
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

    /**
     * Keep a worker out of the live provider map, without costing the server thread anything.
     *
     * <p>{@code getPathTypeProvider} is reached once per node from Fabric's node-type hook, in every
     * search on every thread. It used to carry a cancellable {@code @Inject} at HEAD, and a cancellable
     * inject allocates a {@code CallbackInfoReturnable} on every call, on every thread, including the
     * server thread's own synchronous searches that this hook has nothing to say about. Profiled in a
     * 317-mod client, that and the ThreadLocal read it made came to 10-30% of all synchronous search
     * time. Fabric's body is a single {@code PATH_TYPES.get(block)}, so the decision now sits on that
     * one call: a static redirect, no allocation, and a non-worker thread does exactly what Fabric does.
     */
    @Redirect(
        method = "getPathTypeProvider(Lnet/minecraft/world/level/block/Block;)Lnet/fabricmc/fabric/api/registry/LandPathTypeRegistry$PathTypeProvider;",
        at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
        require = 1,
        expect = 1)
    private static Object pathweaver$keepWorkerOutOfLiveProviderMap(Map<?, ?> liveProviders, Object block) {
        // isWorker(), not searchRunsOffThread(): provider lookups happen in getPathType during the
        // search itself, never in the prologue the main thread runs on a worker's behalf. The
        // prologue only builds a PathfindingContext, which resolves no path types. If that ever
        // changes, this needs the destination-based check for the same reason the cache isolation
        // did -- a main-thread lookup would reach the live provider map on a worker's behalf.
        if (!PathWeaverThread.isWorker()) return liveProviders.get(block);
        FabricLandPathRegistryLatch.recordWorkerProviderLookupBypass();
        // Serve the frozen answer for certified blocks. Returning null here would mean "no rule
        // exists", which is the wrong answer once a mod has registered one, and is exactly how a
        // mob would be routed over a block the mod marked dangerous.
        return block instanceof Block b && CertifiedLandProviders.isCertified(b)
            ? CertifiedLandProviders.frozenProvider()
            : null;
    }
}
