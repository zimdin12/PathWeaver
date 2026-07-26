package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FabricWalkDecisionTest {
    private static final String BLOCK_STATE =
        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";
    private static final ForeignMixinScanner.SwimExemptionEvidence SWIM_EXACT =
        new ForeignMixinScanner.SwimExemptionEvidence(true, List.of());

    @Test void exactContentAndInteractionClaimsRequireBothVerifiedBundles() {
        var content = contentConfig("11.2.1+76b0b6bb4c", false, false);
        var interaction = interactionConfig("5.2.2+07b380be4c", false, false);
        var contentOnly = FabricSwimCompatibility.exactLandEvidence();
        var interactionOnly = FabricInteractionCompatibility.exactEvidence();
        var both = contentOnly.merge(interactionOnly);

        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
            decide(content, interaction, ForeignMixinScanner.AuditedExemptionEvidence.unverified()).denied());
        assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
            decide(content, interaction, contentOnly).denied());
        assertEquals(Set.of(WalkNodeEvaluator.class),
            decide(content, interaction, interactionOnly).denied());
        assertTrue(decide(content, interaction, both).denied().isEmpty());
    }

    @Test void interactionVersionPluginClassAndExtraClaimNearMissesDenyBoth() {
        var both = FabricSwimCompatibility.exactLandEvidence()
            .merge(FabricInteractionCompatibility.exactEvidence());
        var content = contentConfig("11.2.1+76b0b6bb4c", false, false);
        for (var nearMiss : List.of(
                interactionConfig("5.2.3+drift", false, false),
                interactionConfig("5.2.2+07b380be4c", true, false),
                interactionConfig("5.2.2+07b380be4c", false, true),
                new ForeignMixinScanner.ActiveConfig("fabric-events-interaction-v0",
                    "5.2.2+07b380be4c", "fabric-events-interaction-v0.mixins.json",
                    Set.of(new ForeignMixinScanner.TargetClaim("foreign.ChangedSelector", BLOCK_STATE)), false))) {
            assertEquals(Set.of(WalkNodeEvaluator.class, SwimNodeEvaluator.class),
                decide(content, nearMiss, both).denied(), nearMiss.toString());
        }
    }

    @Test void contentVersionPluginAndAddedSensitiveClaimNearMissesDeny() {
        var both = FabricSwimCompatibility.exactLandEvidence()
            .merge(FabricInteractionCompatibility.exactEvidence());
        var interaction = interactionConfig("5.2.2+07b380be4c", false, false);
        for (var nearMiss : List.of(
                contentConfig("11.2.2+drift", false, false),
                contentConfig("11.2.1+76b0b6bb4c", true, false),
                contentConfig("11.2.1+76b0b6bb4c", false, true))) {
            assertFalse(decide(nearMiss, interaction, both).denied().isEmpty(), nearMiss.toString());
        }
    }

    private static ForeignMixinScanner.ScanDecision decide(
            ForeignMixinScanner.ActiveConfig content,
            ForeignMixinScanner.ActiveConfig interaction,
            ForeignMixinScanner.AuditedExemptionEvidence evidence) {
        return ForeignMixinScanner.decide(List.of(content, interaction), List.of(), SWIM_EXACT, evidence);
    }

    private static ForeignMixinScanner.ActiveConfig contentConfig(
            String version, boolean plugin, boolean extraClaim) {
        Set<ForeignMixinScanner.TargetClaim> claims = new java.util.HashSet<>(Set.of(
            new ForeignMixinScanner.TargetClaim(FabricSwimCompatibility.CONTEXT_MIXIN,
                "net.minecraft.world.level.pathfinder.PathfindingContext"),
            new ForeignMixinScanner.TargetClaim(FabricSwimCompatibility.WALK_MIXIN,
                "net.minecraft.world.level.pathfinder.WalkNodeEvaluator"),
            new ForeignMixinScanner.TargetClaim(FabricSwimCompatibility.BLOCK_STATE_BASE_MIXIN,
                BLOCK_STATE)));
        if (extraClaim) claims.add(new ForeignMixinScanner.TargetClaim("foreign.Added",
            "net.minecraft.world.level.pathfinder.NodeEvaluator"));
        return new ForeignMixinScanner.ActiveConfig(FabricSwimCompatibility.MOD_ID, version,
            FabricSwimCompatibility.CONFIG, claims, plugin);
    }

    private static ForeignMixinScanner.ActiveConfig interactionConfig(
            String version, boolean plugin, boolean extraClaim) {
        Set<ForeignMixinScanner.TargetClaim> claims = new java.util.HashSet<>(Set.of(
            new ForeignMixinScanner.TargetClaim(FabricInteractionCompatibility.MIXIN, BLOCK_STATE)));
        if (extraClaim) claims.add(new ForeignMixinScanner.TargetClaim("foreign.Added", BLOCK_STATE));
        return new ForeignMixinScanner.ActiveConfig(FabricInteractionCompatibility.MOD_ID, version,
            FabricInteractionCompatibility.CONFIG, claims, plugin);
    }
}
