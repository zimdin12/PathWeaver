package dev.pathweaver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spiders' chase route, which has never been dispatched off-thread.
 *
 * <p>This is the third instance of one mistake, so it is written down rather than merely fixed. A
 * mixin transforms <em>its target class only</em>. {@code PathNavigationMixin} injects at
 * {@code PathNavigation.moveTo(Entity, double)} to mark that a genuine movement request is in
 * progress — the flag every dispatch decision keys on. {@code WallClimberNavigation}
 * <strong>overrides</strong> that method (verified at its offsets 0-35: it calls
 * {@code createPath(Entity, 0)}, then {@code moveTo(Path, double)}, and never calls {@code super}).
 * An override is a different method that happens to share a name, so the inject never ran for it.
 *
 * <p>The consequence: every spider chasing a player resolved its path synchronously on the server
 * thread, while {@code /pathweaver mobs} counted spiders as eligible. The mod reported coverage it
 * did not have — the same shape as the banner bug 0.5.4 exists to fix, one layer down.
 *
 * <p>0.5.1 was the first instance ({@code AmphibiousNodeEvaluator} overriding {@code getNeighbors},
 * so four families kept racing an attribute). 0.5.2 was the second. The general fix is the
 * reachability work on the 0.6 roadmap; this is the specific one.
 *
 * <p>Nothing else is needed. The inner {@code createPath} this override reaches is
 * {@code PathNavigation}'s own, which {@code PathNavigationMixin} already intercepts — it was
 * declining to dispatch only because the movement flag was never set. Marking the request makes
 * spiders take the identical path as every other mob, including every gate and the synchronous
 * fallback.
 */
@Mixin(WallClimberNavigation.class)
public class WallClimberNavigationMixin {

    @Inject(method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"),
            require = 1, expect = 1)
    private void pathweaver$captureWallClimberEntitySpeed(Entity entity, double speed,
                                                           CallbackInfoReturnable<Boolean> cir) {
        ((dev.pathweaver.duck.PWNavigation) this).pathweaver$beginMovementRequest(speed);
    }

    /**
     * Mark the override's own {@code createPath} call as a movement request.
     *
     * <p>The speed capture above is necessary and was not sufficient — a first version shipped only
     * that and a game test caught it. Dispatch keys on the request DEPTH, which the base mixin raises
     * with a wrap around the {@code createPath} call sites inside {@code PathNavigation}'s movement
     * methods. This override calls {@code createPath(Entity, 0)} from its own body, which no base
     * wrap covers, so the inner call still read as a query and stayed on the server thread.
     */
    @WrapOperation(
        method = "moveTo(Lnet/minecraft/world/entity/Entity;D)Z",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/WallClimberNavigation;"
                + "createPath(Lnet/minecraft/world/entity/Entity;I)"
                + "Lnet/minecraft/world/level/pathfinder/Path;"),
        require = 1,
        expect = 1
    )
    private Path pathweaver$armWallClimberEntityMove(WallClimberNavigation instance, Entity target,
                                                      int reachRange, Operation<Path> original) {
        dev.pathweaver.duck.PWNavigation nav = (dev.pathweaver.duck.PWNavigation) this;
        nav.pathweaver$enterMovementRequest();
        try {
            return original.call(instance, target, reachRange);
        } finally {
            nav.pathweaver$exitMovementRequest();
        }
    }
}
