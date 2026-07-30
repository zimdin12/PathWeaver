package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier must be decided once, by the scan, and by nothing else. Saving a different tier from the
 * settings screen previously left startup denials waived while per-request checks tightened, so a
 * session started at ALL and saved to STRICT kept dispatching work STRICT forbids.
 */
class ActiveCompatibilityPolicyTest {
    @Test void nothingIsAllowedUntilAScanPublishes() {
        ActiveCompatibilityPolicy.State state = ActiveCompatibilityPolicy.isolatedState();
        assertFalse(state.published());
        assertFalse(state.allowsAudited(), "an absent scan must deny");
        assertFalse(state.bypassesScan(), "an absent scan must deny");
    }

    @Test void theFirstPublicationWinsSoNothingLaterCanWiden() {
        ActiveCompatibilityPolicy.State state = ActiveCompatibilityPolicy.isolatedState();
        state.publish(false, false);                              // STRICT
        state.publish(true, true);                                // a later, wider claim
        assertFalse(state.allowsAudited(), "a second publication must not widen the frozen tier");
        assertFalse(state.bypassesScan(), "a second publication must not widen the frozen tier");
    }

    @Test void publishedAnswersAreReportedExactly() {
        ActiveCompatibilityPolicy.State audited = ActiveCompatibilityPolicy.isolatedState();
        audited.publish(true, false);
        assertTrue(audited.allowsAudited());
        assertFalse(audited.bypassesScan());

        ActiveCompatibilityPolicy.State all = ActiveCompatibilityPolicy.isolatedState();
        all.publish(true, true);
        assertTrue(all.allowsAudited());
        assertTrue(all.bypassesScan());
    }

    /**
     * The dangerous shape is a publication reachable from outside the scan.
     *
     * <p>Because the first write wins, anything on the classpath that published the widest answer
     * first would make the scan's own STRICT publication a no-op, and the same scan would then clear
     * its denials down the ALL path — a direct route from an external call to STRICT behaving as
     * ALL. Publication must therefore not be callable from another package, and the process
     * singleton must carry no reset: a test-only reset compiled into the shipped jar contradicts the
     * process-lifetime guarantee this class exists to make.
     */
    @Test void theProcessSingletonExposesNoMutationOutsideThisPackage() {
        for (var method : ActiveCompatibilityPolicy.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("reset"),
                "no reset seam may ship on the process singleton: " + method.getName());
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertEquals(boolean.class, method.getReturnType(),
                "the public surface must be read-only: " + method.getName());
            assertEquals(0, method.getParameterCount(),
                "the public surface must take no input: " + method.getName());
        }
        assertThrows(NoSuchMethodException.class,
            () -> ActiveCompatibilityPolicy.class.getMethod("publish", boolean.class, boolean.class),
            "publish must not be reachable as a public method");
    }
}
