package git.artdeell.artvk;

import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;

@Environment(EnvType.CLIENT)
public class Vk11CommandPool implements Destroyable {
	private static final int BUFFER_ALLOC_COUNT = 32;
    private static final int BUFFER_ARRAY_CAPACITY = 512;
	private final Vk11Device device;
	private final long commandPool;
	private PointerBuffer allocatedBuffers;

	public Vk11CommandPool(final Vk11Device device, final Vk11Queue queue) {
		this.device = device;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandPoolCreateInfo commandPoolCreateInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default();
			commandPoolCreateInfo.flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
			commandPoolCreateInfo.queueFamilyIndex(queue.queueFamilyIndex());
			LongBuffer commandPoolHandlePtr = stack.callocLong(1);
			Vk11Utils.crashIfFailure(
                    VK10.vkCreateCommandPool(device.vkDevice(), commandPoolCreateInfo, null, commandPoolHandlePtr), "Failed to create VkCommandPool"
			);
			this.commandPool = commandPoolHandlePtr.get(0);
		}

		this.allocatedBuffers = MemoryUtil.memAllocPointer(BUFFER_ARRAY_CAPACITY);
		this.allocatedBuffers.limit(0);
	}

	@Override
	public void destroy() {
		this.release();
		this.allocatedBuffers.free();
		VK10.vkDestroyCommandPool(this.device.vkDevice(), this.commandPool, null);
	}

	public void release() {
		this.allocatedBuffers.rewind();
		if (this.allocatedBuffers.hasRemaining()) {
			VK10.vkFreeCommandBuffers(this.device.vkDevice(), this.commandPool, this.allocatedBuffers);
			this.allocatedBuffers.clear();
			MemoryUtil.memSet(this.allocatedBuffers, 0);
		}

		VK10.vkResetCommandPool(this.device.vkDevice(), this.commandPool, VK10.VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT);
		this.allocatedBuffers.limit(0);
	}

	public void reset() {
		VK10.vkResetCommandPool(this.device.vkDevice(), this.commandPool, 0);
		this.allocatedBuffers.rewind();
	}

	private void allocateMoreBuffers() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (this.allocatedBuffers.capacity() - this.allocatedBuffers.limit() < BUFFER_ALLOC_COUNT) {
				PointerBuffer newBuffer = MemoryUtil.memRealloc(this.allocatedBuffers, this.allocatedBuffers.capacity() + BUFFER_ARRAY_CAPACITY);
				newBuffer.limit(this.allocatedBuffers.limit());
				this.allocatedBuffers = newBuffer;
			}

			VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default();
			allocateInfo.commandPool(this.commandPool);
			allocateInfo.level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY);
			allocateInfo.commandBufferCount(BUFFER_ALLOC_COUNT);
			this.allocatedBuffers.limit(this.allocatedBuffers.limit() + BUFFER_ALLOC_COUNT);
			PointerBuffer buffers = this.allocatedBuffers.slice(0, BUFFER_ALLOC_COUNT);
			Vk11Utils.crashIfFailure(VK10.vkAllocateCommandBuffers(this.device.vkDevice(), allocateInfo, buffers), "Failed to allocate VkCommandBuffers");
		}
	}

	public VkCommandBuffer allocateBuffer() {
		if (!this.allocatedBuffers.hasRemaining()) {
			this.allocateMoreBuffers();
		}

		return new VkCommandBuffer(this.allocatedBuffers.get(), this.device.vkDevice());
	}
}
