package dev.pathweaver.gate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier must be decided once, by the scan, and by nothing else.
 *
 * <p>Saving a different tier from the settings screen previously left startup denials waived while
 * per-request checks tightened, so a session started at ALL and saved to STRICT kept dispatching
 * work STRICT forbids.
 */
class ActiveCompatibilityPolicyTest {
    @Test void nothingIsAllowedUntilTheScanFreezesATier() {
        // Unit tests never run the scan, so the process answer must be the fail-closed one.
        assertFalse(ActiveCompatibilityPolicy.published(), "no scan runs in unit tests");
        assertFalse(ActiveCompatibilityPolicy.allowsAudited(), "an absent scan must deny");
        assertFalse(ActiveCompatibilityPolicy.bypassesScan(), "an absent scan must deny");
    }

    /**
     * The dangerous shape is a mutator reachable from anywhere but the scan.
     *
     * <p>Because the first write wins, anything that froze the widest answer first would make the
     * scan's own STRICT publication a no-op, and the same scan would then clear its denials down the
     * ALL path — a direct route from an external call to STRICT behaving as ALL.
     *
     * <p>Package-private is not sufficient protection here. Runtime package access is package name
     * plus classloader, and Fabric mods share the target classloader, so a mod shipping a class in
     * {@code dev.pathweaver.gate} can reach package-private members of this package. Only
     * {@code private} is enforced against that, so the mutator must live in the class that computes
     * the value and this facade must expose no mutation at all.
     */
    @Test void theFacadeExposesNoMutationAtAnyVisibility() {
        for (Method method : ActiveCompatibilityPolicy.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            assertEquals(boolean.class, method.getReturnType(),
                "the facade must answer, not act: " + method.getName());
            assertEquals(0, method.getParameterCount(),
                "the facade must take no input: " + method.getName());
        }
        assertEquals(0, ActiveCompatibilityPolicy.class.getDeclaredFields().length,
            "the facade must hold no state that could be frozen out of band");
    }

    /**
     * The mutator itself must be private, not merely unexported, so a split-package caller on the
     * shared mod classloader cannot invoke it.
     */
    @Test void onlyTheScannerCanFreezeTheTier() throws Exception {
        Method freeze = ForeignMixinScanner.class
            .getDeclaredMethod("freezeActiveTier", boolean.class, boolean.class);
        assertTrue(Modifier.isPrivate(freeze.getModifiers()),
            "freezing the tier must be private to the class that computes it");

        for (Method method : ForeignMixinScanner.class.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) continue;
            String name = method.getName().toLowerCase();
            boolean touchesTier = name.contains("tier") || name.contains("freeze");
            if (!touchesTier) continue;
            assertEquals(boolean.class, method.getReturnType(),
                "non-private tier surface must be read-only: " + method.getName());
            assertEquals(0, method.getParameterCount(),
                "non-private tier surface must take no input: " + method.getName());
        }
    }

    @Test void noResetSeamShipsOnEitherOwner() {
        for (Class<?> owner : new Class<?>[] {
                ActiveCompatibilityPolicy.class, ForeignMixinScanner.class}) {
            for (Method method : owner.getDeclaredMethods()) {
                assertFalse(method.getName().toLowerCase().contains("resettier"),
                    "no tier reset may ship: " + owner.getSimpleName() + "." + method.getName());
            }
        }
    }
}
