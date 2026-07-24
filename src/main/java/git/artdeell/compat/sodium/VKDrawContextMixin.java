package git.artdeell.compat.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import git.artdeell.artvk.Vk11RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(VKDrawContext.class)
public class VKDrawContextMixin {
    @Shadow
    protected VkCommandBuffer cmdBuf;
    @Shadow
    protected long layout;

    @Inject(method = "setContext", at = @At(value = "FIELD",
            target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/context/VKDrawContext;pass:Lcom/mojang/blaze3d/systems/RenderPass;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER), cancellable = true)
    public void injectValues(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) throws NoSuchFieldException, IllegalAccessException {
        Field backendPass = pass.getClass().getDeclaredField("backend");
        backendPass.setAccessible(true);
        Vk11RenderPass vpass = (Vk11RenderPass) backendPass.get(pass);
        cmdBuf = vpass.getCommandBuffer();
        layout = vpass.getPipeline().pipelineLayout();
        ci.cancel();
    }

    @Inject(method = "updateData", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK13;nvkCmdPushConstants(Lorg/lwjgl/vulkan/VkCommandBuffer;JIIIJ)V"), cancellable = true)
    public void injectPushConstant(RenderRegion region, CameraTransform camera, CallbackInfo ci, @Local(name = "memory") long memory, @Local(name = "stack") MemoryStack stack){
        VK11.nvkCmdPushConstants(this.cmdBuf, this.layout, VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, DrawContext.PUSH_CONSTANT_RANGE, memory);
        stack.close();
        ci.cancel();
    }
}
