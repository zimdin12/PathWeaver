package dev.pathweaver.frameprobe.mixin;

import dev.pathweaver.frameprobe.FrameProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftFrameMixin {
    @Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 1)
    private void pwprobe$frame(boolean advanceGameTime, CallbackInfo ci) {
        FrameProbe.Frames.frame();
    }
}
