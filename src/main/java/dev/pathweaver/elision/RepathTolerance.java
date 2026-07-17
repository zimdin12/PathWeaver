package dev.pathweaver.elision;

import net.minecraft.core.BlockPos;

import java.util.Set;

/** Conservative positive-tolerance reuse checks layered above vanilla's exact-target reuse. */
public final class RepathTolerance {
    private RepathTolerance() {}

    /** Immutable live-navigation facts captured on the main thread for one reuse decision. */
    public record CurrentPath(BlockPos target, BlockPos endpoint, boolean reached, boolean done,
                              boolean canUpdate, boolean recomputeInvalidated, int reachRange) { }

    public static boolean canReuseExistingPath(BlockPos oldTarget, BlockPos newTarget, int toleranceBlocks) {
        return oldTarget != null && newTarget != null && toleranceBlocks >= 0
            && manhattan(oldTarget, newTarget) <= toleranceBlocks;
    }

    /**
     * Reuse requires one requested target to satisfy both semantic-target tolerance and endpoint reach.
     * The endpoint bound widens only by the explicit tolerance; all other navigation facts stay exact.
     */
    public static boolean canReuseExistingPath(Set<BlockPos> targets, CurrentPath current,
                                               int requestedReachRange, int toleranceBlocks) {
        return reusableTarget(targets, current, requestedReachRange, toleranceBlocks) != null;
    }

    /** Returns the deterministic target whose intent must replace PathNavigation.targetPos on reuse. */
    public static BlockPos reusableTarget(Set<BlockPos> targets, CurrentPath current,
                                          int requestedReachRange, int toleranceBlocks) {
        if (targets == null || targets.isEmpty() || current == null
                || current.target() == null || current.endpoint() == null
                || toleranceBlocks < 0 || requestedReachRange < 0
                || !current.reached() || current.done() || !current.canUpdate()
                || current.recomputeInvalidated()
                || current.reachRange() != requestedReachRange) {
            return null;
        }
        long endpointLimit = (long) requestedReachRange + toleranceBlocks;
        BlockPos best = null;
        long bestTargetDistance = Long.MAX_VALUE;
        long bestEndpointDistance = Long.MAX_VALUE;
        for (BlockPos target : targets) {
            if (target == null) continue;
            long targetDistance = manhattan(current.target(), target);
            long endpointDistance = manhattan(current.endpoint(), target);
            if (targetDistance > toleranceBlocks || endpointDistance > endpointLimit) continue;
            if (best == null || targetDistance < bestTargetDistance
                    || targetDistance == bestTargetDistance && endpointDistance < bestEndpointDistance
                    || targetDistance == bestTargetDistance && endpointDistance == bestEndpointDistance
                        && compareCoordinates(target, best) < 0) {
                best = target;
                bestTargetDistance = targetDistance;
                bestEndpointDistance = endpointDistance;
            }
        }
        return best;
    }

    private static long manhattan(BlockPos left, BlockPos right) {
        return Math.abs((long) left.getX() - right.getX())
            + Math.abs((long) left.getY() - right.getY())
            + Math.abs((long) left.getZ() - right.getZ());
    }

    private static int compareCoordinates(BlockPos left, BlockPos right) {
        int x = Integer.compare(left.getX(), right.getX());
        if (x != 0) return x;
        int y = Integer.compare(left.getY(), right.getY());
        return y != 0 ? y : Integer.compare(left.getZ(), right.getZ());
    }
}
