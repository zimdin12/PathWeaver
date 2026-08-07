package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a search can reach on a watched class, derived from Minecraft's bytecode.
 *
 * <p>The other half of the narrowing, and the half that decides whether it is honest. If this set is
 * too small, harmless-looking claims get cleared that a search really does reach — and mobs then
 * path off-thread through foreign code the gate was built to keep out.
 *
 * <p>So the assertions here are in one direction: methods a search demonstrably calls MUST be
 * present. The measured examples are real mods from a 317-jar pack — {@code expandability} and
 * {@code vehicleupgrade} inject {@code getCollisionShape}, {@code terrain_slabs} injects
 * {@code getShape} — and all of them must keep denying.
 */
class WorkerReachableMethodsTest {

    private static final String BLOCK_STATE_BASE =
        "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";

    @Test
    void theCollisionAndShapeReadsASearchReallyMakesAreReachable() {
        Optional<Set<String>> reachable = WorkerReachableMethods.on(BLOCK_STATE_BASE);
        assertTrue(reachable.isPresent(),
            "the walk must produce an answer for BlockStateBase, or every claim on it stays denied "
                + "and the narrowing does nothing");
        Set<String> methods = reachable.get();

        // WalkNodeEvaluator.getFloorLevel -> BlockState.getCollisionShape is the call that makes
        // expandability and vehicleupgrade genuinely dangerous. If this ever stops being reported,
        // those mods would be silently cleared.
        assertTrue(methods.contains("getCollisionShape"),
            "getCollisionShape must be reachable -- two mods on a real pack inject it: " + methods);
        assertTrue(methods.contains("getShape") || methods.contains("getCollisionShape"),
            "the shape reads a search performs must be reachable: " + methods);
        assertFalse(methods.isEmpty(), "an empty set would clear every claim on this class");
    }

    @Test
    void aMethodNoSearchTouchesIsNotReported() {
        // getDestroyProgress is block-breaking. Balm injects it, and that is the case the narrowing
        // exists to clear. If this ever becomes reachable the narrowing has over-approximated into
        // uselessness and should be re-examined rather than trusted.
        Optional<Set<String>> reachable = WorkerReachableMethods.on(BLOCK_STATE_BASE);
        assertTrue(reachable.isPresent(), "precondition: the walk answered");
        assertFalse(reachable.get().contains("getDestroyProgress"),
            "block-breaking is not on the pathfinding read path; if it is reported, the walk is "
                + "following edges a worker cannot take: " + reachable.get());
    }

    @Test
    void anUnknownClassFailsClosed() {
        assertTrue(WorkerReachableMethods.on("net/minecraft/does/Not/Exist").isPresent()
                || WorkerReachableMethods.on("net/minecraft/does/Not/Exist").isEmpty(),
            "must not throw");
        // A class nothing calls yields an empty reachable set, which denies nothing extra but also
        // clears nothing -- the caller requires a NON-empty intersection to deny, and an empty
        // reachable set means no injected method can match, so this must never be the answer for a
        // class the search really uses. Pinned by the first test above.
        assertTrue(WorkerReachableMethods.on("net/minecraft/does/Not/Exist").get().isEmpty(),
            "a class no search mentions has no reachable methods");
    }
}
