package dev.pathweaver.gate;

import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code canDispatch} is a RECONSTRUCTION of what dispatch does, and nothing pinned it to the original.
 *
 * <p>The mixin does not call {@code canDispatch}. It performs three separate steps — {@code isAllowed},
 * then {@code requiresEmptyLandRegistry}, then the latch — and {@code canDispatch} exists so the banner
 * and {@code /pathweaver status} can answer the same question. A reviewer reduced the production
 * {@code canDispatch} to {@code return isAllowed(c);} and all 289 tests stayed green, which would have
 * restored the release's headline bug: "ACTIVE: all 6 movement families" while five of six are refused
 * every tick.
 *
 * <p>The reason the old test missed it is that it computed its expectation by calling the very method
 * under test. This one recomputes the dispatch sequence independently and asserts the reconstruction
 * matches it, over the whole input space.
 */
class SafetyGateDispatchParityTest {

    /** What {@code PathNavigationMixin} actually evaluates, spelled out rather than delegated. */
    private static boolean whatDispatchDoes(Class<?> evaluator, boolean allowed,
                                            boolean bypassesScan, boolean latchAllows) {
        if (!allowed) return false;
        boolean gatedOnRegistry = SafetyGate.requiresEmptyLandRegistry(evaluator, bypassesScan);
        return !gatedOnRegistry || latchAllows;
    }

    @Test
    void theReportedPredicateMatchesTheDispatchSequenceOverTheWholeInputSpace() throws Exception {
        Class<?>[] families = {
            WalkNodeEvaluator.class,
            SwimNodeEvaluator.class,
            net.minecraft.world.level.pathfinder.FlyNodeEvaluator.class,
            net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator.class,
            Class.forName("net.minecraft.world.entity.animal.frog.Frog$FrogNodeEvaluator"),
            Class.forName("net.minecraft.world.entity.monster.creaking.Creaking$HomeNodeEvaluator"),
        };
        for (Class<?> family : families) {
            for (boolean allowed : new boolean[] {false, true}) {
                for (boolean bypass : new boolean[] {false, true}) {
                    for (boolean latch : new boolean[] {false, true}) {
                        assertEquals(
                            whatDispatchDoes(family, allowed, bypass, latch),
                            SafetyGate.canDispatch(family, allowed, bypass, latch),
                            () -> family.getSimpleName() + " allowed=" + allowed + " bypass=" + bypass
                                + " latch=" + latch + ": what the banner reports must equal what "
                                + "dispatch does, or one of them is lying to the operator");
                    }
                }
            }
        }
    }

    /**
     * The reconstruction must not collapse to its first term.
     *
     * <p>Stated separately because that is the exact mutation that survived: {@code canDispatch}
     * reduced to {@code isAllowed} passes any test whose expectation is also computed from
     * {@code canDispatch}.
     */
    @Test
    void anAllowedLandFamilyIsStillRefusedWhileTheRegistryIsUnverified() {
        assertEquals(false, SafetyGate.canDispatch(WalkNodeEvaluator.class, true, false, false),
            "allowed is not sufficient: the land registry must still be able to refuse");
        assertEquals(true, SafetyGate.canDispatch(SwimNodeEvaluator.class, true, false, false),
            "and it must not refuse a family that never reads that registry");
    }
}
