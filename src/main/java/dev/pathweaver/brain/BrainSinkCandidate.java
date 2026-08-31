package dev.pathweaver.brain;

import dev.pathweaver.PathWeaverRuntime;
import dev.pathweaver.config.PathWeaverConfig;
import dev.pathweaver.duck.PWNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

import java.util.Optional;

/**
 * Whether a call is a candidate for the brain sink at all, and if not, why not.
 *
 * <p>Four conditions have to hold before the sink can be consulted: the feature is on, the runtime
 * is up, the navigation carries our duck interface, and there is a walk target to path to. Each has
 * a distinct reason, and the reason is carried rather than discarded, because
 * {@link BrainSinkDiagnostics} records it and a test that cannot tell "off" from "no walk target"
 * cannot say which branch it exercised.
 *
 * <p>Both hooks need this chain, and both used to carry their own copy. The start check reads the
 * walk target from the brain; the tick route is already holding the one vanilla is acting on, so it
 * passes that in rather than reading it a second time and risking a different answer.
 *
 * <p>What is deliberately NOT here are vanilla's own two guards, the cooldown and the arrival check.
 * Those read shadowed fields on the behaviour and call a shadowed method, so they belong in the
 * mixin, and they have to run after this and before the sink is consulted. The mixin says why.
 */
public record BrainSinkCandidate(String declineReason, PWNavigation duck, PathNavigation navigation,
                                 WalkTarget walkTarget, BlockPos asked) {

    /** True when every precondition holds and the sink may be consulted. */
    public boolean eligible() {
        return declineReason == null;
    }

    /** The start-check route, which reads the walk target from the brain. */
    public static BrainSinkCandidate fromBrain(Mob mob) {
        return evaluate(mob, null);
    }

    /** The tick route, which already holds the walk target vanilla is acting on. */
    public static BrainSinkCandidate forTarget(Mob mob, WalkTarget walkTarget) {
        return evaluate(mob, walkTarget);
    }

    /**
     * Fails closed: every path that cannot establish all four preconditions returns a decline, and
     * no caller can reach the navigation or the walk target without {@link #eligible()} first.
     *
     * @param known the walk target vanilla is already acting on, or null to read it from the brain
     */
    private static BrainSinkCandidate evaluate(Mob mob, WalkTarget known) {
        PathWeaverConfig cfg = PathWeaverConfig.get();
        if (!cfg.enabled || !cfg.brainSinkAsync) return declined("off");
        if (!PathWeaverRuntime.get().isRunning()) return declined("notRunning");

        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof PWNavigation duck)) return declined("noDuck");

        WalkTarget target = known;
        if (target == null) {
            Optional<WalkTarget> fromBrain = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            if (fromBrain.isEmpty()) return declined("noWalkTarget");
            target = fromBrain.get();
        }
        return new BrainSinkCandidate(null, duck, navigation, target,
            target.getTarget().currentBlockPosition());
    }

    private static BrainSinkCandidate declined(String reason) {
        return new BrainSinkCandidate(reason, null, null, null, null);
    }
}
