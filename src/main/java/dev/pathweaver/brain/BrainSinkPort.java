package dev.pathweaver.brain;

import dev.pathweaver.async.EntityInstallSink;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

/**
 * The live side of the brain-sink decision: the sink, the navigation, and the request window.
 *
 * <p>{@link BrainSinkPolicy} decides; this is everything it needs to look at to decide. Keeping them
 * apart is what lets the policy be driven without a server, which is the only reason its liveness
 * bound and its four deferral paths have tests at all.
 *
 * <p>This used to be a thirty-line anonymous class inside the mixin handler, which is how that
 * handler reached eighty-five lines. Nothing about it needs to be there: it holds no mixin state and
 * touches no shadowed field.
 */
public final class BrainSinkPort implements BrainSinkPolicy.SearchPort {

    /**
     * The reach range vanilla itself passes on this route.
     *
     * <p>{@code MoveToTargetSink} calls {@code createPath(BlockPos, int)} with 0, and the value ends
     * up in the path's own reach range, so probing with anything else would answer a different
     * question from the one vanilla asked.
     */
    public static final int VANILLA_REACH_RANGE = 0;

    private final EntityInstallSink sink;
    private final PathNavigation navigation;
    private final PWNavigation duck;

    public BrainSinkPort(EntityInstallSink sink, PathNavigation navigation, PWNavigation duck) {
        this.sink = sink;
        this.navigation = navigation;
        this.duck = duck;
    }

    @Override public Path takeParked(int entityId, BlockPos asked) {
        return sink.takeBrainSinkPath(entityId, asked);
    }

    @Override public boolean hasPending(int entityId, BlockPos asked) {
        return sink.hasPendingBrainSink(entityId, asked);
    }

    @Override public boolean isRegistered(int entityId) {
        return sink.isRegistered(entityId);
    }

    /**
     * The two conditions {@code moveTo(Path, double)} refuses on: a path already finished, and one
     * that trims to no nodes.
     *
     * <p>Handing back a path it would refuse leaves the behaviour started, holding a route the
     * navigation does not have, with nothing that will ever re-evaluate it. That is the frozen
     * villager.
     */
    @Override public boolean acceptableToVanilla(Path path) {
        return path != null && !path.isDone() && path.getNodeCount() > 0;
    }

    @Override public BrainSinkPolicy.Probe probe(double speed, BlockPos asked) {
        // A window already open on this navigation means something re-entered. The navigation
        // refuses, and the policy leaves the whole call to vanilla rather than answer from nothing.
        if (!duck.pathweaver$enterBrainSinkRequest(speed, asked)) {
            return BrainSinkPolicy.Probe.refused();
        }
        try {
            return BrainSinkPolicy.Probe.ran(navigation.createPath(asked, VANILLA_REACH_RANGE));
        } finally {
            duck.pathweaver$exitBrainSinkRequest();
        }
    }
}
