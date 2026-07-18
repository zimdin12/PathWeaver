package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;

class PathNavigationRepathContractTest {
    @Test void recomputeInvalidationIsScopedAcrossNormalAndExceptionalExit() throws Exception {
        PathNavigationMixin mixin = new TestNavigationMixin();
        var invalidated = PathNavigationMixin.class.getDeclaredField("pathweaver$recomputeInvalidated");
        invalidated.setAccessible(true);
        var depth = PathNavigationMixin.class.getDeclaredField("pathweaver$navigationRequestDepth");
        depth.setAccessible(true);
        var wrapper = PathNavigationMixin.class.getDeclaredMethod("pathweaver$armRecomputePath",
            PathNavigation.class, BlockPos.class, int.class, Operation.class);
        wrapper.setAccessible(true);

        Operation<Path> observe = ignored -> {
            assertTrue(booleanValue(invalidated, mixin), "changed-block invalidation must cover createPath");
            return null;
        };
        wrapper.invoke(mixin, null, BlockPos.ZERO, 0, observe);
        assertFalse(invalidated.getBoolean(mixin), "normal exit must clear invalidation");
        assertEquals(0, depth.getInt(mixin), "normal exit must restore routing depth");

        RuntimeException expected = new RuntimeException("expected");
        Operation<Path> fail = ignored -> {
            assertTrue(booleanValue(invalidated, mixin), "exceptional createPath must remain invalidated");
            throw expected;
        };
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> wrapper.invoke(mixin, null, BlockPos.ZERO, 0, fail));
        assertSame(expected, thrown.getCause());
        assertFalse(invalidated.getBoolean(mixin), "exceptional exit must clear invalidation");
        assertEquals(0, depth.getInt(mixin), "exceptional exit must restore routing depth");
    }

    private static boolean booleanValue(java.lang.reflect.Field field, Object target) {
        try {
            return field.getBoolean(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static final class TestNavigationMixin extends PathNavigationMixin {
        @Override protected boolean canUpdatePath() { return true; }
        @Override public boolean moveTo(Path path, double speed) { return false; }
    }
}
