package dev.pathweaver.gate;

import java.util.Set;

/**
 * Reaches {@link SafetyGate}'s package-private denial mutators from tests in other packages.
 *
 * <p>The mutators used to be {@code public}, which meant any mod on a user's classpath could clear
 * every scan denial — including ones a FAILED scan installed, which no tier is permitted to waive.
 * Making them package-private closed that, and this class is how the tests that legitimately need
 * them still get in.
 *
 * <p>It lives in a source set that is not packaged into the mod jar, so it does not reopen the hole
 * it exists to work around. A contract test asserts the shipped class exposes no public mutator.
 */
public final class SafetyGateTestAccess {

    private SafetyGateTestAccess() { }

    public static void deny(Class<?> family) {
        SafetyGate.denyForTesting(family);
    }

    public static void undeny(Class<?> family) {
        SafetyGate.undenyForTesting(family);
    }

    public static void restore(Set<Class<?>> denials) {
        SafetyGate.restoreDenialsForTesting(denials);
    }

    public static void clear() {
        SafetyGate.restoreDenialsForTesting(Set.of());
    }

    /** Clears the runtime-failure (breaker) denials, which are a separate set from the scan's. */
    public static void clearRuntimeFailureDenials() {
        SafetyGate.resetRuntimeFailureDenials();
    }
}
