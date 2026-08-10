package dev.pathweaver.gate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming the mod is the half of this feature that pays on every install.
 *
 * <p>The breaker beside it may never fire — the reference pack produces zero search failures in a full
 * validation run — but a failure that names nothing is a failure nobody can report. These tests pin both
 * signals and, just as importantly, pin that a signal which cannot fire says so instead of implying
 * no mod was involved.
 */
class ModAttributionTest {

    @AfterEach
    void clear() {
        ModAttribution.useModIdsForTesting(null);
    }

    /**
     * Mixin's generated handler names carry the owning mod id, and that is usually the ONLY signal.
     *
     * <p>An injected handler is merged into its target class, so the frame reads
     * {@code net.minecraft.…WalkNodeEvaluator} — the mod's own package never appears. Losing this
     * parse means losing attribution for the majority of real cases.
     */
    @Test
    void theHandlerNameConventionYieldsTheOwningModId() {
        assertEquals("lithium",
            ModAttribution.modIdFromHandlerName("handler$zzl000$lithium$onGetNodeType"));
        assertEquals("servercore",
            ModAttribution.modIdFromHandlerName("handler$abc123$servercore$something"));
    }

    /** A name that is not a handler must contribute nothing rather than a guess. */
    @Test
    void anythingThatIsNotAHandlerNameYieldsNothing() {
        assertNull(ModAttribution.modIdFromHandlerName("getNeighbors"));
        assertNull(ModAttribution.modIdFromHandlerName(null));
        assertNull(ModAttribution.modIdFromHandlerName("handler$short"),
            "a malformed handler name means the convention changed, not that a mod is called short");
    }

    /** The second signal: a mod id appearing as a package segment of a real mod class. */
    @Test
    void aModIdInThePackagePathIsRecognised() {
        ModAttribution.useModIdsForTesting(Set.of("lithium", "servercore"));
        assertEquals("lithium",
            ModAttribution.modIdForClassName("me.jellysquid.mods.lithium.common.ai.PathCache"));
        assertNull(ModAttribution.modIdForClassName("net.minecraft.world.level.pathfinder.Path"),
            "vanilla must never be attributed to a mod");
    }

    /**
     * Short segments are ignored, because coincidence is more likely than attribution.
     *
     * <p>A two-letter mod id would match package segments across half the ecosystem and turn a
     * bug report into a list of innocent mods.
     */
    @Test
    void veryShortModIdsAreNotMatchedAgainstPackageSegments() {
        ModAttribution.useModIdsForTesting(Set.of("me"));
        assertNull(ModAttribution.modIdForClassName("me.jellysquid.mods.lithium.Anything"));
    }

    /** Both signals, over a real throwable, with vanilla and PathWeaver itself excluded. */
    @Test
    void suspectsCombinesBothSignalsAndExcludesTheUninteresting() {
        // "minecraft" and "java" are in the set deliberately: FabricLoader really does report them
        // as mods, so a test that omits them cannot notice if the exclusion is deleted -- and then
        // every vanilla frame in every failure would be listed as a suspect.
        ModAttribution.useModIdsForTesting(Set.of("lithium", "pathweaver", "minecraft", "java"));
        RuntimeException failure = new RuntimeException("synthetic");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("net.minecraft.world.level.pathfinder.WalkNodeEvaluator",
                "handler$zzl000$lithium$getNodeType", "WalkNodeEvaluator.java", 1),
            new StackTraceElement("dev.pathweaver.async.PathWorkerPool", "run", "x.java", 2),
            new StackTraceElement("net.minecraft.world.level.pathfinder.PathFinder",
                "findPath", "PathFinder.java", 3),
        });

        List<String> suspects = ModAttribution.suspects(failure);
        assertTrue(suspects.contains("lithium"),
            "the handler name is the only place the mod appears, and it must be read");
        assertFalse(suspects.contains("pathweaver"),
            "naming ourselves in our own failure report is noise");
        assertFalse(suspects.contains("minecraft"), "so is naming vanilla");
    }

    /**
     * When nothing can be named, that must be stated rather than left as an empty list.
     *
     * <p>An {@code @Overwrite} or an inlined {@code @Redirect} leaves a stack indistinguishable from
     * vanilla's own. A reader shown nothing would reasonably conclude no mod was involved, which is
     * the opposite of what an empty result means.
     */
    @Test
    void anUnattributableFailureProducesNoSuspectsRatherThanAWrongOne() {
        ModAttribution.useModIdsForTesting(Set.of("lithium"));
        RuntimeException failure = new RuntimeException("synthetic");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("net.minecraft.world.level.pathfinder.WalkNodeEvaluator",
                "getNodeType", "WalkNodeEvaluator.java", 1),
        });
        assertTrue(ModAttribution.suspects(failure).isEmpty(),
            "an overwritten method is genuinely unattributable, and guessing would be worse");
    }

    /** A throwable that refuses to describe itself must not take the server down with it. */
    @Test
    void aHostileThrowableCannotBreakAttribution() {
        Throwable hostile = new RuntimeException("hostile") {
            @Override public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("no");
            }
        };
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            hostile::getStackTrace,
            "precondition: this throwable really is hostile");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> WorkerFailureBreaker.recordSearchFailure(
                net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class, hostile, 1L),
            "attribution runs inside a failure path whose delivery side has no catch at all");
    }

    /**
     * A cyclic cause chain must not spin a worker forever.
     *
     * <p>Two throwables that cause each other defeat a self-cause guard. This runs on a worker inside
     * a catch-all that can swallow a throwable but cannot break a loop, so the bound is the only
     * thing standing between a cause cycle and a pegged core.
     */
    @Test
    @org.junit.jupiter.api.Timeout(value = 5)
    void aCyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        a.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("net.minecraft.world.level.pathfinder.WalkNodeEvaluator",
                "handler$zzl000$lithium$getNodeType", "WalkNodeEvaluator.java", 1)});
        b.setStackTrace(a.getStackTrace());
        ModAttribution.useModIdsForTesting(Set.of("lithium"));

        assertEquals(List.of("lithium"), ModAttribution.suspects(a),
            "it must terminate, and still find what is there");
    }

}
