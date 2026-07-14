package git.artdeell.artvk;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

@Environment(EnvType.CLIENT)
public record Vk11Queue(VkQueue vkQueue, int queueFamilyIndex) {
	public Vk11Queue(final Vk11Device device, final int queueFamilyIndex, final int queueIndex) {
		this(new VkQueue(fetchVkQueue(device, queueFamilyIndex, queueIndex), device.vkDevice()), queueFamilyIndex);
	}

	private static long fetchVkQueue(final Vk11Device device, final int queueFamilyIndex, final int queueIndex) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer queueHandlePtr = stack.callocPointer(1);
			VK10.vkGetDeviceQueue(device.vkDevice(), queueFamilyIndex, queueIndex, queueHandlePtr);
			return queueHandlePtr.get(0);
		}
	}

	public Submission beginSubmit() {
		return new Submission();
	}

	public void waitIdle() {
		VK10.vkQueueWaitIdle(this.vkQueue);
	}

	@Environment(EnvType.CLIENT)
	public class Submission implements AutoCloseable {

        private final LongArrayList waitSemaphores = new LongArrayList();
        private final IntArrayList waitDstStageMasks = new IntArrayList();
        private final LongArrayList signalSemaphores = new LongArrayList();
		private final ReferenceArrayList<VkCommandBuffer> commandBuffers = new ReferenceArrayList<>();

		public Submission waitSemaphore(final long vkSemaphore, final long value, final int stageMask) {
			this.waitSemaphores.add(vkSemaphore);
			this.waitDstStageMasks.add(stageMask);
			return this;
		}

		public Submission signalSemaphore(final long vkSemaphore, final long value, final long stageMask) {
			this.signalSemaphores.add(vkSemaphore);
			return this;
		}

		public Submission executeCommands(final VkCommandBuffer commandBuffer) {
			this.commandBuffers.add(commandBuffer);
			return this;
		}

		@Override
		public void close() {
			this.close(0L);
		}

		public void close(final long fence) {
			if (this.commandBuffers.isEmpty()) {
				return;
			}

			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default();

				if (!waitSemaphores.isEmpty()) {
                    assert waitSemaphores.size() == waitDstStageMasks.size();
                    submitInfo.pWaitSemaphores(stack.longs(waitSemaphores.toLongArray()));
					submitInfo.pWaitDstStageMask(stack.ints(waitDstStageMasks.toIntArray()));
					submitInfo.waitSemaphoreCount(waitSemaphores.size());
				}

                if(!signalSemaphores.isEmpty()) {
                    submitInfo.pSignalSemaphores(stack.longs(signalSemaphores.toLongArray()));
                }

				PointerBuffer cbPtr = stack.callocPointer(this.commandBuffers.size());
				for (VkCommandBuffer cb : this.commandBuffers) {
					cbPtr.put(cb);
				}
				cbPtr.flip();
				submitInfo.pCommandBuffers(cbPtr);

				int result = VK10.vkQueueSubmit(vkQueue, submitInfo, fence);
				if (result < 0) {
					throw new IllegalStateException("Failed to submit VkCommandBuffer: " + Vk11Utils.resultToString(result));
				}
			}
		}
	}
}
