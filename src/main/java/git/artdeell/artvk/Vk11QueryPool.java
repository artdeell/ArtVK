package git.artdeell.artvk;

import com.mojang.blaze3d.systems.GpuQueryPool;
import java.nio.LongBuffer;
import java.util.OptionalLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

@Environment(EnvType.CLIENT)
public class Vk11QueryPool implements GpuQueryPool, Destroyable {
	private final Vk11Device device;
	private final int size;
	private final long vkQueryPool;

	public Vk11QueryPool(final Vk11Device device, final int size) {
		this.device = device;
		this.size = size;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkQueryPoolCreateInfo createInfo = VkQueryPoolCreateInfo.calloc(stack).sType$Default();
			createInfo.queryType(VK10.VK_QUERY_TYPE_TIMESTAMP);
			createInfo.queryCount(size);
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateQueryPool(device.vkDevice(), createInfo, null, pointer), "Cannot create query pool");
			this.vkQueryPool = pointer.get(0);
		}
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public @NotNull OptionalLong getValue(final int index) {
		return this.getValues(index, 1)[0];
	}

	@Override
	public OptionalLong @NotNull [] getValues(final int index, final int count) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer resultsBuffer = stack.callocLong(count * 2);
			int result = VK10.vkGetQueryPoolResults(this.device.vkDevice(), this.vkQueryPool, index, count, resultsBuffer, 16L, VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
			if (result != VK10.VK_SUCCESS) {
				OptionalLong[] empty = new OptionalLong[count];
				java.util.Arrays.fill(empty, OptionalLong.empty());
				return empty;
			}

			OptionalLong[] values = new OptionalLong[count];
			for (int i = 0; i < count; i++) {
				long availability = resultsBuffer.get(i * 2 + 1);
				if (availability != 0L) {
					values[i] = OptionalLong.of(resultsBuffer.get(i * 2));
				} else {
					values[i] = OptionalLong.empty();
				}
			}
			return values;
		}
	}

	@Override
	public void destroy() {
		VK10.vkDestroyQueryPool(this.device.vkDevice(), this.vkQueryPool, null);
	}

	@Override
	public void close() {
		this.destroy();
	}

	public long vkQueryPool() {
		return this.vkQueryPool;
	}
}
