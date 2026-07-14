package git.artdeell.artvk;

import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFenceCreateInfo;

@Environment(EnvType.CLIENT)
public class Vk11Fence implements Destroyable {
	private final Vk11Device device;
	private final long vkFence;

	public Vk11Fence(final Vk11Device device, final boolean signaled) {
		this.device = device;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFenceCreateInfo createInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
			if (signaled) {
				createInfo.flags(1);
			}
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateFence(device.vkDevice(), createInfo, null, pointer), "Failed to create VkFence");
			this.vkFence = pointer.get(0);
		}
	}

	@Override
	public void destroy() {
		VK10.vkDestroyFence(device.vkDevice(), vkFence, null);
	}

	public long vkFence() {
		return this.vkFence;
	}
}
