package git.artdeell.artvk;

import java.util.HashMap;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

@Environment(EnvType.CLIENT)
public class Vk11RenderPassCache implements Destroyable {
	private final Vk11Device device;
	private final HashMap<Long, Long> renderPassCache = new HashMap<>();
	private final HashMap<Long, Long> framebufferCache = new HashMap<>();

	public Vk11RenderPassCache(final Vk11Device device) {
		this.device = device;
	}

	public long getOrCreateRenderPass(
		final List<Integer> colorFormats,
		final boolean hasDepth,
		final int depthFormat
	) {
		long key = computeRenderPassKey(colorFormats, hasDepth, depthFormat);
		Long cached = this.renderPassCache.get(key);
		if (cached != null) {
			return cached;
		}

		long renderPass = createRenderPass(colorFormats, hasDepth, depthFormat);
		this.renderPassCache.put(key, renderPass);
		return renderPass;
	}

	public long getOrCreateFramebuffer(
		final long renderPass,
		final int width,
		final int height,
		final long[] imageViews
	) {
		long key = computeFramebufferKey(renderPass, width, height, imageViews);
		Long cached = this.framebufferCache.get(key);
		if (cached != null) {
			return cached;
		}

		long framebuffer = createFramebuffer(renderPass, width, height, imageViews);
		this.framebufferCache.put(key, framebuffer);
		return framebuffer;
	}

	private long createRenderPass(final List<Integer> colorFormats, final boolean hasDepth, final int depthFormat) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int attachmentCount = colorFormats.size() + (hasDepth ? 1 : 0);
			VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);
			List<VkAttachmentReference> colorRefs = new ArrayList<>();
			VkAttachmentReference depthRef = null;

			for (int i = 0; i < colorFormats.size(); i++) {
				VkAttachmentDescription att = attachments.get(i);
				att.format(colorFormats.get(i));
				att.samples(VK10.VK_SAMPLE_COUNT_1_BIT);
				att.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD);
				att.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
				att.stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR);
				att.stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
				att.initialLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
				att.finalLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

				VkAttachmentReference colorRef = VkAttachmentReference.calloc(stack);
				colorRef.attachment(i);
				colorRef.layout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
				colorRefs.add(colorRef);
			}

			if (hasDepth) {
				VkAttachmentDescription att = attachments.get(colorFormats.size());
				att.format(depthFormat);
				att.samples(VK10.VK_SAMPLE_COUNT_1_BIT);
				att.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD);
				att.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
				att.stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR);
				att.stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
				att.initialLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
				att.finalLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

				depthRef = VkAttachmentReference.calloc(stack);
				depthRef.attachment(colorFormats.size());
				depthRef.layout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
			}

			VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
			subpass.pipelineBindPoint(VK10.VK_PIPELINE_BIND_POINT_GRAPHICS);
			if (!colorRefs.isEmpty()) {
				VkAttachmentReference.Buffer colorRefArray = VkAttachmentReference.calloc(colorRefs.size(), stack);
				for (VkAttachmentReference ref : colorRefs) {
					colorRefArray.put(ref);
				}
				colorRefArray.flip();
				subpass.pColorAttachments(colorRefArray);
                subpass.colorAttachmentCount(colorRefs.size());
			}
			if (depthRef != null) {
				subpass.pDepthStencilAttachment(depthRef);
			}

			VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
			dependency.srcSubpass(VK10.VK_SUBPASS_EXTERNAL);
			dependency.dstSubpass(0);
			dependency.srcStageMask(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
			dependency.dstStageMask(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
			dependency.srcAccessMask(0);
			dependency.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);

			VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
				.sType$Default()
				.pAttachments(attachments)
				.pSubpasses(subpass)
				.pDependencies(dependency);

			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateRenderPass(device.vkDevice(), renderPassInfo, null, pointer), "Failed to create VkRenderPass");
			return pointer.get(0);
		}
	}

	private long createFramebuffer(final long renderPass, final int width, final int height, final long[] imageViews) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
				.sType$Default()
				.renderPass(renderPass)
				.width(width)
				.height(height)
				.layers(1);

			if (imageViews.length > 0) {
				framebufferInfo.attachmentCount(imageViews.length);
				framebufferInfo.pAttachments(stack.longs(imageViews));
			}

			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateFramebuffer(device.vkDevice(), framebufferInfo, null, pointer), "Failed to create VkFramebuffer");
			return pointer.get(0);
		}
	}

	private static long computeRenderPassKey(final List<Integer> colorFormats, final boolean hasDepth, final int depthFormat) {
		long key = 0;
		for (int fmt : colorFormats) {
			key = key * 31 + fmt;
		}
		key = key * 31 + (hasDepth ? 1 : 0);
		if (hasDepth) {
			key = key * 31 + depthFormat;
		}
		return key;
	}

	private static long computeFramebufferKey(final long renderPass, final int width, final int height, final long[] imageViews) {
		long key = renderPass;
		key = key * 31 + width;
		key = key * 31 + height;
		for (long iv : imageViews) {
			key = key * 31 + iv;
		}
		return key;
	}

	public void invalidateFramebuffers() {
		for (long fb : this.framebufferCache.values()) {
			VK10.vkDestroyFramebuffer(device.vkDevice(), fb, null);
		}
		this.framebufferCache.clear();
	}

	@Override
	public void destroy() {
		for (long rp : this.renderPassCache.values()) {
			VK10.vkDestroyRenderPass(device.vkDevice(), rp, null);
		}
		this.renderPassCache.clear();
		this.invalidateFramebuffers();
	}
}
