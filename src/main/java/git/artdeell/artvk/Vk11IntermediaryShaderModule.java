package git.artdeell.artvk;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.util.spvc.SpvcReflectedResource.Buffer;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

@Environment(EnvType.CLIENT)
public record Vk11IntermediaryShaderModule(
	String name, @Nullable ByteBuffer spirv, List<SpvUniformBuffer> uniformBuffers, List<SpvSampler> samplers, List<SpvVariable> outputs, List<SpvVariable> inputs
) implements AutoCloseable {
	public static final Vk11IntermediaryShaderModule INVALID = new Vk11IntermediaryShaderModule(
		"invalid", null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()
	);

	public static Vk11IntermediaryShaderModule createFromSpirv(final String filename, final ByteBuffer spirv) throws ShaderCompileException {
		List<SpvUniformBuffer> uniformBuffers = new ArrayList<>();
		List<SpvSampler> samplers = new ArrayList<>();
		List<SpvVariable> outputs = new ArrayList<>();
		List<SpvVariable> inputs = new ArrayList<>();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.callocPointer(1);
			IntBuffer intReturnBuffer = stack.callocInt(1);
			throwIfError(Spvc.spvc_context_create(pointer), "Couldn't create spvc context");
			long context = pointer.get(0);

			try {
				throwIfError(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4, pointer), "Couldn't parse spirv");
				long ir = pointer.get(0);
				throwIfError(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, 1, pointer), "Couldn't create compiler");
				long compiler = pointer.get(0);
				throwIfError(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "Couldn't create resource list");
				long spvcResources = pointer.get(0);
				PointerBuffer countPointer = stack.callocPointer(1);
				throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, pointer, countPointer), "Couldn't list uniform buffers");
				long spvcList = pointer.get(0);
				long spvcCount = countPointer.get(0);
				Buffer resources = SpvcReflectedResource.create(spvcList, (int)spvcCount);

				for (int i = 0; i < spvcCount; i++) {
					SpvcReflectedResource resource = resources.get(i);
					String name = resource.nameString();
				int bindingOffset = getDecorationOffset(compiler, resource, Spv.SpvDecorationBinding, intReturnBuffer);
				uniformBuffers.add(new SpvUniformBuffer(name, bindingOffset));
				}

				throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, countPointer), "Couldn't list sampled images");
				spvcList = pointer.get(0);
				spvcCount = countPointer.get(0);
				resources = SpvcReflectedResource.create(spvcList, (int)spvcCount);

				for (int i = 0; i < spvcCount; i++) {
					SpvcReflectedResource resource = resources.get(i);
					String name = resource.nameString();
				int bindingOffset = getDecorationOffset(compiler, resource, Spv.SpvDecorationBinding, intReturnBuffer);
				long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
					int dimension = Spvc.spvc_type_get_image_dimension(typeHandle);
					samplers.add(new SpvSampler(name, bindingOffset, dimension));
				}

				throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, pointer, countPointer), "Couldn't list output variables");
				spvcList = pointer.get(0);
				spvcCount = countPointer.get(0);
				resources = SpvcReflectedResource.create(spvcList, (int)spvcCount);

				for (int i = 0; i < spvcCount; i++) {
					SpvcReflectedResource resource = resources.get(i);
					String name = resource.nameString();
				int bindingOffset = getDecorationOffset(compiler, resource, Spv.SpvDecorationLocation, intReturnBuffer);
				outputs.add(new SpvVariable(name, bindingOffset));
				}

				throwIfError(Spvc.spvc_resources_get_resource_list_for_type(spvcResources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pointer, countPointer), "Couldn't list input variables");
				spvcList = pointer.get(0);
				spvcCount = countPointer.get(0);
				resources = SpvcReflectedResource.create(spvcList, (int)spvcCount);

				for (int i = 0; i < spvcCount; i++) {
					SpvcReflectedResource resource = resources.get(i);
					String name = resource.nameString();
				int bindingOffset = getDecorationOffset(compiler, resource, Spv.SpvDecorationLocation, intReturnBuffer);
				inputs.add(new SpvVariable(name, bindingOffset));
				}
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}

		IntBuffer spvAsIntBuffer = spirv.asIntBuffer();

		for (int i = 0; i < outputs.size(); i++) {
			spvAsIntBuffer.put(outputs.get(i).locationOffset(), i);
		}

		return new Vk11IntermediaryShaderModule(filename, spirv, uniformBuffers, samplers, outputs, inputs);
	}

	@Override
	public void close() {
		MemoryUtil.memFree(this.spirv);
	}

	public void rebind(final List<String> inputVariables, final List<Vk11BindGroupLayout.Entry> entries) throws ShaderCompileException {
		if (this.spirv == null) {
			throw new IllegalStateException("Attempt to use invalid shader");
		}

		IntBuffer spvAsIntBuffer = this.spirv.asIntBuffer();
		Set<String> remainingInputs = new HashSet<>();
		Set<String> remainingSamplers = new HashSet<>();
		Set<String> remainingUniformBuffers = new HashSet<>();

		for (SpvVariable input : this.inputs) {
			remainingInputs.add(input.name());
		}

		for (SpvUniformBuffer uniformBuffer : this.uniformBuffers) {
			remainingUniformBuffers.add(uniformBuffer.name());
		}

		for (SpvSampler sampler : this.samplers) {
			remainingSamplers.add(sampler.name());
		}

		String previousName = null;
		int attribLocation = 0;

        for (String variableName : inputVariables) {
            SpvVariable inputVariable = this.getInputVariable(variableName);
            if (inputVariable != null) {
                if (!variableName.equals(previousName)) {
                    spvAsIntBuffer.put(inputVariable.locationOffset(), attribLocation);
                    remainingInputs.remove(variableName);
                }

                attribLocation++;
                previousName = variableName;
            }
        }

		for (int i = 0; i < entries.size(); i++) {
			Vk11BindGroupLayout.Entry entry = entries.get(i);
			switch (entry.type()) {
				case UNIFORM_BUFFER:
					SpvUniformBuffer ubo = this.getUniformBuffer(entry.name());
					if (ubo != null) {
						spvAsIntBuffer.put(ubo.bindingOffset(), i);
						remainingUniformBuffers.remove(entry.name());
					}
					break;
				case SAMPLED_IMAGE:
					SpvSampler samplerx = this.getSampler(entry.name());
					if (samplerx != null) {
						if (samplerx.dimensions() != Spv.SpvDim2D && samplerx.dimensions() != Spv.SpvDimCube) {
							throw new ShaderCompileException(
								"Unsupported texture dimensions '" + SpvcUtil.imageDimensionToString(samplerx.dimensions()) + "' for sampler " + entry.name()
							);
						}

						spvAsIntBuffer.put(samplerx.bindingOffset(), i);
						remainingSamplers.remove(entry.name());
					}
					break;
				case TEXEL_BUFFER:
					SpvSampler sampler = this.getSampler(entry.name());
					if (sampler != null) {
						if (sampler.dimensions() != Spv.SpvDimBuffer) {
							throw new ShaderCompileException(
								"Unsupported texel buffer dimensions '" + SpvcUtil.imageDimensionToString(sampler.dimensions()) + "' for sampler " + entry.name()
							);
						}

						spvAsIntBuffer.put(sampler.bindingOffset(), i);
						remainingSamplers.remove(entry.name());
					}
			}
		}

		if (!remainingInputs.isEmpty()) {
			throw new ShaderCompileException("Shader expects input variables which are not being provided: " + remainingInputs);
		}

		if (!remainingUniformBuffers.isEmpty()) {
			throw new ShaderCompileException("Shader expects uniform buffers which are not being provided: " + remainingUniformBuffers);
		}

		if (!remainingSamplers.isEmpty()) {
			throw new ShaderCompileException("Shader expects samplers which are not being provided: " + remainingSamplers);
		}
	}

	public long createVulkanShaderModule(final Vk11Device device) {
		if (this.spirv == null) {
			throw new IllegalStateException("Attempt to use invalid shader");
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(this.spirv);
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(VK10.vkCreateShaderModule(device.vkDevice(), info, null, pointer), "Can't compile " + this.name);
			device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_SHADER_MODULE, pointer.get(0), () -> this.name);
			return pointer.get(0);
		}
	}

	private @Nullable SpvUniformBuffer getUniformBuffer(final String name) {
		for (SpvUniformBuffer ubo : this.uniformBuffers) {
			if (ubo.name().equals(name)) {
				return ubo;
			}
		}

		return null;
	}

	private @Nullable SpvSampler getSampler(final String name) {
		for (SpvSampler sampler : this.samplers) {
			if (sampler.name().equals(name)) {
				return sampler;
			}
		}

		return null;
	}

	private @Nullable SpvVariable getInputVariable(final String name) {
		for (SpvVariable variable : this.inputs) {
			if (variable.name().equals(name)) {
				return variable;
			}
		}

		return null;
	}

	private static void throwIfError(final int result, final String message) throws ShaderCompileException {
		if (result != Spvc.SPVC_SUCCESS) {
			String name = switch (result) {
				case Spvc.SPVC_ERROR_INVALID_ARGUMENT -> "SPVC_ERROR_INVALID_ARGUMENT";
				case Spvc.SPVC_ERROR_OUT_OF_MEMORY -> "SPVC_ERROR_OUT_OF_MEMORY";
				case Spvc.SPVC_ERROR_UNSUPPORTED_SPIRV -> "SPVC_ERROR_UNSUPPORTED_SPIRV";
				case Spvc.SPVC_ERROR_INVALID_SPIRV -> "SPVC_ERROR_INVALID_SPIRV";
				default -> Integer.toString(result);
			};
			throw new ShaderCompileException(message + " (" + name + ")");
		}
	}

	private static int getDecorationOffset(final long compiler, final SpvcReflectedResource resource, final int decoration, final IntBuffer returnBuffer) throws ShaderCompileException {
		if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), decoration, returnBuffer)) {
			throw new ShaderCompileException("Couldn't find byte offset for location decoration of " + resource.nameString());
		} else {
			return returnBuffer.get(0);
		}
	}
}
