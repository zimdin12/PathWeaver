package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The third {@code Mob.maxUpStep()} call site, which {@link WalkNodeEvaluatorMixin} cannot reach.
 *
 * <p>That mixin redirects the two calls inside {@code WalkNodeEvaluator}, and its javadoc declares
 * the attribute race eliminated. It was eliminated for two of the six admitted families.
 * {@code AmphibiousNodeEvaluator} <em>overrides</em> {@code getNeighbors}: it calls
 * {@code super.getNeighbors} at offset 3 — that one is redirected, because it executes inside
 * {@code WalkNodeEvaluator}'s own body — and then makes its own {@code invokevirtual
 * Mob.maxUpStep()} at offset 72, in its own class. A mixin transforms only its target class's
 * bytecode, so that call was never touched.
 *
 * <p>{@code Frog$FrogNodeEvaluator extends AmphibiousNodeEvaluator} and overrides only
 * {@code getStart} and {@code getPathType}, so it inherits this method verbatim and is affected
 * identically. Between them that is axolotls, turtles, drowned and frogs — four vanilla families the
 * allowlist admits and which therefore dispatch off-thread on the shipped default.
 *
 * <p>The hazard is the one {@link WalkNodeEvaluatorMixin} documents at length: {@code maxUpStep()}
 * resolves to {@code AttributeInstance.getValue()}, which is
 * {@code if (dirty) { cachedValue = calculateValue(); dirty = false; }} over plain non-volatile
 * fields, reached from inside the A* loop. The quiet failure — a worker publishing
 * {@code dirty = false} without {@code cachedValue} being visible to the main thread, leaving the
 * mob's step height wrong for the rest of the session with nothing in any log — is exactly the
 * failure that mixin exists to prevent.
 *
 * <p>Found by an independent bug hunt after 0.5.0 was published. The lesson worth keeping: a
 * {@code @Redirect} scoped by method name is scoped to <em>one class</em>, and an override in a
 * subclass is a different method that shares the name. {@code require = 1} passed on
 * {@code WalkNodeEvaluator} and proved nothing about the subclass.
 */
@Mixin(AmphibiousNodeEvaluator.class)
public class AmphibiousNodeEvaluatorMixin {

    @Redirect(
        method = "getNeighbors([Lnet/minecraft/world/level/pathfinder/Node;"
            + "Lnet/minecraft/world/level/pathfinder/Node;)I",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;maxUpStep()F"),
        require = 1,
        expect = 1
    )
    private float pathweaver$capturedStepHeight(Mob mob) {
        if (PathWeaverThread.isWorker()) {
            Float captured = PathWeaverThread.workerStepHeight();
            // Same failure direction as the walk mixin: a missing capture falls back to the live
            // call, which is vanilla behaviour including its race. A fabricated step height would be
            // a silent permanent behaviour change; the race is at least the status quo.
            if (captured != null) return captured;
        }
        return mob.maxUpStep();
    }
}
