package git.artdeell.artvk;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

@Environment(EnvType.CLIENT)
public class Vk11GpuTextureView extends GpuTextureView implements Destroyable {
	private final Vk11Device device;
	private final long vkImageView;
	private boolean closed;

	protected Vk11GpuTextureView(final Vk11Device device, final Vk11GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		this.device = device;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean isCubemap = (texture.usage() & 16) != 0;
			VkImageViewCreateInfo imageViewCreateInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
			imageViewCreateInfo.image(texture.vkImage());
			imageViewCreateInfo.viewType(isCubemap ? VK10.VK_IMAGE_VIEW_TYPE_CUBE : VK10.VK_IMAGE_VIEW_TYPE_2D);
			imageViewCreateInfo.format(Vk11Const.toVk(texture.getFormat()));
			VkImageSubresourceRange subresourceRange = imageViewCreateInfo.subresourceRange();
			subresourceRange.aspectMask(texture.getFormat().hasColorAspect() ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
			subresourceRange.baseMipLevel(baseMipLevel);
			subresourceRange.levelCount(mipLevels);
			subresourceRange.baseArrayLayer(0);
			subresourceRange.layerCount(isCubemap ? 6 : 1);
			LongBuffer handlePtr = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateImageView(device.vkDevice(), imageViewCreateInfo, null, handlePtr), "Failed to create VkImageView");
			this.vkImageView = handlePtr.get(0);
			device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_IMAGE_VIEW, this.vkImageView, texture.getLabel());
		}

		texture.addViews();
	}



    void enableTransferMode(MemoryStack memoryStack, VkCommandBuffer currentCommandBuffer) {
        texture().enableTransferMode(memoryStack, currentCommandBuffer, baseMipLevel());
    }

    void disableTransferMode(MemoryStack memoryStack, VkCommandBuffer currentCommandBuffer) {
        texture().postTransferBarrier(memoryStack, currentCommandBuffer);
    }

	@Override
	public void destroy() {
		VK10.vkDestroyImageView(this.device.vkDevice(), this.vkImageView, null);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.device.createCommandEncoder().queueForDestroy(this);
			this.texture().removeViews();
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	public @NotNull Vk11GpuTexture texture() {
		return (Vk11GpuTexture)super.texture();
	}

	public long vkImageView() {
		return this.vkImageView;
	}
}
