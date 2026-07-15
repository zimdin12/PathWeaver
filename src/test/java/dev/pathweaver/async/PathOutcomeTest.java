package dev.pathweaver.async;

import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathOutcomeTest {
    @Test void noPathIsACompletedNonFailureOutcome() {
        PathOutcome outcome = PathOutcome.noPath();
        assertEquals(PathOutcome.Status.NO_PATH, outcome.status());
        assertNull(outcome.path());
        assertNull(outcome.failure());
    }

    @Test void failedRetainsTheCauseWithoutPretendingItIsNoPath() {
        IllegalStateException cause = new IllegalStateException("boom");
        PathOutcome outcome = PathOutcome.failed(cause);
        assertEquals(PathOutcome.Status.FAILED, outcome.status());
        assertNull(outcome.path());
        assertSame(cause, outcome.failure());
    }

    @Test void factoriesAndConstructorRejectContradictoryTags() {
        assertThrows(NullPointerException.class, () -> PathOutcome.success(null));
        assertThrows(NullPointerException.class, () -> PathOutcome.failed(null));
        assertThrows(IllegalArgumentException.class, () ->
            new PathOutcome(PathOutcome.Status.NO_PATH, dummyPath(), null));
        assertThrows(IllegalArgumentException.class, () ->
            new PathOutcome(PathOutcome.Status.FAILED, null, null));
    }

    private static Path dummyPath() {
        try {
            var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return (Path) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, Path.class);
        } catch (Throwable failure) {
            throw new RuntimeException(failure);
        }
    }
}
