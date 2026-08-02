package dev.pathweaver.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Trusting one mod must not quietly trust the others.
 *
 * <p>The tier is all-or-nothing: the unsafe tier waives every denial, permanently, including for
 * mods installed later. On a heavily-modded pack that is the only way to get any benefit, which
 * turns a decision about nine known mods into a decision about every mod that will ever touch
 * pathfinding. The trust list is the scoped version, and the whole value of it is that the scan
 * keeps working for everything not named — so that is what is pinned here.
 */
class TrustedModScopeTest {

    private static final String SENSITIVE = "net.minecraft.world.level.pathfinder.PathFinder";

    private static ForeignMixinScanner.ActiveConfig config(String modId) {
        return new ForeignMixinScanner.ActiveConfig(modId, "1.0.0", modId + ".mixins.json",
            Set.of(new ForeignMixinScanner.TargetClaim(modId + ".SomeMixin", SENSITIVE)), false);
    }

    private static ForeignMixinScanner.ScanDecision decide(Set<String> trusted,
                                                           ForeignMixinScanner.ActiveConfig... configs) {
        return ForeignMixinScanner.decide(List.of(configs), List.of(),
            ForeignMixinScanner.SwimExemptionEvidence.unverified("not supplied"),
            ForeignMixinScanner.AuditedExemptionEvidence.unverified(), trusted);
    }

    @Test
    void anUntrustedModStillDeniesEverything() {
        var decision = decide(Set.of(), config("ferritecore"));
        assertEquals(SafetyGate.allowlisted(), decision.denied(),
            "an unaudited mod touching PathFinder must still deny every family");
    }

    @Test
    void trustingTheOnlyOffenderClearsTheDenial() {
        var decision = decide(Set.of("ferritecore"), config("ferritecore"));
        assertTrue(decision.denied().isEmpty(),
            "the operator accepted this mod's risk explicitly: " + decision.denied());
    }

    @Test
    void trustingOneOffenderDoesNotClearAnother() {
        // The entire point. If this ever passes vacuously, the trust list has become a second
        // spelling of the unsafe tier and the operator's scoped decision has been widened for them.
        var decision = decide(Set.of("ferritecore"), config("ferritecore"), config("somethingelse"));
        assertFalse(decision.denied().isEmpty(),
            "a mod the operator never named must still deny");
        assertEquals(SafetyGate.allowlisted(), decision.denied());
    }

    @Test
    void aModInstalledLaterIsStillCheckedAgainstAnExistingTrustList() {
        // Same assertion from the direction that actually happens: the list was written months ago
        // and a new mod arrives. It must not inherit the trust.
        Set<String> writtenLongAgo = Set.of("ferritecore", "modernfix", "moreculling");
        var decision = decide(writtenLongAgo, config("ferritecore"), config("brand-new-mod"));
        assertFalse(decision.denied().isEmpty(),
            "a mod that did not exist when the list was written must still be scanned");
    }

    @Test
    void trustingSomethingHarmlessChangesNothing() {
        var decision = decide(Set.of("a-mod-that-touches-no-pathfinding"), config("ferritecore"));
        assertEquals(SafetyGate.allowlisted(), decision.denied(),
            "naming an irrelevant mod must not clear a real denial");
    }

    @Test
    void anEmptyTrustListBehavesExactlyAsBefore() {
        var withEmpty = decide(Set.of(), config("ferritecore"));
        var withoutArgument = ForeignMixinScanner.decide(List.of(config("ferritecore")), List.of(),
            ForeignMixinScanner.SwimExemptionEvidence.unverified("not supplied"),
            ForeignMixinScanner.AuditedExemptionEvidence.unverified());
        assertEquals(withoutArgument.denied(), withEmpty.denied(),
            "the added parameter must not change the decision when nothing is trusted");
    }
}
