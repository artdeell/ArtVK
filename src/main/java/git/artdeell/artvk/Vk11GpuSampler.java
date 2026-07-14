package git.artdeell.artvk;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.nio.LongBuffer;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

@Environment(EnvType.CLIENT)
public class Vk11GpuSampler extends GpuSampler implements Destroyable {
	private final long vkSampler;
	private final Vk11Device device;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;

	public Vk11GpuSampler(
		final Vk11Device device,
		final AddressMode addressModeU,
		final AddressMode addressModeV,
		final FilterMode minFilter,
		final FilterMode magFilter,
		final int maxAnisotropy,
		final OptionalDouble maxLod
	) {
        boolean anisotropyEnable = maxAnisotropy > 1 && device.hasAnisotropy;

		this.device = device;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = anisotropyEnable ? maxAnisotropy : 1;
		this.maxLod = maxLod;



		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack).sType$Default();
			createInfo.magFilter(Vk11Const.toVk(magFilter));
			createInfo.minFilter(Vk11Const.toVk(minFilter));
			createInfo.mipmapMode(maxLod.orElse(1000.0) > 0.25 ? VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR : VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST);
			createInfo.addressModeU(Vk11Const.toVk(addressModeU));
			createInfo.addressModeV(Vk11Const.toVk(addressModeV));
			createInfo.mipLodBias(0.0F);
			createInfo.maxLod(Math.max(0.25F, (float)maxLod.orElse(1000.0)));
			createInfo.anisotropyEnable(anisotropyEnable);
			if(anisotropyEnable) createInfo.maxAnisotropy(maxAnisotropy);
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateSampler(device.vkDevice(), createInfo, null, pointer), "Can't create sampler");
			this.vkSampler = pointer.get(0);
		}
	}

	@Override
	public void destroy() {
		VK10.vkDestroySampler(this.device.vkDevice(), this.vkSampler, null);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.device.createCommandEncoder().queueForDestroy(this);
		}
	}

	@Override
	public @NotNull AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public @NotNull AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public @NotNull FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public @NotNull FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public @NotNull OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	public long vkSampler() {
		return this.vkSampler;
	}
}
