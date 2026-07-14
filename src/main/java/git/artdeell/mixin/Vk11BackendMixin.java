package git.artdeell.mixin;

import com.mojang.blaze3d.systems.GpuBackend;
import git.artdeell.artvk.Vk11Backend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
public class Vk11BackendMixin {
	@Inject(at = @At("RETURN"), method = "getBackendsToTry", cancellable = true)
	private void injectVk11Backend(CallbackInfoReturnable<GpuBackend[]> cir) {
		GpuBackend[] original = cir.getReturnValue();
		GpuBackend[] result = new GpuBackend[original.length + 1];
		result[0] = new Vk11Backend();
		System.arraycopy(original, 0, result, 1, original.length);
		cir.setReturnValue(result);
	}
}
