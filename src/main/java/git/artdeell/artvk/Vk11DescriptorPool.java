package git.artdeell.artvk;

import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11DescriptorPool implements Destroyable {
	public static final int SETS_PER_FRAME = 1512;
    public static final int SET_PREALLOCATE_COUNT = 504;
    public static final int RECLAIM_THRESHOLD = 504;

	private final Vk11Device device;
    private final PoolObject[] pools = new PoolObject[Vk11CommandEncoder.MAX_SUBMITS_IN_FLIGHT];
    private final LongBuffer bindGroupLayouts;
    private long currentSet;

	public Vk11DescriptorPool(final Vk11Device device, final Vk11CommandEncoder encoder, final Vk11BindGroupLayout layout) {
		this.device = device;
        long bindGroupLayout = layout.handle();
        bindGroupLayouts = MemoryUtil.memAllocLong(SET_PREALLOCATE_COUNT);
        for(int i = 0; i < SET_PREALLOCATE_COUNT; i++) bindGroupLayouts.put(i, bindGroupLayout);

		int uniformBufferCount = 0;
		int sampledImageCount = 0;
		int texelBufferCount = 0;

		for (Vk11BindGroupLayout.Entry entry : layout.entries()) {
			switch (entry.type()) {
				case UNIFORM_BUFFER -> uniformBufferCount++;
				case SAMPLED_IMAGE -> sampledImageCount++;
				case TEXEL_BUFFER -> texelBufferCount++;
			}
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			int poolSizeCount = 0;
			if (uniformBufferCount > 0) poolSizeCount++;
			if (sampledImageCount > 0) poolSizeCount++;
			if (texelBufferCount > 0) poolSizeCount++;

			Buffer poolSizes = VkDescriptorPoolSize.calloc(poolSizeCount, stack);
			int idx = 0;
			if (uniformBufferCount > 0) {
				poolSizes.get(idx++).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(uniformBufferCount * SETS_PER_FRAME);
			}
			if (sampledImageCount > 0) {
				poolSizes.get(idx++).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(sampledImageCount * SETS_PER_FRAME);
			}
			if (texelBufferCount > 0) {
				poolSizes.get(idx++).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(texelBufferCount * SETS_PER_FRAME);
			}

            for(int i = 0; i < pools.length; i++) {
                pools[i] = new PoolObject(stack, poolSizes);
            }

		}
		encoder.registerDescriptorPool(this);
	}

    public boolean isCapacityLow(int frameIndex) {
        return pools[frameIndex].isOverCapacityThreshold();
    }

	public void allocateSet(final int frameIndex) {
        currentSet = pools[frameIndex].takeSet();
	}

	public void resetFrame(final int frameIndex) {
		pools[frameIndex].reset();
	}

	public void updateUniformBuffer(final Vk11Device device, final int binding, final long buffer, final long offset, final long range) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			org.lwjgl.vulkan.VkDescriptorBufferInfo.Buffer bufferInfo = org.lwjgl.vulkan.VkDescriptorBufferInfo.calloc(1, stack);
			bufferInfo.buffer(buffer);
			bufferInfo.offset(offset);
			bufferInfo.range(range);

			org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer write = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(currentSet)
				.dstBinding(binding)
				.dstArrayElement(0)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
				.descriptorCount(1)
				.pBufferInfo(bufferInfo);

			VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
		}
	}

	public void updateSampledImage(final Vk11Device device, final int binding, final long imageView, final long sampler) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			org.lwjgl.vulkan.VkDescriptorImageInfo.Buffer imageInfo = org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack);
			imageInfo.sampler(sampler);
			imageInfo.imageView(imageView);
			imageInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

			org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer write = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(currentSet)
				.dstBinding(binding)
				.dstArrayElement(0)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1)
				.pImageInfo(imageInfo);

			VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
		}
	}

	public void updateTexelBuffer(final Vk11Device device, final int binding, final long bufferView) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer bvPtr = stack.longs(bufferView);

			org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer write = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(currentSet)
				.dstBinding(binding)
				.dstArrayElement(0)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER)
				.descriptorCount(1)
				.pTexelBufferView(bvPtr);

			VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
		}
	}

	public void bind(final org.lwjgl.vulkan.VkCommandBuffer commandBuffer, final long pipelineLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(currentSet), null);
		}
	}
	@Override
	public void destroy() {
		for(PoolObject pool : pools) pool.destroy();
	}

    private class PoolObject {
        protected final long pool;
        protected final LongBuffer sets;
        private int numAllocated;
        private int numUsed;

        public PoolObject(MemoryStack stack, Buffer poolSizes) {
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(SETS_PER_FRAME)
                    .pPoolSizes(poolSizes);
            LongBuffer poolPtr = stack.callocLong(1);
            Vk11Utils.crashIfFailure(VK10.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, poolPtr), "Failed to create descriptor pool");
            pool = poolPtr.get(0);
            sets = MemoryUtil.memCallocLong(SETS_PER_FRAME);
            numAllocated = 0;
            numUsed = 0;
            preallocateMore(SET_PREALLOCATE_COUNT);
        }

        public void preallocateMore(int count) {
            assert count <= SET_PREALLOCATE_COUNT;
            LongBuffer layoutsSlice = MemoryUtil.memSlice(bindGroupLayouts, 0, count);
            LongBuffer outSetsSlice = MemoryUtil.memSlice(sets, numAllocated, count);
            try(MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(pool)
                        .pSetLayouts(layoutsSlice);
                assert allocInfo.descriptorSetCount() == count;
                Vk11Utils.crashIfFailure(VK10.vkAllocateDescriptorSets(device.vkDevice(), allocInfo, outSetsSlice), "Failed to allocate descriptor set");
            }
            numAllocated += count;
        }

        public boolean isOverCapacityThreshold() {
            return numUsed >= RECLAIM_THRESHOLD;
        }

        public void reset() {
            if(numUsed > RECLAIM_THRESHOLD) {

                Vk11Utils.crashIfFailure(VK10.vkResetDescriptorPool(device.vkDevice(), pool, 0), "Failed to reclaim descriptor pool");
                numAllocated = 0;
                preallocateMore(SET_PREALLOCATE_COUNT);
            }
            numUsed = 0;
        }

        public long takeSet() {
            int nextIdx = numUsed++;
            if(nextIdx >= numAllocated) {
                preallocateMore(SET_PREALLOCATE_COUNT);
            }
            return sets.get(nextIdx);
        }

        public void destroy() {
            VK10.vkDestroyDescriptorPool(device.vkDevice(), pool, null);
        }
    }
}
