package git.artdeell.artvk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11GpuTexture extends GpuTexture implements Destroyable {
	private final Vk11Device device;
	private final long vkImage;
	private final long vmaAllocation;
	private boolean closed = false;
    private int transferLevel = -1;
	private int views = 0;

	public Vk11GpuTexture(
		final Vk11Device device,
		final @GpuTexture.Usage int usage,
		final String label,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.device = device;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack).sType$Default();
			imageCreateInfo.imageType(VK10.VK_IMAGE_TYPE_2D);
			imageCreateInfo.extent().set(width, height, 1);
			imageCreateInfo.mipLevels(mipLevels);
			imageCreateInfo.arrayLayers(depthOrLayers);
			imageCreateInfo.format(Vk11Const.toVk(format));
			imageCreateInfo.tiling(VK10.VK_IMAGE_TILING_OPTIMAL);
			imageCreateInfo.initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			imageCreateInfo.usage(Vk11Const.textureUsageToVk(usage, format));
			imageCreateInfo.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			imageCreateInfo.samples(VK10.VK_SAMPLE_COUNT_1_BIT);
			imageCreateInfo.flags(Vk11Utils.hasAnyBit(usage, 16) ? VK10.VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0);
			VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack);
			allocationCreateInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			LongBuffer imageHandlePtr = stack.callocLong(1);
			PointerBuffer allocationHandlePtr = stack.callocPointer(1);
			Vk11Utils.crashIfFailure(
                    Vma.vmaCreateImage(device.vma(), imageCreateInfo, allocationCreateInfo, imageHandlePtr, allocationHandlePtr, null), "Failed to create image"
			);
			this.vkImage = imageHandlePtr.get(0);
			this.vmaAllocation = allocationHandlePtr.get(0);
			Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
			barrier.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			barrier.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
			barrier.srcAccessMask(0);
			barrier.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
			barrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
			barrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
			barrier.image(this.vkImage);
			VkImageSubresourceRange subresourceRange = barrier.subresourceRange();
			subresourceRange.aspectMask(this.getFormat().hasColorAspect() ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
			subresourceRange.baseMipLevel(0);
			subresourceRange.levelCount(this.getMipLevels());
			subresourceRange.baseArrayLayer(0);
			subresourceRange.layerCount(depthOrLayers);
			VK10.vkCmdPipelineBarrier(device.createCommandEncoder().textureInitCommandBuffer(), VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
			device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_IMAGE, this.vkImage, label);
		}

		this.addViews();
	}

    /**
     * Prepare texture subresource for transfer after rendering
     */
    void enableTransferMode(MemoryStack memoryStack, VkCommandBuffer currentCommandBuffer, int mipLevel) {
        if(transferLevel != -1) return;
        try (MemoryStack stack = memoryStack.push()) {
            VkImageMemoryBarrier.Buffer srcBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            srcBarrier.srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            srcBarrier.dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            srcBarrier.oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            srcBarrier.newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            srcBarrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            srcBarrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            srcBarrier.image(vkImage);
            VkImageSubresourceRange subresourceRange = srcBarrier.subresourceRange();
            subresourceRange.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            subresourceRange.baseMipLevel(mipLevel);
            subresourceRange.levelCount(1);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    currentCommandBuffer,
                    VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, srcBarrier
            );
        }
        transferLevel = mipLevel;
    }

    /**
     * Prepare texture subresource for rendering again after transfer
     */
    void postTransferBarrier(MemoryStack memoryStack, VkCommandBuffer currentCommandBuffer) {
        if(transferLevel == -1) return;
        try (MemoryStack stack = memoryStack.push()) {
            VkImageMemoryBarrier.Buffer genBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
            genBarrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            genBarrier.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
            genBarrier.oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            genBarrier.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            genBarrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            genBarrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
            genBarrier.image(vkImage);
            VkImageSubresourceRange subresourceRange = genBarrier.subresourceRange();
            subresourceRange.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            subresourceRange.baseMipLevel(transferLevel);
            subresourceRange.levelCount(1);
            subresourceRange.baseArrayLayer(0);
            subresourceRange.layerCount(1);
            VK10.vkCmdPipelineBarrier(
                    currentCommandBuffer,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, genBarrier
            );
        }
        transferLevel = -1;
    }

	@Override
	public void destroy() {
		Vma.vmaDestroyImage(this.device.vma(), this.vkImage, this.vmaAllocation);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.removeViews();
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	public void addViews() {
		this.views++;
	}

	public void removeViews() {
		this.views--;
		if (this.views < 0) {
			throw new IllegalStateException("Too many views removed from texture");
		}

		if (this.closed && this.views == 0) {
			this.device.createCommandEncoder().queueForDestroy(this);
		}
	}

	public long vkImage() {
		return this.vkImage;
	}
}
