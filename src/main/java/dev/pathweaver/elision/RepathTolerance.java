package dev.pathweaver.elision;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Feature B: conservative repath elision. Vanilla only reuses a live path when the new target block
 * EXACTLY equals the old target ({@code targets.contains(targetPos)}), so a target that drifts one
 * block forces a full A*. This helper tests a Manhattan tolerance only. In 0.1.2 the mixin bypasses
 * Feature B entirely when the configured tolerance is 0 (the default); positive values are experimental
 * because endpoint/navigation validity and changed-block invalidation are not yet implemented.
 */
public final class RepathTolerance {
    private RepathTolerance() {}

    public static boolean canReuseExistingPath(BlockPos oldTarget, BlockPos newTarget, int toleranceBlocks) {
        return oldTarget != null && newTarget != null
            && oldTarget.distManhattan(newTarget) <= toleranceBlocks;
    }

    /** True if any requested target is within tolerance of the existing target. */
    public static boolean anyWithinTolerance(Set<BlockPos> targets, BlockPos oldTarget, int toleranceBlocks) {
        if (oldTarget == null) return false;
        for (BlockPos t : targets) {
            if (canReuseExistingPath(oldTarget, t, toleranceBlocks)) return true;
        }
        return false;
    }
}
