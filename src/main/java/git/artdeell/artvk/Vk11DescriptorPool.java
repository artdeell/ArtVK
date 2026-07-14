package git.artdeell.artvk;

import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11DescriptorPool implements Destroyable {
	public static final int SETS_PER_FRAME = 1512;
	private static final int TOTAL_SETS = Vk11CommandEncoder.MAX_SUBMITS_IN_FLIGHT * SETS_PER_FRAME;

	private final Vk11Device device;
	private final long pool;
	private final long[] sets = new long[TOTAL_SETS];
	private final int[] frameSetIndex = new int[Vk11CommandEncoder.MAX_SUBMITS_IN_FLIGHT];

	public Vk11DescriptorPool(final Vk11Device device, final Vk11CommandEncoder encoder, final Vk11BindGroupLayout layout) {
		this.device = device;

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
				poolSizes.get(idx).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(uniformBufferCount * TOTAL_SETS);
				idx++;
			}
			if (sampledImageCount > 0) {
				poolSizes.get(idx).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(sampledImageCount * TOTAL_SETS);
				idx++;
			}
			if (texelBufferCount > 0) {
				poolSizes.get(idx).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(texelBufferCount * TOTAL_SETS);
			}

			VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
				.sType$Default()
				.maxSets(TOTAL_SETS)
				.pPoolSizes(poolSizes);
			LongBuffer poolPtr = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, poolPtr), "Failed to create descriptor pool");
			this.pool = poolPtr.get(0);

			LongBuffer setLayouts = stack.longs(layout.handle());
			LongBuffer setPtr = stack.callocLong(1);
			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
				.sType$Default()
				.descriptorPool(this.pool)
				.pSetLayouts(setLayouts);
			for (int i = 0; i < TOTAL_SETS; i++) {
				Vk11Utils.crashIfFailure(VK10.vkAllocateDescriptorSets(device.vkDevice(), allocInfo, setPtr), "Failed to allocate descriptor set");
				this.sets[i] = setPtr.get(0);
			}
		}
		encoder.registerDescriptorPool(this);
	}

    public boolean isCapacityLow(int frameIndex) {
        int offset = this.frameSetIndex[frameIndex];
        if(offset >= SETS_PER_FRAME / 2) {
            System.out.println(toString()+" over capacity threshold: "+SETS_PER_FRAME +" used: "+offset);
            return true;
        }
        return false;
    }

	public int allocateSet(final int frameIndex) {
		int base = frameIndex * SETS_PER_FRAME;
		int offset = this.frameSetIndex[frameIndex]++;
		if (offset >= SETS_PER_FRAME) {
			throw new IllegalStateException("Descriptor set limit exceeded for frame " + frameIndex + " (max " + SETS_PER_FRAME + " per frame)");
		}
		return base + offset;
	}

	public void resetFrame(final int frameIndex) {
		this.frameSetIndex[frameIndex] = 0;
	}

	public void updateUniformBuffer(final Vk11Device device, final int setIndex, final int binding, final long buffer, final long offset, final long range) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			org.lwjgl.vulkan.VkDescriptorBufferInfo.Buffer bufferInfo = org.lwjgl.vulkan.VkDescriptorBufferInfo.calloc(1, stack);
			bufferInfo.buffer(buffer);
			bufferInfo.offset(offset);
			bufferInfo.range(range);

			org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer write = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(this.sets[setIndex])
				.dstBinding(binding)
				.dstArrayElement(0)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
				.descriptorCount(1)
				.pBufferInfo(bufferInfo);

			VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
		}
	}

	public void updateSampledImage(final Vk11Device device, final int setIndex, final int binding, final long imageView, final long sampler) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			org.lwjgl.vulkan.VkDescriptorImageInfo.Buffer imageInfo = org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack);
			imageInfo.sampler(sampler);
			imageInfo.imageView(imageView);
			imageInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

			org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer write = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(this.sets[setIndex])
				.dstBinding(binding)
				.dstArrayElement(0)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1)
				.pImageInfo(imageInfo);

			VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
		}
	}

	public void updateTexelBuffer(final Vk11Device device, final int setIndex, final int binding, final long bufferView) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer bvPtr = stack.longs(bufferView);

			org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer write = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
			write.get(0)
				.sType$Default()
				.dstSet(this.sets[setIndex])
				.dstBinding(binding)
				.dstArrayElement(0)
				.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER)
				.descriptorCount(1)
				.pTexelBufferView(bvPtr);

			VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
		}
	}

	public void bind(final org.lwjgl.vulkan.VkCommandBuffer commandBuffer, final long pipelineLayout, final int setIndex) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(this.sets[setIndex]), null);
		}
	}

	public long descriptorSet(final int setIndex) {
		return this.sets[setIndex];
	}

	@Override
	public void destroy() {
		VK10.vkDestroyDescriptorPool(device.vkDevice(), pool, null);
	}
}
