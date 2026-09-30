package git.artdeell.compat.sodium;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import git.artdeell.artvk.Vk11Backend;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DrawBackend.class)
public class DrawBackendMixin {

    @Inject(method = "chooseBackend", at = @At(value = "HEAD"), cancellable = true)
    private static void injectBackend(CallbackInfoReturnable<DrawBackend> cir){
        // Basically how Sodium picks the correct draw backend
        // TODO: XXX ABI BREAKAGE - Sodium's GpuDeviceAccessor returns com.mojang.blaze3d.systems.GpuDeviceBackend,
        // which moved to com.mojang.renderpearl.backend.api, so we can no longer ask the frontend for its backend
        // and instead track whether this backend is the live one.
        GpuDevice device = RenderSystem.getDevice();
        if (Vk11Backend.deviceActive) {
            if(device.getDeviceInfo().features().multiDrawDirectInterleaved())
                cir.setReturnValue(DrawBackend.VK_MULTIDRAW);
            // Upstream Sodium uses MDI here, but we will check for generic indirect draw
            // MDI is emulated on the backend side
            else if(device.getDeviceInfo().features().drawIndirect())
                cir.setReturnValue(DrawBackend.VK_INDIRECT);
            else throw new IllegalStateException("Selected Vulkan device does not support neither multidraw nor indirect draw backends. Sodium might be unsupported on this device");
            cir.cancel();
        }
    }
}
