package dev.pathweaver.gate;

import com.google.gson.JsonParser;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ForeignMixinScannerTest {

    // ---- pure helper ----
    @Test void detectsMixinIntoAllowlistedClass() {
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            List.of("net.minecraft.world.level.pathfinder.WalkNodeEvaluator"));
        assertTrue(hit.contains(WalkNodeEvaluator.class));
    }

    @Test void ignoresUnrelatedTargets() {
        Set<Class<?>> hit = ForeignMixinScanner.targetsTouchingAllowlist(
            List.of("net.minecraft.world.entity.Mob"));
        assertTrue(hit.isEmpty());
    }

    @Test void sharedPathfindingTargetsDenyEveryEligibleEvaluator() {
        for (String target : List.of(
            "net.minecraft.world.level.pathfinder.NodeEvaluator",
            "net.minecraft.world.level.pathfinder.PathfindingContext",
            "net.minecraft.world.entity.ai.navigation.PathNavigation",
            "net.minecraft.world.entity.ai.navigation.GroundPathNavigation",
            "net.minecraft.world.level.pathfinder.PathFinder"
        )) {
            assertEquals(SafetyGate.allowlisted(),
                ForeignMixinScanner.denialsForTargets(List.of(target)), target);
        }
    }

    @Test void internalSlashTargetNamesCannotBypassSensitiveTargetDenials() {
        assertEquals(Set.of(WalkNodeEvaluator.class),
            ForeignMixinScanner.denialsForTargets(List.of(
                "net/minecraft/world/level/pathfinder/WalkNodeEvaluator")));
        assertEquals(SafetyGate.allowlisted(),
            ForeignMixinScanner.denialsForTargets(List.of(
                "net/minecraft/world/level/pathfinder/PathFinder")));
    }

    @Test void preparedReflectionPathNormalizesInternalTargetNames() throws Exception {
        assertEquals(Set.of(new ForeignMixinScanner.TargetClaim(
                "foreign.SlashMixin", "net.minecraft.world.level.pathfinder.WalkNodeEvaluator")),
            ForeignMixinScanner.preparedClaims(new SlashTargetConfig()));
    }

    @Test void anyDiscoveryFailureFailsClosedForEveryEligibleEvaluator() {
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(
            List.of(), List.of("broken nested jar: unreadable"));
        assertEquals(SafetyGate.allowlisted(), decision.denied());
        assertEquals(0, decision.scanned());
        assertEquals(1, decision.failed());
    }

    @Test void activePluginTargetsAreEvaluatedLikeStaticMixins() {
        ForeignMixinScanner.ActiveConfig pluginConfig = new ForeignMixinScanner.ActiveConfig(
            "foreign", "1.0", "foreign.mixins.json",
            Set.of(new ForeignMixinScanner.TargetClaim("foreign.PluginMixin",
                "net.minecraft.world.level.pathfinder.PathFinder")), true);
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(
            List.of(pluginConfig), List.of());
        assertEquals(SafetyGate.allowlisted(), decision.denied());
        assertEquals(1, decision.scanned());
        assertEquals(0, decision.failed());
    }

    @Test void fabricMetadataDiscoveryHonorsServerEnvironmentAndObjectForm() {
        var metadata = JsonParser.parseString("""
            {"mixins":[
              "common.mixins.json",
              {"config":"server.mixins.json","environment":"server"},
              {"config":"client.mixins.json","environment":"client"}
            ]}
            """).getAsJsonObject();
        assertEquals(List.of("common.mixins.json", "server.mixins.json"),
            ForeignMixinScanner.readServerMixinConfigNames(metadata));
    }

    @Test void fabricMetadataDiscoveryHonorsClientEnvironmentAndObjectForm() {
        var metadata = JsonParser.parseString("""
            {"mixins":[
              "common.mixins.json",
              {"config":"server.mixins.json","environment":"server"},
              {"config":"client.mixins.json","environment":"client"}
            ]}
            """).getAsJsonObject();
        assertEquals(List.of("common.mixins.json", "client.mixins.json"),
            ForeignMixinScanner.readClientMixinConfigNames(metadata));
    }

    @Test void malformedFabricMixinMetadataFailsInsteadOfDisappearing() {
        var metadata = JsonParser.parseString("{\"mixins\":[42]}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class,
            () -> ForeignMixinScanner.readServerMixinConfigNames(metadata));
    }

    @Test void undeclaredConfigThatTransformsNothingDoesNotVetoAsync() {
        // c2me.mixins.json is active, listed in no fabric.mod.json, declares an empty mixin list
        // and carries no plugin. It transforms nothing, so it must not disable the feature.
        assertNull(ForeignMixinScanner.unattributableConfigFailure(
                "c2me.mixins.json", Set.of(), false),
            "an undeclared config that transforms nothing must not be treated as a scan failure");
    }

    @Test void undeclaredConfigCarryingAPluginAlwaysFailsClosed() {
        // A config plugin can rewrite classes owned by OTHER configs, so an empty claim set
        // proves nothing about it.
        assertNotNull(ForeignMixinScanner.unattributableConfigFailure(
                "mixinsquared.mixins.json", Set.of(), true),
            "an undeclared config with a plugin must fail closed even with no claims");
    }

    @Test void undeclaredConfigClaimingAnythingAtAllFailsClosed() {
        // Deliberately NOT judged against the sensitive-target list: that list is best-effort and
        // known to be incomplete, so an unauditable config claiming any target must deny.
        assertNotNull(ForeignMixinScanner.unattributableConfigFailure("mystery.mixins.json",
                Set.of(new ForeignMixinScanner.TargetClaim(
                    "com.example.mixin.ChestBlockEntityMixin",
                    "net.minecraft.world.level.block.entity.ChestBlockEntity")), false),
            "an unauditable config claiming any target must fail closed");
    }

    @Test void undeclaredConfigTouchingWorkerReadClassesFailsClosed() {
        // PathNavigationRegion and BlockStateBase are read on the worker thread but are not in
        // SHARED_PATHFINDING_TARGETS. They must still deny via the claims-anything rule.
        for (String target : List.of(
                "net.minecraft.world.level.PathNavigationRegion",
                "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
                "net.minecraft.world.level.pathfinder.PathTypeCache")) {
            assertNotNull(ForeignMixinScanner.unattributableConfigFailure("mystery.mixins.json",
                    Set.of(new ForeignMixinScanner.TargetClaim("com.example.mixin.M", target)),
                    false),
                "unauditable config touching worker-read class must deny: " + target);
        }
    }

    @Test void undeclaredConfigTouchingPathfindingStillFailsClosed() {
        String failure = ForeignMixinScanner.unattributableConfigFailure("mystery.mixins.json",
            Set.of(new ForeignMixinScanner.TargetClaim(
                "com.example.mixin.WalkMixin",
                "net.minecraft.world.level.pathfinder.WalkNodeEvaluator")), false);
        assertNotNull(failure, "unauditable AND relevant must still fail closed");
        assertTrue(failure.contains("mystery.mixins.json"), "failure must name the config");
    }

    @Test void exactFabricContextTupleWithVerifiedShapeExemptsSwimOnly() {
        ForeignMixinScanner.ActiveConfig config = exactFabricPathfindingConfig();
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(
            List.of(config), List.of(),
            new ForeignMixinScanner.SwimExemptionEvidence(true, List.of()));
        assertEquals(Set.of(WalkNodeEvaluator.class), decision.denied(),
            "the independent Walk mixin claim must continue denying Walk");
    }

    @Test void exactTupleWithoutRuntimeFingerprintStillDeniesBoth() {
        ForeignMixinScanner.ScanDecision decision = ForeignMixinScanner.decide(
            List.of(exactFabricPathfindingConfig()), List.of(),
            new ForeignMixinScanner.SwimExemptionEvidence(false, List.of("hash mismatch")));
        assertEquals(SafetyGate.allowlisted(), decision.denied());
    }

    @Test void versionDriftOrPluginContributionCannotUseSwimExemption() {
        ForeignMixinScanner.ActiveConfig exact = exactFabricPathfindingConfig();
        for (ForeignMixinScanner.ActiveConfig drifted : List.of(
            new ForeignMixinScanner.ActiveConfig(exact.modId(), "11.2.2+future", exact.configName(),
                exact.claims(), false),
            new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(), exact.configName(),
                exact.claims(), true)
        )) {
            assertEquals(SafetyGate.allowlisted(),
                ForeignMixinScanner.decide(List.of(drifted), List.of(),
                    new ForeignMixinScanner.SwimExemptionEvidence(true, List.of())).denied());
        }
    }

    @Test void missingWalkClaimOrAddedSharedClaimFailsClosed() {
        ForeignMixinScanner.ActiveConfig exact = exactFabricPathfindingConfig();
        ForeignMixinScanner.TargetClaim contextClaim = exact.claims().stream()
            .filter(c -> c.target().endsWith("PathfindingContext")).findFirst().orElseThrow();
        ForeignMixinScanner.ActiveConfig missingWalk = new ForeignMixinScanner.ActiveConfig(
            exact.modId(), exact.version(), exact.configName(), Set.of(contextClaim), false);
        ForeignMixinScanner.ActiveConfig extraShared = new ForeignMixinScanner.ActiveConfig(
            exact.modId(), exact.version(), exact.configName(), Set.of(
                contextClaim,
                exact.claims().stream().filter(c -> c.target().endsWith("WalkNodeEvaluator"))
                    .findFirst().orElseThrow(),
                new ForeignMixinScanner.TargetClaim("foreign.AddedMixin",
                    "net.minecraft.world.level.pathfinder.NodeEvaluator")), false);
        for (ForeignMixinScanner.ActiveConfig malformed : List.of(missingWalk, extraShared)) {
            assertEquals(SafetyGate.allowlisted(),
                ForeignMixinScanner.decide(List.of(malformed), List.of(),
                    new ForeignMixinScanner.SwimExemptionEvidence(true, List.of())).denied());
        }
    }

    @Test void configMixinAndTargetNearMissesDenyBoth() {
        ForeignMixinScanner.ActiveConfig exact = exactFabricPathfindingConfig();
        ForeignMixinScanner.TargetClaim context = exact.claims().stream()
            .filter(c -> c.target().endsWith("PathfindingContext")).findFirst().orElseThrow();
        ForeignMixinScanner.TargetClaim walk = exact.claims().stream()
            .filter(c -> c.target().endsWith("WalkNodeEvaluator")).findFirst().orElseThrow();
        List<ForeignMixinScanner.ActiveConfig> nearMisses = List.of(
            new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(),
                "renamed.mixins.json", exact.claims(), false),
            new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(), exact.configName(),
                Set.of(new ForeignMixinScanner.TargetClaim("foreign.ImpostorContextMixin",
                    context.target()), walk), false),
            new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(), exact.configName(),
                Set.of(new ForeignMixinScanner.TargetClaim(context.mixinClass(),
                    "net.minecraft.world.level.pathfinder.NodeEvaluator"), walk), false)
        );
        for (ForeignMixinScanner.ActiveConfig nearMiss : nearMisses) {
            assertEquals(SafetyGate.allowlisted(),
                ForeignMixinScanner.decide(List.of(nearMiss), List.of(),
                    new ForeignMixinScanner.SwimExemptionEvidence(true, List.of())).denied());
        }
    }

    @Test void scannerSelfExclusionIsExplicitAndCannotBecomeAnAuditedForeignTuple() {
        assertFalse(ForeignMixinScanner.isForeignModId("pathweaver"));
        assertTrue(ForeignMixinScanner.isForeignModId("fabric-content-registries-v0"));
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "pathweaver", "0.2.3+26.1.2", "pathweaver.mixins.json",
            "dev.pathweaver.mixin.PathfindingContextMixin",
            "net.minecraft.world.level.pathfinder.PathfindingContext"));
    }

    private static ForeignMixinScanner.ActiveConfig exactFabricPathfindingConfig() {
        return new ForeignMixinScanner.ActiveConfig(
            "fabric-content-registries-v0", "11.2.1+76b0b6bb4c",
            "fabric-content-registries-v0.mixins.json", Set.of(
                new ForeignMixinScanner.TargetClaim(
                    "net.fabricmc.fabric.mixin.content.registry.PathfindingContextMixin",
                    "net.minecraft.world.level.pathfinder.PathfindingContext"),
                new ForeignMixinScanner.TargetClaim(
                    "net.fabricmc.fabric.mixin.content.registry.WalkNodeEvaluatorMixin",
                    "net.minecraft.world.level.pathfinder.WalkNodeEvaluator"),
                new ForeignMixinScanner.TargetClaim(
                    "net.fabricmc.fabric.mixin.content.registry.BlockBehaviourBlockStateBaseMixin",
                    "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")), false);
    }

    @Test void oldBroadTrustRulesAreGone() {
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "fabric-anything", "1", "x.mixins.json", "x.Mixin",
            "net.minecraft.world.level.pathfinder.PathFinder"));
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "diagonal-anything", "1", "x.mixins.json", "x.Mixin",
            "net.minecraft.world.level.pathfinder.PathFinder"));
        assertFalse(ForeignMixinScanner.isAuditedExemption(
            "lithium", "unknown", "x.mixins.json", "x.Mixin",
            "net.minecraft.world.level.pathfinder.PathFinder"));
    }

    private static final class SlashTargetConfig implements IMixinConfig {
        private static final String RAW_TARGET =
            "net/minecraft/world/level/pathfinder/WalkNodeEvaluator";

        public Collection<IMixinInfo> getMixinsFor(String target) {
            assertEquals(RAW_TARGET, target, "reflection must query Mixin with its original target form");
            IMixinInfo info = (IMixinInfo) Proxy.newProxyInstance(
                IMixinInfo.class.getClassLoader(), new Class<?>[]{IMixinInfo.class},
                (proxy, method, args) -> method.getName().equals("getClassName")
                    ? "foreign.SlashMixin" : defaultValue(method.getReturnType()));
            return List.of(info);
        }

        @Override public MixinEnvironment getEnvironment() { return null; }
        @Override public String getName() { return "slash.mixins.json"; }
        @Override public IMixinConfigSource getSource() { return null; }
        @Override public String getCleanSourceId() { return "slash"; }
        @Override public String getMixinPackage() { return "foreign"; }
        @Override public int getPriority() { return DEFAULT_PRIORITY; }
        @Override public IMixinConfigPlugin getPlugin() { return null; }
        @Override public boolean isRequired() { return true; }
        @Override public Set<String> getTargets() { return Set.of(RAW_TARGET); }
        @Override public <V> void decorate(String key, V value) { }
        @Override public boolean hasDecoration(String key) { return false; }
        @Override public <V> V getDecoration(String key) { return null; }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0;
        if (type == char.class) return '\0';
        return null;
    }
}
