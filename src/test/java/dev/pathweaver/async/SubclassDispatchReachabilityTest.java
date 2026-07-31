package dev.pathweaver.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pathweaver.gate.SafetyGate;
import java.lang.reflect.Field;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * Keeps the admission gate and the rebuilder answering the same question.
 *
 * <p>They did not. The gate was widened to admit third-party evaluator subclasses at the unsafe tier
 * while the rebuilder still named two classes, so every admitted subclass built a region, cloned
 * nothing, counted a dispatch and unwound to a synchronous search — forever, silently, because the
 * fallback is correct. Two gates disagreeing is invisible precisely when the second one fails safe.
 *
 * <p>So the invariant is pinned directly: whatever the allowlist admits, the rebuilder can build.
 */
class SubclassDispatchReachabilityTest {

    private static final class ThirdPartyWalk extends WalkNodeEvaluator {}

    private static final class ThirdPartySwim extends SwimNodeEvaluator {
        ThirdPartySwim() {
            super(false);
        }
    }

    /** No no-arg constructor, and its argument matches no field: honestly unbuildable. */
    private static final class Unbuildable extends WalkNodeEvaluator {
        Unbuildable(String unmatched) {
            super();
            if (unmatched == null) throw new IllegalArgumentException();
        }
    }

    @Test
    void everyAllowlistedEvaluatorCanBeRebuilt() {
        assertFalse(SafetyGate.allowlisted().isEmpty(), "allowlist resolved to nothing");
        for (Class<?> allowed : SafetyGate.allowlisted()) {
            assertTrue(EvaluatorCloner.canClone(allowed),
                allowed.getName() + " is admitted by the gate but cannot be rebuilt");
        }
    }

    @Test
    void allSixVanillaEvaluatorsAreAllowlisted() {
        // Frog's and the creaking's are package-private, so they are named rather than referenced.
        assertEquals(6, SafetyGate.allowlisted().size(),
            "expected every concrete 26.1.2 evaluator: " + SafetyGate.allowlisted());
        assertTrue(SafetyGate.allowlisted().containsAll(java.util.Set.of(
            WalkNodeEvaluator.class, SwimNodeEvaluator.class,
            FlyNodeEvaluator.class, AmphibiousNodeEvaluator.class)));
    }

    @Test
    void thirdPartySubclassesAreRebuildableWhenTheirShapeIsUnderstood() {
        assertTrue(EvaluatorCloner.canClone(ThirdPartyWalk.class));
        assertTrue(EvaluatorCloner.canClone(ThirdPartySwim.class));
    }

    @Test
    void anUnrecognisedConstructorShapeIsRefusedRatherThanGuessed() {
        assertFalse(EvaluatorCloner.canClone(Unbuildable.class),
            "a guessed constructor argument would silently misconfigure the search");
    }

    @Test
    void rebuildingPreservesTheConstructorArgumentAndTheTraversalFlags() throws Exception {
        SwimNodeEvaluator breaching = new SwimNodeEvaluator(true);
        breaching.setCanPassDoors(true);
        breaching.setCanOpenDoors(true);
        breaching.setCanFloat(true);
        breaching.setCanWalkOverFences(true);

        NodeEvaluator clone = EvaluatorCloner.cloneWithConfig(breaching);

        assertNotSame(breaching, clone);
        assertEquals(SwimNodeEvaluator.class, clone.getClass());
        assertTrue(readBoolean(clone, "allowBreaching"), "constructor argument must survive the rebuild");
        assertTrue(clone.canPassDoors());
        assertTrue(clone.canOpenDoors());
        assertTrue(clone.canFloat());
        assertTrue(clone.canWalkOverFences());
    }

    @Test
    void amphibiousRebuildPreservesItsShallowSwimmingChoice() throws Exception {
        NodeEvaluator clone = EvaluatorCloner.cloneWithConfig(new AmphibiousNodeEvaluator(true));
        assertEquals(AmphibiousNodeEvaluator.class, clone.getClass());
        assertTrue(readBoolean(clone, "prefersShallowSwimming"));

        NodeEvaluator deep = EvaluatorCloner.cloneWithConfig(new AmphibiousNodeEvaluator(false));
        assertFalse(readBoolean(deep, "prefersShallowSwimming"));
    }

    private static boolean readBoolean(Object target, String fieldName) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equals(fieldName)) continue;
                field.setAccessible(true);
                return field.getBoolean(target);
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
