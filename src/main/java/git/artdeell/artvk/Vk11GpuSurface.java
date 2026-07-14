package git.artdeell.artvk;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkOffset3D;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11GpuSurface implements GpuSurfaceBackend {
	private static final int NO_CURRENT_IMAGE = -1;
	private final Vk11Device device;
	private final VkQueue presentQueue;
	private final long surface;
	private final int swapchainImageFormat;
	private long swapchain;
	private int swapchainWidth;
	private int swapchainHeight;
	private final LongList swapchainImages = new LongArrayList();
	private int currentImageIndex = NO_CURRENT_IMAGE;
	private @Nullable SurfaceException eatenException = null;
	private boolean swapchainSuboptimal;
	private boolean swapchainOutOfDate;
	private final Set<GpuSurface.PresentMode> supportedPresentModes;

	public Vk11GpuSurface(final Vk11Device device, final long windowHandle) {
		this.device = device;
		this.presentQueue = device.graphicsQueue().vkQueue();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handlePtr = stack.longs(0L);
			Vk11Utils.crashIfFailure(
                    GLFWVulkan.glfwCreateWindowSurface(device.instance().vkInstance(), windowHandle, null, handlePtr),
				"Failed to create window surface"
			);
			this.surface = handlePtr.get(0);
			IntBuffer countPtr = stack.callocInt(1);
			Vk11Utils.crashIfFailure(
                    KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.vkDevice().getPhysicalDevice(), this.surface, countPtr, null),
				"Failed to enumerate surface present modes"
			);
			int presentModeCount = countPtr.get(0);
			IntBuffer presentModes = stack.callocInt(presentModeCount);
			Vk11Utils.crashIfFailure(
                    KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.vkDevice().getPhysicalDevice(), this.surface, countPtr, presentModes),
				"Failed to enumerate surface present modes"
			);
			this.supportedPresentModes = Collections.unmodifiableSet(this.convertPresentModes(presentModes));
			IntBuffer formatCount = stack.callocInt(1);
			KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.vkDevice().getPhysicalDevice(), this.surface, formatCount, null);
			Buffer formatsBuffer = VkSurfaceFormatKHR.calloc(formatCount.get(0));
			KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.vkDevice().getPhysicalDevice(), this.surface, formatCount, formatsBuffer);
			this.swapchainImageFormat = this.pickSwapchainSurfaceFormat(formatsBuffer).format();
			MemoryUtil.memFree(formatsBuffer);
		}
	}

	private Set<GpuSurface.PresentMode> convertPresentModes(final IntBuffer presentModes) {
		Set<GpuSurface.PresentMode> result = EnumSet.noneOf(GpuSurface.PresentMode.class);

		for (int i = 0; i < presentModes.limit(); i++) {
			int mode = presentModes.get(i);
			switch (mode) {
			case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR:
				result.add(GpuSurface.PresentMode.IMMEDIATE);
				break;
			case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR:
				result.add(GpuSurface.PresentMode.MAILBOX);
				break;
			case KHRSurface.VK_PRESENT_MODE_FIFO_KHR:
				result.add(GpuSurface.PresentMode.FIFO);
				break;
			case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR:
				result.add(GpuSurface.PresentMode.FIFO_RELAXED);
			}
		}

		return result;
	}

	@Override
	public @NotNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return this.supportedPresentModes;
	}

	public VkSurfaceFormatKHR pickSwapchainSurfaceFormat(final Buffer formats) {
		for (VkSurfaceFormatKHR format : formats) {
			if (format.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
				&& (format.format() == VK10.VK_FORMAT_R8G8B8A8_UNORM || format.format() == VK10.VK_FORMAT_B8G8R8A8_UNORM)) {
				return format;
			}
		}

		throw new IllegalStateException("Could not find compatible swapchain format");
	}

	public static void throwIfFailure(final int result, final String message) throws SurfaceException {
		if (result < 0) {
			throw new SurfaceException(Vk11Utils.resultToString(result) + ": " + message);
		}
	}

	@Override
	public void close() {
		this.destroySwapchain();
		KHRSurface.vkDestroySurfaceKHR(this.device.instance().vkInstance(), this.surface, null);
	}

	private void destroySwapchain() {
		if (this.swapchain != 0L) {
			this.device.graphicsQueue().waitIdle();
			KHRSwapchain.vkDestroySwapchainKHR(this.device.vkDevice(), this.swapchain, null);
			this.swapchain = 0L;
		}
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		this.destroySwapchain();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSurfaceCapabilitiesKHR surfaceCapabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
			throwIfFailure(
				KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(this.device.vkDevice().getPhysicalDevice(), this.surface, surfaceCapabilities),
				"Failed to get surface capabilities"
			);
			VkExtent2D minExtent = surfaceCapabilities.minImageExtent();
			VkExtent2D maxExtent = surfaceCapabilities.maxImageExtent();
			if (config.width() < minExtent.width() || maxExtent.width() < config.width() || config.height() < minExtent.height() || maxExtent.height() < config.height()) {
				throw new SurfaceException(
					String.format(
						Locale.ROOT,
						"Requested swapchain extent (%d x %d) not within allowed extent: min(%d x %d) max(%d x %d)",
						config.width(),
						config.height(),
						minExtent.width(),
						minExtent.height(),
						maxExtent.width(),
						maxExtent.height()
					)
				);
			}

			VkSwapchainCreateInfoKHR swapchainCreateInfo = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default();
			swapchainCreateInfo.surface(this.surface);
			int currentPresentMode = Vk11Const.toVk(config.presentMode());
			swapchainCreateInfo.minImageCount(Math.max(3, surfaceCapabilities.minImageCount()));
			swapchainCreateInfo.imageFormat(this.swapchainImageFormat);
			swapchainCreateInfo.imageColorSpace(KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
			swapchainCreateInfo.imageExtent(VkExtent2D.calloc(stack).set(config.width(), config.height()));
			swapchainCreateInfo.imageArrayLayers(1);
			swapchainCreateInfo.imageUsage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
			swapchainCreateInfo.imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			swapchainCreateInfo.preTransform(surfaceCapabilities.currentTransform());
			swapchainCreateInfo.compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
			swapchainCreateInfo.presentMode(currentPresentMode);
			swapchainCreateInfo.clipped(true);
			LongBuffer swapchainPtr = stack.callocLong(1);
			throwIfFailure(KHRSwapchain.vkCreateSwapchainKHR(this.device.vkDevice(), swapchainCreateInfo, null, swapchainPtr), "Failed to create Swapchain");
			this.swapchain = swapchainPtr.get(0);
			IntBuffer imageCountPtr = stack.callocInt(1);
			throwIfFailure(KHRSwapchain.vkGetSwapchainImagesKHR(this.device.vkDevice(), this.swapchain, imageCountPtr, null), "Failed to get swapchain image count");
			int swapchainImageCount = imageCountPtr.get(0);
			LongBuffer swapchainImagesPtr = stack.callocLong(swapchainImageCount);
			throwIfFailure(
				KHRSwapchain.vkGetSwapchainImagesKHR(this.device.vkDevice(), this.swapchain, imageCountPtr, swapchainImagesPtr), "Failed to get swapchain images"
			);
			this.swapchainImages.clear();

			for (int i = 0; i < swapchainImageCount; i++) {
				this.swapchainImages.add(swapchainImagesPtr.get(i));
			}

			this.swapchainSuboptimal = false;
			this.swapchainOutOfDate = false;
			this.swapchainWidth = config.width();
			this.swapchainHeight = config.height();
		} catch (SurfaceException e) {
			this.swapchainSuboptimal = true;
			this.swapchainOutOfDate = true;
			throw e;
		}
	}

	@Override
	public boolean isSuboptimal() {
		return this.swapchainSuboptimal;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.eatenException != null) {
			SurfaceException toThrow = this.eatenException;
			this.eatenException = null;
			throw new SurfaceException(toThrow);
		}

		if (this.swapchainOutOfDate) {
			throw new IllegalStateException("Attempt to use out of date swapchain");
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer frameIndexPtr = stack.callocInt(1);
			frameIndexPtr.put(0, NO_CURRENT_IMAGE);
			Vk11CommandEncoder commandEncoder = this.device.createCommandEncoder();
			long acquireSemaphore = commandEncoder.acquireSemaphore();
			int result = KHRSwapchain.vkAcquireNextImageKHR(this.device.vkDevice(), this.swapchain, 50000000000L, acquireSemaphore, 0L, frameIndexPtr);
			if (result == VK10.VK_TIMEOUT) {
				throw new IllegalStateException("GPU timeout attempting to acquire next frame");
			}

			this.currentImageIndex = frameIndexPtr.get(0);
			if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
				this.swapchainSuboptimal = true;
				this.swapchainOutOfDate = true;
				throw new SurfaceException("Failed to acquire image, swapchain out of date");
			}

			if (result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
				this.swapchainSuboptimal = true;
			} else {
				Vk11Utils.crashIfFailure(result, "Failed to acquire image");
			}
		}
	}

	@Override
	public void blitFromTexture(final @NotNull CommandEncoderBackend commandEncoder, final @NotNull GpuTextureView textureView) {
		if (this.swapchainOutOfDate) {
			throw new IllegalStateException("Attempt to use out of date swapchain");
		}

		assert this.currentImageIndex != NO_CURRENT_IMAGE;
        Vk11GpuTextureView vTextureView = (Vk11GpuTextureView) textureView;
		Vk11CommandEncoder vk11CommandEncoder = (Vk11CommandEncoder)commandEncoder;
		VkCommandBuffer currentCommandBuffer = vk11CommandEncoder.commandBuffer();
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		MemoryStack tStack = MemoryStack.stackGet();

        vTextureView.enableTransferMode(tStack, currentCommandBuffer);

		// Transition swapchain image to TRANSFER_DST
		try (MemoryStack stack = tStack.push()) {
			VkImageMemoryBarrier.Buffer imageBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
			imageBarrier.srcAccessMask(0);
			imageBarrier.dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
			imageBarrier.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			imageBarrier.newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			imageBarrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
			imageBarrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
			imageBarrier.image(swapchainImage);
			VkImageSubresourceRange subresourceRange = imageBarrier.subresourceRange();
			subresourceRange.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			subresourceRange.baseMipLevel(0);
			subresourceRange.levelCount(1);
			subresourceRange.baseArrayLayer(0);
			subresourceRange.layerCount(1);
			VK10.vkCmdPipelineBarrier(currentCommandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);
		}

		// Blit
		try (MemoryStack stack = tStack.push()) {
			int copyWidth = Math.min(this.swapchainWidth, textureView.getWidth(0));
			int copyHeight = Math.min(this.swapchainHeight, textureView.getHeight(0));
			VkOffset3D.Buffer srcOffsets = VkOffset3D.calloc(2, stack);
			srcOffsets.x(0).y(0).z(0);
			srcOffsets.position(1);
			srcOffsets.x(copyWidth).y(copyHeight).z(1);
			srcOffsets.position(0);
			VkOffset3D.Buffer dstOffsets = VkOffset3D.calloc(2, stack);
			dstOffsets.x(0).y(copyHeight).z(0);
			dstOffsets.position(1);
			dstOffsets.x(copyWidth).y(0).z(1);
			dstOffsets.position(0);
			VkImageSubresourceLayers srcSubresource = VkImageSubresourceLayers.calloc(stack);
			srcSubresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			srcSubresource.mipLevel(textureView.baseMipLevel());
			srcSubresource.baseArrayLayer(0);
			srcSubresource.layerCount(1);
			VkImageSubresourceLayers dstSubresource = VkImageSubresourceLayers.calloc(stack);
			dstSubresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			dstSubresource.mipLevel(0);
			dstSubresource.baseArrayLayer(0);
			dstSubresource.layerCount(1);
			VkImageBlit.Buffer blitRegion = VkImageBlit.calloc(1, stack);
			blitRegion.srcSubresource(srcSubresource);
			blitRegion.srcOffsets(srcOffsets);
			blitRegion.dstSubresource(dstSubresource);
			blitRegion.dstOffsets(dstOffsets);
			VK10.vkCmdBlitImage(
				currentCommandBuffer,
				((Vk11GpuTexture)textureView.texture()).vkImage(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
				swapchainImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
				blitRegion, VK10.VK_FILTER_LINEAR
			);
		}

		// Transition swapchain image to PRESENT_SRC
		try (MemoryStack stack = tStack.push()) {
			VkImageMemoryBarrier.Buffer imageBarrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default();
			imageBarrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
			imageBarrier.dstAccessMask(0);
			imageBarrier.oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
			imageBarrier.newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
			imageBarrier.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
			imageBarrier.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
			imageBarrier.image(swapchainImage);
			VkImageSubresourceRange subresourceRange = imageBarrier.subresourceRange();
			subresourceRange.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			subresourceRange.baseMipLevel(0);
			subresourceRange.levelCount(1);
			subresourceRange.baseArrayLayer(0);
			subresourceRange.layerCount(1);
			VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
			memBarrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
			memBarrier.dstAccessMask(0);
			VK10.vkCmdPipelineBarrier(
				currentCommandBuffer,
				VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				0,
				memBarrier,
				null,
				imageBarrier
			);
		}

		vk11CommandEncoder.waitSemaphore(vk11CommandEncoder.acquireSemaphore(), 0L, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        vk11CommandEncoder.signalSemaphore(vk11CommandEncoder.presentSemaphore(), 0L, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
	}

	@Override
	public void present() {
		if (this.swapchainOutOfDate) {
			throw new IllegalStateException("Attempt to use out of date swapchain");
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default();
			presentInfo.pWaitSemaphores(stack.longs(this.device.createCommandEncoder().submittedPresentSemaphore()));
			presentInfo.swapchainCount(1);
			presentInfo.pSwapchains(stack.longs(this.swapchain));
			presentInfo.pImageIndices(stack.ints(this.currentImageIndex));
			this.currentImageIndex = NO_CURRENT_IMAGE;
			int result = KHRSwapchain.vkQueuePresentKHR(this.presentQueue, presentInfo);
			if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
				this.swapchainSuboptimal = true;
				this.swapchainOutOfDate = true;
				this.eatenException = new SurfaceException("Failed to present image, swapchain out of date");
			} else if (result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
				this.swapchainSuboptimal = true;
			} else {
				Vk11Utils.crashIfFailure(result, "Failed to present image");
			}
		}
	}
}
