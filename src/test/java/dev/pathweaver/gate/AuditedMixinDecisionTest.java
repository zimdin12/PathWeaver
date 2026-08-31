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
        var config = serverCoreConfig(AuditedMixinCompatibility.SERVERCORE_VERSION, "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true);
        assertEquals(SafetyGate.allowlisted(),
            decide(config, ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied());
        assertTrue(decide(config, AuditedMixinCompatibility.exactServerCoreEvidence()).denied().isEmpty());
    }

    @Test void exactRabbitClaimRequiresVerifiedRuntimeEvidence() {
        var config = rabbitConfig(AuditedMixinCompatibility.RABBIT_VERSION, "rabbit-pathfinding-fix.mixins.json",
            "net.litetex.rpf.mixin.EntityNavigationMixin", false);
        assertEquals(SafetyGate.allowlisted(),
            decide(config, ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied());
        assertTrue(decide(config, AuditedMixinCompatibility.exactRabbitEvidence()).denied().isEmpty());
    }

    @Test void versionConfigMixinTargetAndPluginNearMissesDenyBoth() {
        var serverEvidence = AuditedMixinCompatibility.exactServerCoreEvidence();
        var rabbitEvidence = AuditedMixinCompatibility.exactRabbitEvidence();
        for (var nearMiss : List.of(
            serverCoreConfig("1.5.20+future", "servercore.common.mixins.json",
                "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true),
            serverCoreConfig(AuditedMixinCompatibility.SERVERCORE_VERSION, "renamed.mixins.json",
                "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true),
            serverCoreConfig(AuditedMixinCompatibility.SERVERCORE_VERSION, "servercore.common.mixins.json",
                "foreign.ImpostorPathFinderMixin", true),
            serverCoreConfig(AuditedMixinCompatibility.SERVERCORE_VERSION, "servercore.common.mixins.json",
                "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", false),
            new ForeignMixinScanner.ActiveConfig("servercore", AuditedMixinCompatibility.SERVERCORE_VERSION,
                "servercore.common.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
                    PATH_FINDER)), new ForeignMixinScanner.PluginIdentity(
                        "foreign.WrongPlugin", AuditedMixinCompatibility.SERVERCORE_PLUGIN_SHA)),
            new ForeignMixinScanner.ActiveConfig("servercore-impostor", AuditedMixinCompatibility.SERVERCORE_VERSION,
                "servercore.common.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
                    PATH_FINDER)), true),
            new ForeignMixinScanner.ActiveConfig("servercore", AuditedMixinCompatibility.SERVERCORE_VERSION,
                "servercore.common.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin",
                    "net.minecraft.world.level.pathfinder.NodeEvaluator")), true))) {
            assertEquals(SafetyGate.allowlisted(),
                decide(nearMiss, serverEvidence).denied(), nearMiss.toString());
        }
        for (var nearMiss : List.of(
            rabbitConfig(AuditedMixinCompatibility.RABBIT_VERSION + "-unaudited",
                "rabbit-pathfinding-fix.mixins.json",
                "net.litetex.rpf.mixin.EntityNavigationMixin", false),
            rabbitConfig(AuditedMixinCompatibility.RABBIT_VERSION, "renamed.mixins.json",
                "net.litetex.rpf.mixin.EntityNavigationMixin", false),
            rabbitConfig(AuditedMixinCompatibility.RABBIT_VERSION, "rabbit-pathfinding-fix.mixins.json",
                "foreign.ImpostorNavigationMixin", false),
            rabbitConfig(AuditedMixinCompatibility.RABBIT_VERSION, "rabbit-pathfinding-fix.mixins.json",
                "net.litetex.rpf.mixin.EntityNavigationMixin", true),
            new ForeignMixinScanner.ActiveConfig("rabbit-impostor", AuditedMixinCompatibility.RABBIT_VERSION,
                "rabbit-pathfinding-fix.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "net.litetex.rpf.mixin.EntityNavigationMixin", PATH_NAVIGATION)), false),
            new ForeignMixinScanner.ActiveConfig("rabbit-pathfinding-fix", AuditedMixinCompatibility.RABBIT_VERSION,
                "rabbit-pathfinding-fix.mixins.json", Set.of(new ForeignMixinScanner.TargetClaim(
                    "net.litetex.rpf.mixin.EntityNavigationMixin", PATH_FINDER)), false))) {
            assertEquals(SafetyGate.allowlisted(),
                decide(nearMiss, rabbitEvidence).denied(), nearMiss.toString());
        }
    }

    @Test void verifiedClaimDoesNotExemptAnAddedSensitiveClaim() {
        var exact = serverCoreConfig(AuditedMixinCompatibility.SERVERCORE_VERSION, "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", true);
        var extra = new ForeignMixinScanner.ActiveConfig(exact.modId(), exact.version(), exact.configName(),
            Set.of(exact.claims().iterator().next(),
                new ForeignMixinScanner.TargetClaim("foreign.AddedMixin",
                    "net.minecraft.world.level.pathfinder.NodeEvaluator")), true);
        assertEquals(SafetyGate.allowlisted(),
            decide(extra, AuditedMixinCompatibility.exactServerCoreEvidence()).denied());
    }

    @Test void nominalAuditListIsExactAndDoesNotTrustOwnerPrefixes() {
        assertTrue(ForeignMixinScanner.isAuditedExemption("servercore", AuditedMixinCompatibility.SERVERCORE_VERSION,
            "servercore.common.mixins.json",
            "me.wesley1808.servercore.mixin.optimizations.misc.PathFinderMixin", PATH_FINDER));
        assertTrue(ForeignMixinScanner.isAuditedExemption("rabbit-pathfinding-fix", AuditedMixinCompatibility.RABBIT_VERSION,
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
