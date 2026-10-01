package git.artdeell.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import git.artdeell.ArtVK;
import git.artdeell.artvk.Vk11Device;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GlslCompiler.class)
public class GlslCompilerMixin {
    @WrapOperation(method = "createBaseShaderOptions", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_options_set_target_env(JII)V"))
    private void injectSpirvTarget(long options, int target, int version, Operation<Void> original) {
        int spv = getSpirvTarget();
        Shaderc.shaderc_compile_options_set_target_env(options, target, spv != 0 ? spv : version);
    }

    private int getSpirvTarget() {
        GpuDevice device = RenderSystem.getDevice();
        if(!(device instanceof FrontendGpuDevice backendDevice)) {
            ArtVK.LOGGER.error("GpuDevice isn't an instance of FrontendGpuDevice. Incorrect RenderPearl usage?");
            return 0;
        }
        if(!(backendDevice.backend instanceof Vk11Device artvkDevice)) {
            ArtVK.LOGGER.error("Current GpuDeviceBackend is not from ArtVK. Returning original value");
            return 0;
        }
        return Math.min(artvkDevice.instance().apiTarget, artvkDevice.apiVersion());
    }
}
