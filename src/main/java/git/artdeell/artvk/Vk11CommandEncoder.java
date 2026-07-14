package git.artdeell.artvk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

@Environment(EnvType.CLIENT)
public class Vk11CommandEncoder implements CommandEncoderBackend, Destroyable {
	public static final int MAX_SUBMITS_IN_FLIGHT = 3;
	private final Vk11Device device;
	private final Vk11TransientMemory transientMemory;
	private final long[] acquireSemaphores = new long[MAX_SUBMITS_IN_FLIGHT];
	private final long[] presentSemaphores = new long[MAX_SUBMITS_IN_FLIGHT];
	private final Vk11Fence[] frameFences = new Vk11Fence[MAX_SUBMITS_IN_FLIGHT];
	private int currentSubmitIndex = 0;
	private Vk11Queue.Submission submissionBuilder;
	private final DestructionQueue<Destroyable> destroyQueue = new DestructionQueue<>(MAX_SUBMITS_IN_FLIGHT, Destroyable::destroy);
	private final Vk11CommandPool[] commandPools = new Vk11CommandPool[MAX_SUBMITS_IN_FLIGHT];
	private @Nullable VkCommandBuffer currentCommandBuffer;
	private @Nullable Vk11RenderPass currentRenderPass;
	private long submittedPresentSemaphore;
	private final java.util.ArrayList<Vk11DescriptorPool> descriptorPools = new java.util.ArrayList<>();

	public Vk11CommandEncoder(final Vk11Device device) {
		this.device = device;
		this.transientMemory = new Vk11TransientMemory(device, this);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSemaphoreCreateInfo semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			LongBuffer semaphoreHandlePtr = stack.callocLong(1);

			for (int i = 0; i < this.acquireSemaphores.length; i++) {
				Vk11Utils.crashIfFailure(
                        VK10.vkCreateSemaphore(device.vkDevice(), semaphoreCreateInfo, null, semaphoreHandlePtr), "Failed to create acquire VkSemaphore"
				);
				this.acquireSemaphores[i] = semaphoreHandlePtr.get(0);
			}

			for (int i = 0; i < this.presentSemaphores.length; i++) {
				Vk11Utils.crashIfFailure(
                        VK10.vkCreateSemaphore(device.vkDevice(), semaphoreCreateInfo, null, semaphoreHandlePtr), "Failed to create present VkSemaphore"
				);
				this.presentSemaphores[i] = semaphoreHandlePtr.get(0);
			}
		}

		for (int i = 0; i < MAX_SUBMITS_IN_FLIGHT; i++) {
			this.frameFences[i] = new Vk11Fence(device, true);
			this.commandPools[i] = new Vk11CommandPool(device, device.graphicsQueue());
		}

		this.submissionBuilder = device.graphicsQueue().beginSubmit();
		this.transientMemory.beginSubmit();
	}

	@Override
	public void destroy() {
		this.transientMemory.endSubmit();
		this.submissionBuilder.close();
		this.device.graphicsQueue().waitIdle();
		this.destroyQueue.close();
		this.transientMemory.destroy();
		this.destroyQueue.close();

		for (int i = 0; i < MAX_SUBMITS_IN_FLIGHT; i++) {
			this.commandPools[i].destroy();
			this.frameFences[i].destroy();
			VK10.vkDestroySemaphore(this.device.vkDevice(), this.acquireSemaphores[i], null);
			VK10.vkDestroySemaphore(this.device.vkDevice(), this.presentSemaphores[i], null);
		}
	}

	public void queueForDestroy(final Destroyable destroyable) {
		this.destroyQueue.add(destroyable);
	}

	private Vk11CommandPool currentCommandPool() {
		return this.commandPools[this.currentSubmitIndex];
	}

	public VkCommandBuffer allocateAndBeginTransientCommandBuffer() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBuffer commandBuffer = this.currentCommandPool().allocateBuffer();
			VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			beginInfo.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			Vk11Utils.crashIfFailure(VK10.vkBeginCommandBuffer(commandBuffer, beginInfo), "Failed to begin VkCommandBuffer");
			return commandBuffer;
		}
	}

	public VkCommandBuffer commandBuffer() {
		if (this.currentCommandBuffer != null) {
			return this.currentCommandBuffer;
		}

		if (this.currentRenderPass != null) {
			throw new IllegalStateException("Cannot start command buffer while inside RenderPass");
		}

		this.currentCommandBuffer = this.allocateAndBeginTransientCommandBuffer();
		this.submissionBuilder.executeCommands(this.currentCommandBuffer);
		return this.currentCommandBuffer;
	}

	VkCommandBuffer textureInitCommandBuffer() {
		return this.commandBuffer();
	}

	private void endCommandBuffer() {
		if (this.currentCommandBuffer != null) {
			if (this.currentRenderPass != null) {
				throw new IllegalStateException("Cannot end command buffer while inside RenderPass");
			}

			Vk11Utils.crashIfFailure(VK10.vkEndCommandBuffer(this.currentCommandBuffer), "Failed to end VkCommandBuffer");
			this.currentCommandBuffer = null;
		}
	}

	public void waitSemaphore(final long vkSemaphore, final long value, final int stageMask) {
		if (this.currentRenderPass != null) {
			throw new IllegalStateException("Cannot add semaphore operation while inside RenderPass");
		}

		this.submissionBuilder.waitSemaphore(vkSemaphore, value, stageMask);
	}

	public void execute(final VkCommandBuffer commandBuffer) {
		if (this.currentRenderPass != null) {
			throw new IllegalStateException("Cannot execute command buffer while inside RenderPass");
		}

		this.submissionBuilder.executeCommands(commandBuffer);
	}

	public void signalSemaphore(final long vkSemaphore, final long value, final long stageMask) {
		if (this.currentRenderPass != null) {
			throw new IllegalStateException("Cannot add semaphore operation while inside RenderPass");
		}

		this.submissionBuilder.signalSemaphore(vkSemaphore, value, stageMask);
	}

	private void memoryBarrier(final MemoryStack stack) {
		memoryBarrier(this.commandBuffer(), stack);
	}

	public static void memoryBarrier(final VkCommandBuffer commandBuffer, final MemoryStack stack) {
		VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
		barrier.srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
		barrier.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
		VK10.vkCmdPipelineBarrier(
			commandBuffer,
			VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			0,
			barrier,
			null,
			null
		);
	}

    public boolean waitForFences(long... fences) {
        int result = VK10.VK_TIMEOUT;
        while(result == VK10.VK_TIMEOUT) {
            result = VK10.vkWaitForFences(device.vkDevice(), fences, true, 1_000_000_000L);
        }
        Vk11Utils.crashIfFailure(result, "fence wait failed");
        return true;
    }

    public void resetFences(long... fences) {
        Vk11Utils.crashIfFailure(VK10.vkResetFences(device.vkDevice(), fences), "fence reset failed");
    }

    public void requireFencesComplete(long... fences) {
        if(!waitForFences(fences)) throw new IllegalStateException("Failed to wait for completion fence");
        resetFences(fences);
    }

    @Override
	public void submit() {
		this.endCommandBuffer();
		this.transientMemory.endSubmit();
		this.submittedPresentSemaphore = this.presentSemaphores[this.currentSubmitIndex];
        long lastFence = this.frameFences[this.currentSubmitIndex].vkFence();

        requireFencesComplete(lastFence);

        this.submissionBuilder.close(lastFence);
		this.submissionBuilder = this.device.graphicsQueue().beginSubmit();

        currentSubmitIndex = (currentSubmitIndex + 1) % MAX_SUBMITS_IN_FLIGHT;

        waitForFences(frameFences[currentSubmitIndex].vkFence());

        for (Vk11DescriptorPool pool : this.descriptorPools) {
            pool.resetFrame(this.currentSubmitIndex);
        }

		this.currentCommandPool().reset();
		this.destroyQueue.rotate();
		this.transientMemory.beginSubmit();
	}

	@Override
	public @NotNull TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public @NotNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {

		List<RenderPassDescriptor.Attachment<@NotNull Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
		Vk11GpuTextureView[] colorTextures = new Vk11GpuTextureView[colorAttachments.size()];

        MemoryStack transStack = MemoryStack.stackGet();
		for (int i = 0; i < colorAttachments.size(); i++) {
			RenderPassDescriptor.Attachment<@NotNull Optional<Vector4fc>> attachment = colorAttachments.get(i);
            if(attachment == null) {
                colorTextures[i] = null;
                continue;
            }
            Vk11GpuTextureView textureView = (Vk11GpuTextureView)attachment.textureView();
            textureView.disableTransferMode(transStack, currentCommandBuffer);
			colorTextures[i] = textureView;
		}

		RenderPassDescriptor.Attachment<@NotNull OptionalDouble> depthAttachment = descriptor.depthAttachment();
		this.device.instance().debug().beginDebugGroup(this.commandBuffer(), descriptor.label());

		int width = 0;
		int height = 0;
		if (!colorAttachments.isEmpty()) {
			for (RenderPassDescriptor.Attachment<@NotNull Optional<Vector4fc>> colorAttachment : colorAttachments) {
				if (colorAttachment != null) {
					GpuTextureView colorTexture = colorAttachment.textureView();
					width = colorTexture.getWidth(0);
					height = colorTexture.getHeight(0);
				}
			}
		} else if (depthAttachment != null) {
			width = depthAttachment.textureView().getWidth(0);
			height = depthAttachment.textureView().getHeight(0);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Build color formats list
			List<Integer> colorFormats = new java.util.ArrayList<>();
			for (Vk11GpuTextureView cv : colorTextures) {
				if (cv != null) {
					colorFormats.add(Vk11Const.toVk(cv.texture().getFormat()));
				} else {
					colorFormats.add(VK10.VK_FORMAT_UNDEFINED);
				}
			}

			boolean hasDepth = depthAttachment != null;
			int depthFormat = hasDepth ? Vk11Const.toVk(depthAttachment.textureView().texture().getFormat()) : VK10.VK_FORMAT_UNDEFINED;

			long renderPass = this.device.renderPassCache().getOrCreateRenderPass(colorFormats, hasDepth, depthFormat);

			// Build image views array for framebuffer
			int viewCount = colorTextures.length + (hasDepth ? 1 : 0);
			long[] imageViews = new long[viewCount];
			for (int i = 0; i < colorTextures.length; i++) {
				imageViews[i] = colorTextures[i] != null ? colorTextures[i].vkImageView() : 0L;
			}
			if (hasDepth) {
				imageViews[colorTextures.length] = ((Vk11GpuTextureView)depthAttachment.textureView()).vkImageView();
			}

			long framebuffer = this.device.renderPassCache().getOrCreateFramebuffer(renderPass, width, height, imageViews);

			// Build clear values
			org.lwjgl.vulkan.VkClearValue.Buffer clearValues = VkClearValue.calloc(viewCount, stack);
			for (int i = 0; i < colorTextures.length; i++) {
				if (colorTextures[i] != null) {
					RenderPassDescriptor.Attachment<@NotNull Optional<Vector4fc>> attachment = colorAttachments.get(i);
					if (attachment.clearValue().isPresent()) {
						Vector4fc color = attachment.clearValue().get();
                        System.out.println("Clear value: "+color);
						Vk11Utils.putArgb(clearValues.get(i).color(), color);
					}
				}
			}
			if (hasDepth) {
				OptionalDouble clearDepth = depthAttachment.clearValue();
				if (clearDepth.isPresent()) {
					clearValues.get(colorTextures.length).depthStencil(VkClearDepthStencilValue.calloc(stack).depth((float)clearDepth.getAsDouble()));
				}
			}

			// Begin render pass
			org.lwjgl.vulkan.VkRenderPassBeginInfo renderPassBeginInfo = org.lwjgl.vulkan.VkRenderPassBeginInfo.calloc(stack).sType$Default();
			renderPassBeginInfo.renderPass(renderPass);
			renderPassBeginInfo.framebuffer(framebuffer);
			renderPassBeginInfo.renderArea().offset().set(descriptor.renderArea != null ? descriptor.renderArea.x() : 0, descriptor.renderArea != null ? descriptor.renderArea.y() : 0);
			renderPassBeginInfo.renderArea().extent().set(width, height);
			renderPassBeginInfo.pClearValues(clearValues);
			renderPassBeginInfo.clearValueCount(viewCount);

			VK10.vkCmdBeginRenderPass(this.commandBuffer(), renderPassBeginInfo, VK10.VK_SUBPASS_CONTENTS_INLINE);
		}

		this.currentRenderPass = new Vk11RenderPass(
			this.device, this, this.commandBuffer(), descriptor.renderArea, width, height, depthAttachment != null, descriptor.label()
		);
		return this.currentRenderPass;
	}

    private boolean poolCapacityLow() {
        for(Vk11DescriptorPool pool : descriptorPools) {
            if(pool.isCapacityLow(currentSubmitIndex)) return true;
        }
        return false;
    }

	@Override
	public void submitRenderPass() {
		if (this.currentRenderPass == null) {
			throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
		}

        boolean poolCapacityLow = poolCapacityLow();

		VK10.vkCmdEndRenderPass(this.commandBuffer());
		this.device.instance().debug().endDebugGroup(this.commandBuffer());
		this.currentRenderPass = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			this.memoryBarrier(stack);
		}

        if(poolCapacityLow) {
            System.out.println("Descriptor pool capacity is low, submitting out of order");
            submit();
        }
	}

	private void clearColorTextureUnsynced(final MemoryStack stack, final GpuTexture colorTexture, final Vector4fc clearColor) {
		org.lwjgl.vulkan.VkClearColorValue vkClearColor = Vk11Utils.putArgb(org.lwjgl.vulkan.VkClearColorValue.calloc(stack), clearColor);
		VkImageSubresourceRange subresourceRange = VkImageSubresourceRange.calloc(stack);
		subresourceRange.baseMipLevel(0);
		subresourceRange.levelCount(colorTexture.getMipLevels());
		subresourceRange.baseArrayLayer(0);
		subresourceRange.layerCount(1);
		subresourceRange.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
		VK10.vkCmdClearColorImage(this.commandBuffer(), ((Vk11GpuTexture)colorTexture).vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, vkClearColor, subresourceRange);
	}

	public void clearDepthTextureUnsynced(final MemoryStack stack, final GpuTexture depthTexture, final double clearDepth) {
		VkClearDepthStencilValue vkClearDepth = VkClearDepthStencilValue.calloc(stack).depth((float)clearDepth);
		VkImageSubresourceRange subresourceRange = VkImageSubresourceRange.calloc(stack);
		subresourceRange.baseMipLevel(0);
		subresourceRange.levelCount(depthTexture.getMipLevels());
		subresourceRange.baseArrayLayer(0);
		subresourceRange.layerCount(1);
		subresourceRange.aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
		VK10.vkCmdClearDepthStencilImage(this.commandBuffer(), ((Vk11GpuTexture)depthTexture).vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, vkClearDepth, subresourceRange);
	}

	@Override
	public void clearColorTexture(final @NotNull GpuTexture colorTexture, final @NotNull Vector4fc clearColor) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
            ((Vk11GpuTexture) colorTexture).postTransferBarrier(stack, this.commandBuffer());
			this.clearColorTextureUnsynced(stack, colorTexture, clearColor);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void clearColorAndDepthTextures(
            final @NotNull GpuTexture colorTexture,
            final @NotNull Vector4fc clearColor,
            final @NotNull GpuTexture depthTexture,
            final double clearDepth
    ) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
            ((Vk11GpuTexture) colorTexture).postTransferBarrier(stack, this.commandBuffer());
			this.clearColorTextureUnsynced(stack, colorTexture, clearColor);
			this.clearDepthTextureUnsynced(stack, depthTexture, clearDepth);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void clearColorAndDepthTextures(
		final GpuTexture colorTexture,
		final @NotNull Vector4fc clearColor,
		final @NotNull GpuTexture depthTexture,
		final double clearDepth,
		final int regionX,
		final int regionY,
		final int regionWidth,
		final int regionHeight
	) {
		try (
			GpuTextureView colorTextureView = this.device.createTextureView(colorTexture);
			GpuTextureView depthTextureView = this.device.createTextureView(depthTexture);
			MemoryStack stack = MemoryStack.stackPush()
		) {
            ((Vk11GpuTexture) colorTexture).postTransferBarrier(stack, this.commandBuffer());

			this.createRenderPass(
				RenderPassDescriptor.create(() -> "ClearColorDepthTextures")
					.withColorAttachment(colorTextureView)
					.withDepthAttachment(depthTextureView)
					.withRenderArea(new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)))
			);
			assert this.currentRenderPass != null;
			org.lwjgl.vulkan.VkClearRect.Buffer rects = org.lwjgl.vulkan.VkClearRect.calloc(1, stack);
			rects.baseArrayLayer(0);
			rects.layerCount(1);
			rects.rect().offset().set(regionX, regionY);
			rects.rect().extent().set(regionWidth, regionHeight);
			org.lwjgl.vulkan.VkClearAttachment.Buffer attachments = org.lwjgl.vulkan.VkClearAttachment.calloc(2, stack);
			VkClearValue colorClearValue = VkClearValue.calloc(stack);
			Vk11Utils.putArgb(colorClearValue.color(), clearColor);
			attachments.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			attachments.clearValue(colorClearValue);
			VkClearValue depthClearValue = VkClearValue.calloc(stack);
			VkClearDepthStencilValue clearValue = depthClearValue.depthStencil();
			clearValue.depth((float)clearDepth);
			attachments.position(1);
			attachments.aspectMask(VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
			attachments.clearValue(depthClearValue);
			attachments.position(0);
			VK10.vkCmdClearAttachments(this.commandBuffer(), attachments, rects);
			this.submitRenderPass();
		}
	}

	@Override
	public void clearDepthTexture(final @NotNull GpuTexture depthTexture, final double clearDepth) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			this.clearDepthTextureUnsynced(stack, depthTexture, clearDepth);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice destination, final @NotNull ByteBuffer data) {
		Vk11GpuBuffer destBuffer = (Vk11GpuBuffer)destination.buffer();
		GpuBufferSlice stagingBuffer = this.transientMemory.uploadStaging(data, 1L, 16);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCopy.Buffer regions = VkBufferCopy.calloc(1, stack)
				.srcOffset(stagingBuffer.offset())
				.dstOffset(destination.offset())
				.size(data.remaining());
			VK10.vkCmdCopyBuffer(this.commandBuffer(), ((Vk11GpuBuffer)stagingBuffer.buffer()).vkBuffer(), destBuffer.vkBuffer(), regions);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCopy.Buffer copyInfo = VkBufferCopy.calloc(1, stack);
			copyInfo.srcOffset(source.offset());
			copyInfo.dstOffset(target.offset());
			copyInfo.size(source.length());
			VK10.vkCmdCopyBuffer(this.commandBuffer(), ((Vk11GpuBuffer)source.buffer()).vkBuffer(), ((Vk11GpuBuffer)target.buffer()).vkBuffer(), copyInfo);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void writeToTexture(
		final @NotNull GpuTexture destination,
		final @NotNull ByteBuffer source,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height
	) {
		GpuBufferSlice stagingBuffer = this.transientMemory.uploadStaging(source, 1L, 16);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.bufferOffset(stagingBuffer.offset());
			region.bufferRowLength(width);
			region.bufferImageHeight(height);
			VkImageSubresourceLayers imageSubresource = region.imageSubresource();
			imageSubresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			imageSubresource.mipLevel(mipLevel);
			imageSubresource.baseArrayLayer(depthOrLayer);
			imageSubresource.layerCount(1);
			region.imageOffset().set(destX, destY, 0);
			region.imageExtent().set(width, height, 1);
			VK10.vkCmdCopyBufferToImage(this.commandBuffer(), ((Vk11GpuBuffer)stagingBuffer.buffer()).vkBuffer(), ((Vk11GpuTexture)destination).vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, region);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void copyBufferToTexture(
		final GpuBufferSlice source,
		final int sourceX,
		final int sourceY,
		final int sourceWidth,
		final int sourceHeight,
		final GpuTexture destination,
		final int destinationX,
		final int destinationY,
		final int copyWidth,
		final int copyHeight,
		final int mipLevel,
		final int arrayLayer
	) {
		int texelSize = destination.getFormat().blockSize();
		long skipTexels = sourceX + (long)sourceY * sourceWidth;
		long skipBytes = skipTexels * texelSize;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.bufferOffset(source.offset() + skipBytes);
			region.bufferRowLength(sourceWidth);
			region.bufferImageHeight(sourceHeight);
			VkImageSubresourceLayers imageSubresource = region.imageSubresource();
			imageSubresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
			imageSubresource.mipLevel(mipLevel);
			imageSubresource.baseArrayLayer(arrayLayer);
			imageSubresource.layerCount(1);
			region.imageOffset().set(destinationX, destinationY, 0);
			region.imageExtent().set(copyWidth, copyHeight, 1);
			VK10.vkCmdCopyBufferToImage(this.commandBuffer(), ((Vk11GpuBuffer)source.buffer()).vkBuffer(), ((Vk11GpuTexture)destination).vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, region);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public void copyTextureToBuffer(
            final @NotNull GpuTexture source,
            final @NotNull GpuBuffer destination,
            final long offset,
            final @NotNull Runnable callback,
            final int mipLevel
    ) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(
		final GpuTexture source,
		final @NotNull GpuBuffer destination,
		final long offset,
		final @NotNull Runnable callback,
		final int mipLevel,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
			copy.bufferOffset(offset);
			VkImageSubresourceLayers subresource = copy.imageSubresource();
			subresource.aspectMask(Vk11Const.formatAspectMask(source.getFormat()));
			subresource.mipLevel(mipLevel);
			subresource.baseArrayLayer(0);
			subresource.layerCount(1);
			copy.imageOffset().set(x, y, 0);
			copy.imageExtent().set(width, height, 1);
			copy.bufferRowLength(width);
			copy.bufferImageHeight(height);
			VK10.vkCmdCopyImageToBuffer(this.commandBuffer(), ((Vk11GpuTexture)source).vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, ((Vk11GpuBuffer)destination).vkBuffer(), copy);
			this.memoryBarrier(stack);
		}

		this.queueForDestroy(callback::run);
	}

	@Override
	public void copyTextureToTexture(
		final GpuTexture source,
		final @NotNull GpuTexture destination,
		final int mipLevel,
		final int destX,
		final int destY,
		final int sourceX,
		final int sourceY,
		final int width,
		final int height
	) {
		Vk11GpuTexture vk11Src = (Vk11GpuTexture)source;
		Vk11GpuTexture vk11Dst = (Vk11GpuTexture)destination;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageSubresourceLayers subresourceLayers = VkImageSubresourceLayers.calloc(stack);
			subresourceLayers.mipLevel(mipLevel);
			subresourceLayers.baseArrayLayer(0);
			subresourceLayers.layerCount(1);
			subresourceLayers.aspectMask(Vk11Const.formatAspectMask(source.getFormat()));
			VkImageCopy.Buffer regions = VkImageCopy.calloc(1, stack);
			regions.srcOffset().set(sourceX, sourceY, 0);
			regions.dstOffset().set(destX, destY, 0);
			regions.extent().set(width, height, 1);
			regions.srcSubresource(subresourceLayers);
			regions.dstSubresource(subresourceLayers);
			VK10.vkCmdCopyImage(this.commandBuffer(), vk11Src.vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, vk11Dst.vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, regions);
			this.memoryBarrier(stack);
		}
	}

	@Override
	public @NotNull GpuFence createFence() {
        return new GpuFence() {
            // Don't gaf
            // Maybe TODO: implement fence for previous frame's submit
            @Override
            public void close() {}

            @Override
            public boolean awaitCompletion(long timeoutNS) {
                return true;
            }
        };
	}

	@Override
	public void writeTimestamp(final @NotNull GpuQueryPool pool, final int index) {
		long queryPool = ((Vk11QueryPool)pool).vkQueryPool();
		VK10.vkCmdResetQueryPool(this.commandBuffer(), queryPool, index, 1);
		VK10.vkCmdWriteTimestamp(this.commandBuffer(), VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, index);
	}

	public long getTimestampNow() {
		try (
			MemoryStack stack = MemoryStack.stackPush();
			Vk11QueryPool queryPool = (Vk11QueryPool)this.device.createTimestampQueryPool(1)
		) {
			VkCommandBuffer commandBuffer = this.allocateAndBeginTransientCommandBuffer();
			VK10.vkCmdWriteTimestamp(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool.vkQueryPool(), 0);
			Vk11Utils.crashIfFailure(VK10.vkEndCommandBuffer(commandBuffer), "Failed to end VkCommandBuffer");

			try (Vk11Queue.Submission submit = this.device.graphicsQueue().beginSubmit()) {
				submit.executeCommands(commandBuffer);
			}

			LongBuffer timestampPtr = stack.callocLong(1);
			Vk11Utils.crashIfFailure(
                    VK10.vkGetQueryPoolResults(this.device.vkDevice(), queryPool.vkQueryPool(), 0, 1, timestampPtr, 0L, VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT), "Cannot fetch current timestamp"
			);
			return timestampPtr.get(0);
		}
	}

	public long acquireSemaphore() {
        assert acquireSemaphores.length == MAX_SUBMITS_IN_FLIGHT;
		return this.acquireSemaphores[this.currentSubmitIndex];
	}

	public long presentSemaphore() {
        assert presentSemaphores.length == MAX_SUBMITS_IN_FLIGHT;
		return this.presentSemaphores[this.currentSubmitIndex];
	}

	public long submittedPresentSemaphore() {
		return this.submittedPresentSemaphore;
	}

	public int currentSubmitIndex() {
		return this.currentSubmitIndex;
	}

	public void registerDescriptorPool(final Vk11DescriptorPool pool) {
		this.descriptorPools.add(pool);
	}
}
