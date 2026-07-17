package dev.pathweaver.mixin;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathNavigationRepathContractTest {
    @Test void recomputeScopeLocksChangedBlockInvalidationAroundVanillaRecompute() throws Exception {
        Map<String, String> expectedAt = Map.of(
            "pathweaver$beginRecomputeInvalidation", "HEAD",
            "pathweaver$endRecomputeInvalidation", "RETURN"
        );
        for (var expected : expectedAt.entrySet()) {
            var method = PathNavigationMixin.class.getDeclaredMethod(expected.getKey(),
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
            Inject inject = method.getAnnotation(Inject.class);
            assertNotNull(inject);
            assertArrayEquals(new String[]{"recomputePath()V"}, inject.method());
            assertEquals(1, inject.require());
            assertEquals(1, inject.expect());
            assertEquals(1, inject.at().length);
            assertEquals(expected.getValue(), inject.at()[0].value());
        }
    }
}
