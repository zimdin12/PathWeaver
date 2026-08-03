package dev.pathweaver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Telling an operator their machine is too small for this mod to help.
 *
 * <p>PathWeaver does not make pathfinding cheaper — it moves the work off the server thread and adds
 * a little of its own (prologue, epilogue and install all run on the main thread, and every discarded
 * search is CPU spent for nothing). That only pays when a core is free for the worker. Nothing else
 * in the mod surfaces this: on two cores it still dispatches, still installs, still reports healthy
 * counters, and quietly returns less than it costs.
 *
 * <p>The advice is deliberately not enforcement. Nothing switches itself off, for the same reason the
 * self-defeating settings are reported rather than clamped.
 */
class LowCoreAdviceTest {

    @Test
    void aManyCoreMachineIsToldNothing() {
        assertTrue(PathWeaverRuntime.lowCoreAdvice(32, 8).isEmpty(),
            "a machine with headroom must not be nagged");
        assertTrue(PathWeaverRuntime.lowCoreAdvice(8, 2).isEmpty(),
            "eight cores is comfortably above the threshold");
    }

    @Test
    void twoCoresGetAnExplicitRecommendationToTurnItOff() {
        List<String> advice = PathWeaverRuntime.lowCoreAdvice(2, 1);
        assertFalse(advice.isEmpty());
        String text = String.join(" | ", advice);
        assertTrue(text.contains("RECOMMENDATION"),
            "the operator should not have to infer the conclusion: " + text);
        assertTrue(text.contains("enabled=false"),
            "name the setting, so acting on it does not require reading the config: " + text);
        assertTrue(text.contains("2 processor"), "state the observed core count: " + text);
    }

    @Test
    void theRecommendationSaysNothingWasSwitchedOffAutomatically() {
        // The project reports rather than clamps, and an operator who reads a strong recommendation
        // needs to know whether it already happened to them.
        String text = String.join(" | ", PathWeaverRuntime.lowCoreAdvice(1, 1));
        assertTrue(text.contains("Nothing is switched off automatically"),
            "an operator must be able to tell advice from action: " + text);
    }

    @Test
    void fourCoresGetAWeakerWarningRatherThanTheSameOne() {
        List<String> marginal = PathWeaverRuntime.lowCoreAdvice(4, 1);
        assertFalse(marginal.isEmpty(), "four cores is one worker; say so");
        String text = String.join(" | ", marginal);
        assertFalse(text.contains("RECOMMENDATION"),
            "four cores is marginal, not hopeless -- do not tell them to turn it off: " + text);
        assertTrue(text.contains("/pathweaver status"),
            "point at the number that settles it for their pack: " + text);
    }

    @Test
    void theThresholdIsInclusiveAtBothEnds() {
        // Pinned because an off-by-one here either nags every 5-core machine or silently drops the
        // 4-core case the auto-sizer bottoms out on.
        assertFalse(PathWeaverRuntime.lowCoreAdvice(PathWeaverRuntime.CORES_WITH_LITTLE_HEADROOM, 1)
            .isEmpty(), "the boundary itself must warn");
        assertTrue(PathWeaverRuntime.lowCoreAdvice(PathWeaverRuntime.CORES_WITH_LITTLE_HEADROOM + 1, 2)
            .isEmpty(), "one core above the boundary must be silent");
        assertTrue(String.join(" ", PathWeaverRuntime.lowCoreAdvice(
                PathWeaverRuntime.CORES_WITH_NO_HEADROOM, 1)).contains("RECOMMENDATION"),
            "the no-headroom boundary gets the strong form");
    }

    @Test
    void everyLineIsPlainProseFitForALogAndNotAStackOfNumbers() {
        for (int cores : new int[] {1, 2, 3, 4}) {
            for (String line : PathWeaverRuntime.lowCoreAdvice(cores, 1)) {
                assertFalse(line.isBlank(), "no blank lines in a warning block");
                assertTrue(line.length() <= 200,
                    "a log line this long wraps into noise in a console: " + line);
            }
        }
    }

    @Test
    void theWorkerCountIsReportedSoTheAdviceMatchesWhatIsActuallyRunning() {
        // The auto-sizer is cores/4, but poolThreads can override it. Quoting the configured number
        // rather than a derived guess is what makes this checkable by the person reading it.
        assertTrue(String.join(" ", PathWeaverRuntime.lowCoreAdvice(2, 7)).contains("7 worker"),
            "report the workers actually started, not the ones the auto-sizer would have picked");
    }
}
