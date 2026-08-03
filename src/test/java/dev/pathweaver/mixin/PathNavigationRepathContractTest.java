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

    /**
     * The recompute seam's whole purpose, and until 0.5.3 nothing executed it.
     *
     * <p>The claim field defaulted to {@code null} in every existing test, so the re-apply block was
     * dead code as far as the suite was concerned — the headline fix of 0.5.2 shipped with no
     * coverage at all, and the game-test named after it turned out to pass with the bug reintroduced.
     *
     * <p>What must hold: the destination handed to vanilla's {@code createPath} is the CLAIMED one,
     * not the rolled-back one, and {@code targetPos} is left agreeing with it. If either half is
     * missed the mob searches for one destination and records another.
     */
    @Test void theWrapSearchesForTheClaimedDestinationAndLeavesTargetPosAgreeingWithIt()
            throws Exception {
        PathNavigationMixin mixin = new TestNavigationMixin();
        BlockPos claimed = new BlockPos(40, 64, -12);
        BlockPos rolledBack = new BlockPos(1, 2, 3);

        setClaim(mixin, claimed);
        setTargetPos(mixin, rolledBack);

        BlockPos[] searchedFor = new BlockPos[1];
        Operation<Path> observe = args -> {
            searchedFor[0] = (BlockPos) args[1];
            return null;
        };
        wrapper().invoke(mixin, null, rolledBack, 7, observe);

        assertEquals(claimed, searchedFor[0],
            "vanilla must search for the claimed destination, not the pre-dispatch one");
        assertEquals(claimed, targetPos(mixin),
            "targetPos must name the destination actually searched for");
    }

    /** With no claim outstanding the seam must be transparent — vanilla's own target survives. */
    @Test void theWrapLeavesAnUnclaimedRecomputeExactlyAsVanillaHadIt() throws Exception {
        PathNavigationMixin mixin = new TestNavigationMixin();
        BlockPos vanillaTarget = new BlockPos(5, 70, 5);
        setTargetPos(mixin, vanillaTarget);

        BlockPos[] searchedFor = new BlockPos[1];
        Operation<Path> observe = args -> {
            searchedFor[0] = (BlockPos) args[1];
            return null;
        };
        wrapper().invoke(mixin, null, vanillaTarget, 3, observe);

        assertEquals(vanillaTarget, searchedFor[0], "no claim must mean no substitution");
        assertEquals(vanillaTarget, targetPos(mixin), "no claim must mean targetPos is untouched");
    }

    private static java.lang.reflect.Method wrapper() throws Exception {
        var m = PathNavigationMixin.class.getDeclaredMethod("pathweaver$armRecomputePath",
            PathNavigation.class, BlockPos.class, int.class, Operation.class);
        m.setAccessible(true);
        return m;
    }

    private static void setTargetPos(PathNavigationMixin mixin, BlockPos pos) throws Exception {
        var f = PathNavigationMixin.class.getDeclaredField("targetPos");
        f.setAccessible(true);
        f.set(mixin, pos);
    }

    private static BlockPos targetPos(PathNavigationMixin mixin) throws Exception {
        var f = PathNavigationMixin.class.getDeclaredField("targetPos");
        f.setAccessible(true);
        return (BlockPos) f.get(mixin);
    }

    private static void setClaim(PathNavigationMixin mixin, BlockPos claim) throws Exception {
        var f = PathNavigationMixin.class.getDeclaredField("pathweaver$recomputeTargetClaim");
        f.setAccessible(true);
        f.set(mixin, claim);
    }

    private static boolean booleanValue(java.lang.reflect.Field field, Object target) {
        try {
            return field.getBoolean(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static final class TestNavigationMixin extends PathNavigationMixin {
        boolean stopped;
        @Override public void stop() { stopped = true; this.path = null; }

        @Override protected boolean canUpdatePath() { return true; }
        @Override public boolean moveTo(Path path, double speed) { return false; }
    }
}
