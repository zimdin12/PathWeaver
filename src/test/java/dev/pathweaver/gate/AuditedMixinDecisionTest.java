package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuditedMixinDecisionTest {
    private static final String PATH_FINDER =
        "net.minecraft.world.level.pathfinder.PathFinder";
    private static final String PATH_NAVIGATION =
        "net.minecraft.world.entity.ai.navigation.PathNavigation";
    private static final ForeignMixinScanner.SwimExemptionEvidence NO_SWIM =
        new ForeignMixinScanner.SwimExemptionEvidence(false, List.of());

    @Test void exactServerCoreClaimRequiresVerifiedRuntimeEvidence() {
        var config = serverCoreConfig("1.5.19+26.1.2", "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true);
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
            decide(config, ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied());
        assertTrue(decide(config, AuditedMixinCompatibility.exactServerCoreEvidence()).denied().isEmpty());
    }

    @Test void exactRabbitClaimRequiresVerifiedRuntimeEvidence() {
        var config = rabbitConfig("1.3.0", "rabbit-pathfinding-fix.mixins.json",
            "net.litetex.rpf.mixin.EntityNavigationMixin", false);
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
            decide(config, ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied());
        assertTrue(decide(config, AuditedMixinCompatibility.exactRabbitEvidence()).denied().isEmpty());
    }

    @Test void versionConfigMixinTargetAndPluginNearMissesDenyBoth() {
        var serverEvidence = AuditedMixinCompatibility.exactServerCoreEvidence();
        var rabbitEvidence = AuditedMixinCompatibility.exactRabbitEvidence();
        for (var nearMiss : List.of(
            serverCoreConfig("1.5.20+future", "servercore.common.mixins.json",
                "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true),
            serverCoreConfig("1.5.19+26.1.2", "renamed.mixins.json",
                "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true),
            serverCoreConfig("1.5.19+26.1.2", "servercore.common.mixins.json",
                "foreign.ImpostorPathFinderMixin", true),
            serverCoreConfig("1.5.19+26.1.2", "servercore.common.mixins.json",
                "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", false),
            new ForeignMixinScanner.ActiveConfig("servercore", "1.5.19+26.1.2",
                "servercore.common.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
                    PATH_FINDER)), new ForeignMixinScanner.PluginIdentity(
                        "foreign.WrongPlugin", AuditedMixinCompatibility.SERVERCORE_PLUGIN_SHA)),
            new ForeignMixinScanner.ActiveConfig("servercore-impostor", "1.5.19+26.1.2",
                "servercore.common.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
                    PATH_FINDER)), true),
            new ForeignMixinScanner.ActiveConfig("servercore", "1.5.19+26.1.2",
                "servercore.common.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
                    "net.minecraft.world.level.pathfinder.NodeEvaluator")), true))) {
            assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
                decide(nearMiss, serverEvidence).denied(), nearMiss.toString());
        }
        for (var nearMiss : List.of(
            rabbitConfig("1.4.0", "rabbit-pathfinding-fix.mixins.json",
                "net.litetex.rpf.mixin.EntityNavigationMixin", false),
            rabbitConfig("1.3.0", "renamed.mixins.json",
                "net.litetex.rpf.mixin.EntityNavigationMixin", false),
            rabbitConfig("1.3.0", "rabbit-pathfinding-fix.mixins.json",
                "foreign.ImpostorNavigationMixin", false),
            rabbitConfig("1.3.0", "rabbit-pathfinding-fix.mixins.json",
                "net.litetex.rpf.mixin.EntityNavigationMixin", true),
            new ForeignMixinScanner.ActiveConfig("rabbit-impostor", "1.3.0",
                "rabbit-pathfinding-fix.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "net.litetex.rpf.mixin.EntityNavigationMixin", PATH_NAVIGATION)), false),
            new ForeignMixinScanner.ActiveConfig("rabbit-pathfinding-fix", "1.3.0",
                "rabbit-pathfinding-fix.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "net.litetex.rpf.mixin.EntityNavigationMixin", PATH_FINDER)), false))) {
            assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
                decide(nearMiss, rabbitEvidence).denied(), nearMiss.toString());
        }
    }

    @Test void verifiedClaimDoesNotExemptAnAddedSensitiveClaim() {
        var exact = serverCoreConfig("1.5.19+26.1.2", "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true);
        var extra = new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(), exact.configName(),
            Set.of(exact.claims().iterator().next(),
                new ForeignMixinScanner.TargetClaim("foreign.AddedMixin",
                    "net.minecraft.world.level.pathfinder.NodeEvaluator")), true);
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
            decide(extra, AuditedMixinCompatibility.exactServerCoreEvidence()).denied());
    }

    @Test void nominalAuditListIsExactAndDoesNotTrustOwnerPrefixes() {
        assertTrue(ForeignMixinScanner.isAuditedExemption("servercore", "1.5.19+26.1.2",
            "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", PATH_FINDER));
        assertTrue(ForeignMixinScanner.isAuditedExemption("rabbit-pathfinding-fix", "1.3.0",
            "rabbit-pathfinding-fix.mixins.json", "net.litetex.rpf.mixin.EntityNavigationMixin",
            PATH_NAVIGATION));
        assertFalse(ForeignMixinScanner.isAuditedExemption("servercore", "future",
            "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", PATH_FINDER));
    }

    private static ForeignMixinScanner.ScanDecision decide(
            ForeignMixinScanner.ActiveConfig config,
            ForeignMixinScanner.AuditedExemptionEvidence evidence) {
        return ForeignMixinScanner.decide(List.of(config), List.of(), NO_SWIM, evidence);
    }

    private static ForeignMixinScanner.ActiveConfig serverCoreConfig(
            String version, String config, String mixin, boolean plugin) {
        return new ForeignMixinScanner.ActiveConfig("servercore", version, config,
            Set.of(new ForeignMixinScanner.TargetClaim(mixin, PATH_FINDER)), plugin
                ? new ForeignMixinScanner.PluginIdentity(AuditedMixinCompatibility.SERVERCORE_PLUGIN,
                    AuditedMixinCompatibility.SERVERCORE_PLUGIN_SHA)
                : null);
    }

    private static ForeignMixinScanner.ActiveConfig rabbitConfig(
            String version, String config, String mixin, boolean plugin) {
        return new ForeignMixinScanner.ActiveConfig("rabbit-pathfinding-fix", version, config,
            Set.of(new ForeignMixinScanner.TargetClaim(mixin, PATH_NAVIGATION)), plugin);
    }
}
