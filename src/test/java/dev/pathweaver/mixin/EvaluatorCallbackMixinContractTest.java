package dev.pathweaver.mixin;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluatorCallbackMixinContractTest {
    private static final Map<String, String> EXPECTED = Map.of(
        "prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V",
        "Lnet/minecraft/world/entity/Mob;onPathfindingStart()V",
        "done()V",
        "Lnet/minecraft/world/entity/Mob;onPathfindingDone()V"
    );

    @Test void walkCallbackRedirectsLockExactMultiplicityAndTargets() {
        Map<String, Redirect> redirects = new HashMap<>();
        for (var method : WalkNodeEvaluatorMixin.class.getDeclaredMethods()) {
            Redirect redirect = method.getAnnotation(Redirect.class);
            if (redirect == null) continue;
            assertEquals(1, redirect.method().length);
            assertNull(redirects.put(redirect.method()[0], redirect), "duplicate callback redirect");
        }

        assertEquals(EXPECTED.keySet(), redirects.keySet());
        for (var expected : EXPECTED.entrySet()) {
            Redirect redirect = redirects.get(expected.getKey());
            assertEquals(1, redirect.require(), expected.getKey() + " must fail closed on mapping drift");
            assertEquals(1, redirect.expect(), expected.getKey() + " must lock callback count");
            assertEquals("INVOKE", redirect.at().value());
            assertEquals(expected.getValue(), redirect.at().target());
        }
    }
}
