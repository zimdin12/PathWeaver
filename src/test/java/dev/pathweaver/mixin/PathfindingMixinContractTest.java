package dev.pathweaver.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Locks the exact injection points that keep live mob state off worker threads.
 *
 * <p>Descriptor drift here is not a missing feature, it is corruption. If the {@code prepare} redirect
 * stops matching, the worker runs a second prepare after the main thread's, and the amphibious
 * evaluator's second prepare captures the already-overwritten malus as the value to restore — so
 * {@code done()} writes the search's temporary costs onto the mob permanently. If the {@code getRandom}
 * redirect stops matching, a worker draws from the live mob's shared randomness on every flying search.
 *
 * <p>Both failures are silent at runtime and neither shows up as a wrong path. {@code require = 1}
 * turns them into a startup failure instead, and this test guarantees nobody quietly relaxes that.
 */
class PathfindingMixinContractTest {

    private static final String FIND_PATH =
        "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;";

    private static final Map<String, String> PATH_FINDER_REDIRECTS = Map.of(
        FIND_PATH + "#prepare",
        "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;"
            + "prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V",
        FIND_PATH + "#done",
        "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;done()V");

    @Test
    void pathFinderSkipsBothSearchEndsOnWorkers() {
        Map<String, Redirect> byTarget = redirectsOf(PathFinderMixin.class);
        assertEquals(2, byTarget.size(), "findPath must redirect exactly prepare and done");
        for (Redirect redirect : byTarget.values()) {
            assertEquals(1, redirect.method().length);
            assertEquals(FIND_PATH, redirect.method()[0], "must target the public findPath overload");
            assertEquals("INVOKE", redirect.at().value());
            assertEquals(1, redirect.require(), "must fail closed on mapping drift");
            assertEquals(1, redirect.expect(), "must lock the call count");
        }
        assertEquals(
            java.util.Set.copyOf(PATH_FINDER_REDIRECTS.values()),
            java.util.Set.copyOf(byTarget.keySet()));
    }

    @Test
    void flyEvaluatorRedirectsExactlyTheStartNodeRandomDraw() {
        Map<String, Redirect> byTarget = redirectsOf(FlyNodeEvaluatorMixin.class);
        assertEquals(1, byTarget.size(), "exactly one live-mob randomness read exists in this class");

        Redirect redirect = byTarget.get(
            "Lnet/minecraft/world/entity/Mob;getRandom()Lnet/minecraft/util/RandomSource;");
        assertEquals("iteratePathfindingStartNodeCandidatePositions"
                + "(Lnet/minecraft/world/entity/Mob;)Ljava/lang/Iterable;",
            redirect.method()[0]);
        assertEquals("INVOKE", redirect.at().value());
        assertEquals(1, redirect.require(), "must fail closed on mapping drift");
        assertEquals(1, redirect.expect(), "a second draw would be an unaudited live read");
    }

    private static Map<String, Redirect> redirectsOf(Class<?> mixin) {
        Map<String, Redirect> byTarget = new HashMap<>();
        for (var method : mixin.getDeclaredMethods()) {
            Redirect redirect = method.getAnnotation(Redirect.class);
            if (redirect == null) continue;
            assertNull(byTarget.put(redirect.at().target(), redirect),
                "two redirects share an injection target");
        }
        return byTarget;
    }
}
