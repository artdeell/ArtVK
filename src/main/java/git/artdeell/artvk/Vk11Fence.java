package git.artdeell.artvk;

import java.nio.LongBuffer;

import com.mojang.blaze3d.buffers.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFenceCreateInfo;

@Environment(EnvType.CLIENT)
public class Vk11Fence implements Destroyable, GpuFence {
	private final Vk11Device device;
	private final long vkFence;
    private boolean completed = false;

	public Vk11Fence(final Vk11Device device, final boolean signaled) {
		this.device = device;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFenceCreateInfo createInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
			if (signaled) {
				createInfo.flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT);
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

    protected void reset() {
        VK10.vkResetFences(device.vkDevice(), vkFence);
        completed = false;
    }

    public long vkFence() {
        return vkFence;
    }

    @Override
    public void close() {
        // TODO: Implement this? Is it necessary if the fences should only be reset in submit()?
    }

    @Override
    public boolean awaitCompletion(long timeoutNS) {
        if(completed) return true;
        int result = VK10.vkWaitForFences(device.vkDevice(), vkFence, true, timeoutNS);
        if(result == VK10.VK_TIMEOUT) return false;
        Vk11Utils.crashIfFailure(result, "Failed to wait for fence");
        completed = true;
        return true;
    }

    public void waitForever() {
        while(true) if(awaitCompletion(1_000_000_000)) break;
    }

    public void autoWait() {
        waitForever();
        reset();
    }
}
