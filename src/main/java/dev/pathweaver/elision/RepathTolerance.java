package dev.pathweaver.elision;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Feature B: conservative repath elision. Vanilla only reuses a live path when the new target block
 * EXACTLY equals the old target ({@code targets.contains(targetPos)}), so a target that drifts one
 * block forces a full A*. This widens that reuse to a small Manhattan tolerance. With tolerance 0 the
 * behaviour is identical to vanilla; the default of 1 spares the recompute for sub-block target jitter.
 * Lithium already handles the block-change repath trigger, so this only addresses the goal cadence.
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
