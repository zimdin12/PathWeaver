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
            assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
                ForeignMixinScanner.denialsForTargets(List.of(target)), target);
        }
    }

    @Test void internalSlashTargetNamesCannotBypassSensitiveTargetDenials() {
        assertEquals(Set.of(WalkNodeEvaluator.class),
            ForeignMixinScanner.denialsForTargets(List.of(
                "net/minecraft/world/level/pathfinder/WalkNodeEvaluator")));
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
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
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class), decision.denied());
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
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class), decision.denied());
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
