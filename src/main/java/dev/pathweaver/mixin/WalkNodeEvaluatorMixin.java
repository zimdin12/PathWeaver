package dev.pathweaver.mixin;

import dev.pathweaver.async.PathWeaverThread;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the live mob's step-height attribute off the worker thread.
 *
 * <p>{@code Mob.maxUpStep()} reads like an accessor and is not one. Verified against the 26.1.2
 * bytecode, it resolves to {@code LivingEntity.getAttributeValue(STEP_HEIGHT)} →
 * {@code AttributeInstance.getValue()}, which is a read-modify-write:
 *
 * <pre>{@code
 * if (this.dirty) {
 *     this.cachedValue = calculateValue();   // walks the modifier collections
 *     this.dirty = false;
 * }
 * return this.cachedValue;
 * }</pre>
 *
 * <p>{@code dirty} and {@code cachedValue} are plain, non-volatile fields on shared live entity
 * state. {@code WalkNodeEvaluator} calls {@code maxUpStep()} from {@code getNeighbors} and again
 * from {@code getMobJumpHeight} via {@code tryJumpOn} — both inside the A* loop, so a single search
 * can reach it hundreds of times.
 *
 * <p>This is the one place the design's central claim was wrong. The claim is that all live-mob
 * writes live in {@code prepare()}/{@code done()} and the search between them only reads; that holds
 * for every explicit mutation in all six evaluators, and missed a write hidden behind an attribute
 * getter.
 *
 * <p>Two failures, and the quiet one is worse. Loudly: the worker runs {@code calculateValue()}
 * while the main thread adds or removes a modifier, and the search dies on a concurrent-modification
 * fault — noisy and unexplained, but recoverable, since the request is discarded and the mob falls
 * back to synchronous pathing. Quietly: the worker publishes {@code dirty = false} without
 * {@code cachedValue} being visible to the main thread, and the mob's step height is wrong for the
 * rest of the session — it stops climbing blocks it should, or climbs ones it should not, with
 * nothing in any log to connect it to pathfinding.
 *
 * <p>A dispatch-time pre-resolve is not enough. It clears {@code dirty} at that instant, but the
 * request is in flight for at least a tick, and equipment, a potion effect or a mod touching the
 * attribute re-dirties it inside that window.
 *
 * <p>So the worker is given a value instead of a call, exactly as {@link FlyNodeEvaluatorMixin} does
 * for {@code Mob.getRandom()}. Vanilla resolves this attribute once per search anyway, microseconds
 * after the prologue that PathWeaver already runs on the main thread, so the captured value is what
 * a synchronous search on that tick would have seen. On the main thread nothing is captured and the
 * live call runs, so synchronous searches are bit-for-bit vanilla.
 */
@Mixin(WalkNodeEvaluator.class)
public class WalkNodeEvaluatorMixin {

    @Redirect(
        method = {
            "getNeighbors([Lnet/minecraft/world/level/pathfinder/Node;"
                + "Lnet/minecraft/world/level/pathfinder/Node;)I",
            "getMobJumpHeight()D"
        },
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;maxUpStep()F"),
        require = 2,
        expect = 2
    )
    private float pathweaver$capturedStepHeight(Mob mob) {
        if (PathWeaverThread.isWorker()) {
            Float captured = PathWeaverThread.workerStepHeight();
            // Null means the dispatch path stopped supplying one. Falling back to the live call is
            // vanilla behaviour including its race, which is the safer of the two failures: the race
            // is the status quo, a fabricated step height is a silent permanent behaviour change.
            if (captured != null) return captured;
        }
        return mob.maxUpStep();
    }

    /**
     * The same hazard by a different route, found only after 0.5.1 shipped.
     *
     * <p>{@code tryFindFirstGroundNodeBelow} is reached from {@code getNeighbors} via
     * {@code findAcceptedNode}, so this is inside the A* loop. {@code Mob.getMaxFallDistance()} reads
     * {@code getMaxHealth()} when the mob has a target, and that is
     * {@code getAttributeValue(MAX_HEALTH)} → {@code AttributeInstance.getValue()} — the identical
     * read-modify-write over plain non-volatile fields that {@code maxUpStep()} hides. It is declared
     * by this class, so every admitted family reaches it, and it fires exactly when a mob is chasing
     * something.
     */
    @Redirect(
        method = "tryFindFirstGroundNodeBelow(III)Lnet/minecraft/world/level/pathfinder/Node;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;getMaxFallDistance()I"),
        require = 1,
        expect = 1
    )
    private int pathweaver$capturedMaxFallDistance(Mob mob) {
        if (PathWeaverThread.isWorker()) {
            Integer captured = PathWeaverThread.workerMaxFallDistance();
            if (captured != null) return captured;
        }
        return mob.getMaxFallDistance();
    }
}
