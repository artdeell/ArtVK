package git.artdeell.compat.sodium;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import git.artdeell.artvk.Vk11Device;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.caffeinemc.mods.sodium.mixin.core.GpuDeviceAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DrawBackend.class)
public class DrawBackendMixin {

    @Inject(method = "chooseBackend", at = @At(value = "HEAD"), cancellable = true)
    private static void injectBackend(CallbackInfoReturnable<DrawBackend> cir){
        // Basically how Sodium picks the correct draw backend
        // Im still not sure if we want to get device backend same way as Sodium does
        GpuDevice device = RenderSystem.getDevice();
        if(((GpuDeviceAccessor) device).sodium$getBackend() instanceof Vk11Device){
            if(device.getDeviceInfo().features().multiDrawDirectInterleaved())
                cir.setReturnValue(DrawBackend.VK_MULTIDRAW);
            else if(device.getDeviceInfo().features().drawIndirect())
                cir.setReturnValue(DrawBackend.VK_INDIRECT);
            else throw new IllegalStateException("Selected Vulkan device does not support neither multidraw nor indirect draw backends. Sodium might be unsupported on this device");
            cir.cancel();
        }
    }
}
