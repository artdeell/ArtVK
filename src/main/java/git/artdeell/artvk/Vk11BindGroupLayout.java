package git.artdeell.artvk;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.nio.LongBuffer;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.Buffer;

@Environment(EnvType.CLIENT)
public record Vk11BindGroupLayout(long handle, List<BindGroupLayout.UniformDescription> entries) {
	public static final Vk11BindGroupLayout INVALID_LAYOUT = new Vk11BindGroupLayout(0L, List.of());

	public static Vk11BindGroupLayout create(final Vk11Device device, final List<BindGroupLayout.UniformDescription> entries, final String name) {
		long layoutHandle;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Buffer bindings = VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);

			// The frontend assigns descriptor bindings by their position in this list, so the layout
			// binding index has to match the uniform index handed to RenderPassBackend#setUniform
			for (int i = 0; i < entries.size(); i++) {
				VkDescriptorSetLayoutBinding binding = VkDescriptorSetLayoutBinding.calloc(stack).descriptorType(switch (entries.get(i).type()) {
					case UNIFORM_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
					case COMBINED_IMAGE_SAMPLER -> VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
					case TEXEL_BUFFER -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
				}).descriptorCount(1).binding(i).stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
				bindings.put(binding);
			}

			bindings.flip();
			VkDescriptorSetLayoutCreateInfo setCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateDescriptorSetLayout(device.vkDevice(), setCreateInfo, null, pointer), "Can't set layout for " + name);
			layoutHandle = pointer.get(0);
		}

		return new Vk11BindGroupLayout(layoutHandle, entries);
	}

	public static int countOfType(final List<BindGroupLayout.UniformDescription> entries, final UniformType type) {
		int count = 0;

		for (BindGroupLayout.UniformDescription entry : entries) {
			if (entry.type() == type) {
				count++;
			}
		}

		return count;
	}
}
