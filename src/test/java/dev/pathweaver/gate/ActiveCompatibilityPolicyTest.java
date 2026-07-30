package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier must be decided once. Saving a different tier from the settings screen previously left
 * startup denials waived while per-request checks tightened, so a session started at ALL and saved
 * to STRICT kept dispatching work STRICT forbids.
 */
class ActiveCompatibilityPolicyTest {
    @Test void nothingIsAllowedUntilAScanPublishes() {
        try {
            ActiveCompatibilityPolicy.resetForTests();
            assertFalse(ActiveCompatibilityPolicy.published());
            assertFalse(ActiveCompatibilityPolicy.allowsAudited(), "an absent scan must deny");
            assertFalse(ActiveCompatibilityPolicy.bypassesScan(), "an absent scan must deny");
        } finally {
            ActiveCompatibilityPolicy.resetForTests();
        }
    }

    @Test void theFirstPublicationWinsSoNothingLaterCanWiden() {
        try {
            ActiveCompatibilityPolicy.resetForTests();
            ActiveCompatibilityPolicy.publish(false, false);          // STRICT
            ActiveCompatibilityPolicy.publish(true, true);            // a later, wider claim
            assertFalse(ActiveCompatibilityPolicy.allowsAudited(),
                "a second publication must not widen the frozen tier");
            assertFalse(ActiveCompatibilityPolicy.bypassesScan(),
                "a second publication must not widen the frozen tier");
        } finally {
            ActiveCompatibilityPolicy.resetForTests();
        }
    }

    @Test void publishedAnswersAreReportedExactly() {
        try {
            ActiveCompatibilityPolicy.resetForTests();
            ActiveCompatibilityPolicy.publish(true, false);           // AUDITED
            assertTrue(ActiveCompatibilityPolicy.allowsAudited());
            assertFalse(ActiveCompatibilityPolicy.bypassesScan());

            ActiveCompatibilityPolicy.resetForTests();
            ActiveCompatibilityPolicy.publish(true, true);            // ALL
            assertTrue(ActiveCompatibilityPolicy.allowsAudited());
            assertTrue(ActiveCompatibilityPolicy.bypassesScan());
        } finally {
            ActiveCompatibilityPolicy.resetForTests();
        }
    }
}
