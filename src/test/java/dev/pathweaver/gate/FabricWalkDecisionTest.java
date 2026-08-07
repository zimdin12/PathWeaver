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

        // Expectations changed in 0.6, and the reason is the release.
        //
        // With NO audit evidence at all, this used to deny every family, because Fabric's
        // interaction module claims BlockStateBase and a claim on a watched class denied everything
        // regardless of what it injected. That module injects player block-interaction methods --
        // verified from its bytecode: the signatures carry Player, InteractionHand and
        // BlockHitResult and return InteractionResult -- which no search calls. It is now cleared,
        // and what remains is the content-registry module's Walk claim, which injects
        // getPathTypeFromState on WalkNodeEvaluator and genuinely is on the search's path.
        //
        // So an unaudited stock Fabric API goes from "nothing may dispatch" to "everything except
        // the land families may dispatch". That is the whole point: a version the audits do not
        // recognise no longer switches the mod off wholesale.
        assertEquals(Set.of(WalkNodeEvaluator.class),
            decide(content, interaction, ForeignMixinScanner.AuditedExemptionEvidence.unverified())
                .denied(),
            "unaudited stock Fabric API must deny only the claim that is really on the search path");
        assertTrue(decide(content, interaction, contentOnly).denied().isEmpty(),
            "auditing the content module clears the one remaining real claim");
        assertEquals(Set.of(WalkNodeEvaluator.class),
            decide(content, interaction, interactionOnly).denied(),
            "auditing only the interaction module leaves the content module's Walk claim denying");
        assertTrue(decide(content, interaction, both).denied().isEmpty());
    }

    /**
     * A version near-miss on the INTERACTION module no longer denies, and that is the point of 0.6.
     *
     * <p>Verified from the module's own bytecode before this test was changed: its
     * {@code BlockStateBase} mixin injects into player block-interaction methods — the signatures
     * carry {@code Player}, {@code InteractionHand} and {@code BlockHitResult} and return
     * {@code InteractionResult}. A pathfinding search never calls any of them.
     *
     * <p>Under the old policy the exact version and jar hash were pinned, so any other Fabric API
     * build denied every family and switched the mod off with no actionable error. That is what made
     * {@code AUDITED} leave 0 of 187 mob types eligible. The narrowing asks the question that
     * actually matters — is the injected method one a search can reach — against the bytes that are
     * really loaded, which is also why it does not care what version string the metadata claims.
     *
     * <p>The near-misses that still deny are asserted below and in
     * {@link #contentVersionPluginAndAddedSensitiveClaimNearMissesDeny}: the content-registry module
     * injects {@code WalkNodeEvaluator} and {@code PathfindingContext}, which a search does reach.
     */
    @Test void interactionNearMissesAreClearedBecauseTheirMethodsAreUnreachable() {
        var both = FabricSwimCompatibility.exactLandEvidence()
            .merge(FabricInteractionCompatibility.exactEvidence());
        var content = contentConfig("11.2.1+76b0b6bb4c", false, false);
        assertTrue(decide(content, interactionConfig("5.2.3+drift", false, false), both)
                .denied().isEmpty(),
            "a VERSION near-miss on a module whose injected methods no search reaches must no "
                + "longer deny -- the version string proves nothing about the code");

        // A mixin PLUGIN is different and must still deny. A plugin can rewrite a mixin in
        // preApply/postApply, so the class bytes the narrowing reads are not necessarily what gets
        // applied, and clearing on them would be reasoning about code that never runs. The
        // aggregate game-test harness caught this before it shipped.
        assertEquals(SafetyGate.allowlisted(),
            decide(content, interactionConfig("5.2.2+07b380be4c", true, false), both).denied(),
            "a config that has grown a mixin plugin must not be narrowed, and its BlockStateBase "
                + "claim is a shared target, so it denies every family");
    }

    /**
     * An UNKNOWN mixin class on the same target still denies.
     *
     * <p>The narrowing clears a claim only when it can read the mixin's bytes and resolve every
     * injected method. A class it cannot read — a mod that renamed or added one — resolves to
     * nothing and keeps denying, which is what stops this from being a blanket waiver.
     */
    @Test void anUnreadableMixinOnTheSameTargetStillDenies() {
        var both = FabricSwimCompatibility.exactLandEvidence()
            .merge(FabricInteractionCompatibility.exactEvidence());
        var content = contentConfig("11.2.1+76b0b6bb4c", false, false);
        var unknown = new ForeignMixinScanner.ActiveConfig("fabric-events-interaction-v0",
            "5.2.2+07b380be4c", "fabric-events-interaction-v0.mixins.json",
            Set.of(new ForeignMixinScanner.TargetClaim("foreign.ChangedSelector", BLOCK_STATE)), false);
        assertEquals(SafetyGate.allowlisted(), decide(content, unknown, both).denied(),
            "a mixin class whose bytes cannot be read must fail closed");
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
