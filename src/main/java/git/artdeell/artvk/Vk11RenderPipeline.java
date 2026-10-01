package git.artdeell.artvk;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11RenderPipeline implements BackendRenderPipeline, Destroyable {
	public static final long INVALID_PIPELINE = 0L;

	private final Vk11Device device;
	private final long withDepthPipeline;
	private final long withoutDepthPipeline;
	private final long pipelineLayout;
	private final Vk11BindGroupLayout layout;
	// Created on demand, see descriptorPool()
	private @Nullable Vk11DescriptorPool descriptorPool;
	private final LongList shaderModules;
	private boolean closed = false;

	public Vk11RenderPipeline(
		final Vk11Device device,
		final long withDepthPipeline,
		final long withoutDepthPipeline,
		final long pipelineLayout,
		final Vk11BindGroupLayout layout,
		final LongList shaderModules
	) {
		this.device = device;
		this.withDepthPipeline = withDepthPipeline;
		this.withoutDepthPipeline = withoutDepthPipeline;
		this.pipelineLayout = pipelineLayout;
		this.layout = layout;
		this.shaderModules = shaderModules;
	}

	public long withDepthPipeline() {
		return this.withDepthPipeline;
	}

	public long withoutDepthPipeline() {
		return this.withoutDepthPipeline;
	}

	public long pipelineLayout() {
		return this.pipelineLayout;
	}

	public Vk11BindGroupLayout layout() {
		return this.layout;
	}

	public Vk11DescriptorPool descriptorPool() {
		// TODO: XXX ABI BREAKAGE - compile() runs on Util.backgroundExecutor(), but this pool preallocates three
		// times SETS_PER_FRAME descriptor sets and registers itself with the command encoder, which only belongs
		// on the render thread. Build it on first use, exactly where 26.2 built it.
		Vk11DescriptorPool pool = this.descriptorPool;

		if (pool == null) {
			pool = new Vk11DescriptorPool(this.device, this.device.createCommandEncoder(), this.layout);
			this.descriptorPool = pool;
		}

		return pool;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.destroy();
		}
	}

	public static Vk11RenderPipeline compile(final Vk11Device device, final BackendRenderPipeline.CreateInfo info) {
		// TODO: XXX ABI BREAKAGE - shader modules now arrive as ready SPIR-V from the frontend pipeline
		// builder, so this backend no longer rebinds descriptor sets/vertex locations itself and instead
		// trusts the binding indices the frontend already assigned.
		String pipelineName = info.name();
		List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
		List<@Nullable ColorTargetState> colorTargetStates = info.colorTargetStates();

		Vk11BindGroupLayout layout = Vk11BindGroupLayout.create(device, uniforms, pipelineName);
		LongList shaderModules = new LongArrayList();
		long pipelineLayout;
		long withDepthPipeline = 0L;
		long withoutDepthPipeline = 0L;

		try {
			pipelineLayout = createPipelineLayout(device, layout, info.pushConstantsSize(), pipelineName);

			try (MemoryStack stack = MemoryStack.stackPush()) {
				Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(info.shaders().size(), stack);

				for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
					long module = createShaderModule(device, shader, pipelineName);
					shaderModules.add(module);
					shaderStages.put(
						VkPipelineShaderStageCreateInfo.calloc(stack)
							.sType$Default()
							.stage(Vk11Const.toVk(shader.module().type()))
							.module(module)
							.pName(stack.UTF8(shader.entryPoint()))
					);
				}

				shaderStages.flip();
				org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo vertexInputState = createVertexInputState(device, stack, info);
				VkPipelineInputAssemblyStateCreateInfo inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
					.sType$Default()
					.topology(Vk11Const.toVk(info.primitiveTopology()));

				int polygonMode = device.features.fillModeNonSolid() ? Vk11Const.toVk(info.polygonMode()) : VK10.VK_POLYGON_MODE_FILL;
				VkPipelineRasterizationStateCreateInfo rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
					.sType$Default()
					.polygonMode(polygonMode)
					.cullMode(info.cull() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE)
					.frontFace(VK10.VK_FRONT_FACE_CLOCKWISE)
					.lineWidth(1.0F);
				VkPipelineDepthStencilStateCreateInfo depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();

				if (info.depthStencilState() != null) {
					rasterizationState.depthBiasEnable(
						info.depthStencilState().depthBiasConstant() != 0.0F && info.depthStencilState().depthBiasScaleFactor() != 0.0F
					);
					rasterizationState.depthBiasConstantFactor(info.depthStencilState().depthBiasConstant());
					rasterizationState.depthBiasSlopeFactor(info.depthStencilState().depthBiasScaleFactor());
					depthStencilState.depthTestEnable(true);
					depthStencilState.depthWriteEnable(info.depthStencilState().writeDepth());
					depthStencilState.depthCompareOp(Vk11Const.toVk(info.depthStencilState().depthTest()));
				}

				org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState.Buffer blendAttachments =
					VkPipelineColorBlendAttachmentState.calloc(colorTargetStates.size(), stack);

				for (@Nullable ColorTargetState colorTargetState : colorTargetStates) {
					blendAttachments.colorWriteMask(colorTargetState != null ? Vk11Const.toVk(colorTargetState) : 0);

					if (colorTargetState != null && colorTargetState.blendFunction().isPresent()) {
						applyBlendInformation(blendAttachments, colorTargetState.blendFunction().get());
					}

					blendAttachments.position(blendAttachments.position() + 1);
				}

				blendAttachments.position(0);
				VkPipelineColorBlendStateCreateInfo colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
					.sType$Default()
					.pAttachments(blendAttachments);
				VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
					.sType$Default()
					.rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
				VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
					.sType$Default()
					.pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
				VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
					.sType$Default()
					.viewportCount(1)
					.scissorCount(1);

				int[] colorFormats = new int[colorTargetStates.size()];

				for (int i = 0; i < colorTargetStates.size(); i++) {
					@Nullable ColorTargetState state = colorTargetStates.get(i);
					colorFormats[i] = state != null ? Vk11Const.toVk(state.format()) : VK10.VK_FORMAT_UNDEFINED;
				}

				int depthFormat = VK10.VK_FORMAT_D32_SFLOAT;
				long renderPassWithDepth = device.renderPassCache().getOrCreateRenderPass(colorFormats, true, depthFormat);
				long renderPassWithoutDepth = device.renderPassCache().getOrCreateRenderPass(colorFormats, false, VK10.VK_FORMAT_UNDEFINED);
				withDepthPipeline = createGraphicsPipeline(
					device, stack, pipelineName, shaderStages, vertexInputState, inputAssemblyState, rasterizationState, depthStencilState,
					colorBlendState, multisampleState, dynamicState, viewportState, pipelineLayout, renderPassWithDepth, "with depth"
				);

				// The without-depth variant disables depth testing entirely, matching the render pass it is built against
				depthStencilState.depthTestEnable(false);
				depthStencilState.depthWriteEnable(false);
				withoutDepthPipeline = createGraphicsPipeline(
					device, stack, pipelineName, shaderStages, vertexInputState, inputAssemblyState, rasterizationState, depthStencilState,
					colorBlendState, multisampleState, dynamicState, viewportState, pipelineLayout, renderPassWithoutDepth, "without depth"
				);
			}
		} catch (RuntimeException | Error e) {
			for (long module : shaderModules) {
				VK10.vkDestroyShaderModule(device.vkDevice(), module, null);
			}

			VK10.vkDestroyDescriptorSetLayout(device.vkDevice(), layout.handle(), null);
			throw e;
		}

		return new Vk11RenderPipeline(device, withDepthPipeline, withoutDepthPipeline, pipelineLayout, layout, shaderModules);
	}

	private static long createShaderModule(
		final Vk11Device device, final BackendRenderPipeline.CreateInfo.Shader shader, final String pipelineName
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// The frontend releases the SpvModule as soon as compilation finishes, so upload right away
			ByteBuffer spirv = shader.module().spv();
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(
				VK10.vkCreateShaderModule(device.vkDevice(), info, null, pointer), "Can't compile " + shader.name() + " for " + pipelineName
			);
			device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_SHADER_MODULE, pointer.get(0), () -> shader.name());
			return pointer.get(0);
		}
	}

	private static long createPipelineLayout(
		final Vk11Device device, final Vk11BindGroupLayout layout, final int pushConstantRange, final String pipelineName
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
				.sType$Default()
				.pSetLayouts(stack.longs(layout.handle()));

			if (pushConstantRange > 0) {
				VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
					.stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
					.offset(0)
					.size(pushConstantRange);
				createInfo.pPushConstantRanges(range);
			}

			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(
				VK10.vkCreatePipelineLayout(device.vkDevice(), createInfo, null, pointer), "Can't create pipeline layout for " + pipelineName
			);
			long handle = pointer.get(0);
			device.instance().debug().setObjectName(
				device.vkDevice(), VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, handle, () -> "Pipeline layout for " + pipelineName
			);
			return handle;
		}
	}

	private static VkPipelineVertexInputStateCreateInfo createVertexInputState(
		final Vk11Device device, final MemoryStack stack, final BackendRenderPipeline.CreateInfo info
	) {
		List<BackendRenderPipeline.CreateInfo.VertexBuffer> vertexBuffers = info.vertexBuffers();
		org.lwjgl.vulkan.VkVertexInputAttributeDescription.Buffer vertexAttributeDescriptions =
			VkVertexInputAttributeDescription.calloc(info.attribBindings().size(), stack);
		org.lwjgl.vulkan.VkVertexInputBindingDescription.Buffer vertexBindingDescriptions =
			VkVertexInputBindingDescription.calloc(vertexBuffers.size(), stack);
		org.lwjgl.vulkan.VkVertexInputBindingDivisorDescriptionEXT.Buffer vertexBindingDivisorDescriptions =
			VkVertexInputBindingDivisorDescriptionEXT.calloc(vertexBuffers.size(), stack);
		int divisorCount = 0;

		for (int i = 0; i < vertexBuffers.size(); i++) {
			BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer = vertexBuffers.get(i);
			vertexBindingDescriptions.put(
				VkVertexInputBindingDescription.calloc(stack)
					.binding(vertexBuffer.bufferSlot())
					.stride(vertexBuffer.stride())
					.inputRate(vertexBuffer.stepRate() > 0 ? VK10.VK_VERTEX_INPUT_RATE_INSTANCE : VK10.VK_VERTEX_INPUT_RATE_VERTEX)
			);

			if (vertexBuffer.stepRate() > 0) {
				if (device.features.vertexAttributeDivisor()) {
					vertexBindingDivisorDescriptions.put(
						VkVertexInputBindingDivisorDescriptionEXT.calloc(stack)
							.binding(vertexBuffer.bufferSlot())
							.divisor(vertexBuffer.stepRate())
					);
					divisorCount++;
				} else if (vertexBuffer.stepRate() > 1) {
					throw new IllegalStateException("Device does not support instance attribute divisor above 1");
				}
			}
		}

		for (BackendRenderPipeline.CreateInfo.AttribBinding binding : info.attribBindings()) {
			vertexAttributeDescriptions.put(
				VkVertexInputAttributeDescription.calloc(stack)
					.location(binding.location())
					.binding(binding.bufferSlot())
					.offset(binding.offset())
					.format(Vk11Const.toVk(binding.format()))
			);
		}

		vertexAttributeDescriptions.flip();
		vertexBindingDescriptions.flip();
		vertexBindingDivisorDescriptions.flip();
		VkPipelineVertexInputStateCreateInfo vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack)
			.sType$Default()
			.pVertexAttributeDescriptions(vertexAttributeDescriptions)
			.pVertexBindingDescriptions(vertexBindingDescriptions);

		if (divisorCount > 0) {
			VkPipelineVertexInputDivisorStateCreateInfoEXT vertexInputDivisorState = VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack)
				.sType$Default()
				.pVertexBindingDivisors(vertexBindingDivisorDescriptions);
			vertexInputState.pNext(vertexInputDivisorState);
		}

		return vertexInputState;
	}

	private static long createGraphicsPipeline(
		final Vk11Device device,
		final MemoryStack stack,
		final String pipelineName,
		final Buffer shaderStages,
		final VkPipelineVertexInputStateCreateInfo vertexInputState,
		final VkPipelineInputAssemblyStateCreateInfo inputAssemblyState,
		final VkPipelineRasterizationStateCreateInfo rasterizationState,
		final VkPipelineDepthStencilStateCreateInfo depthStencilState,
		final VkPipelineColorBlendStateCreateInfo colorBlendState,
		final VkPipelineMultisampleStateCreateInfo multisampleState,
		final VkPipelineDynamicStateCreateInfo dynamicState,
		final VkPipelineViewportStateCreateInfo viewportState,
		final long pipelineLayout,
		final long renderPass,
		final String variant
	) {
		VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
		pipelineInfo.sType$Default();
		pipelineInfo.stageCount(shaderStages.remaining());
		pipelineInfo.pStages(shaderStages);
		pipelineInfo.pVertexInputState(vertexInputState);
		pipelineInfo.pInputAssemblyState(inputAssemblyState);
		pipelineInfo.pRasterizationState(rasterizationState);
		pipelineInfo.pDepthStencilState(depthStencilState);
		pipelineInfo.pColorBlendState(colorBlendState);
		pipelineInfo.pMultisampleState(multisampleState);
		pipelineInfo.pDynamicState(dynamicState);
		pipelineInfo.pViewportState(viewportState);
		pipelineInfo.layout(pipelineLayout);
		pipelineInfo.renderPass(renderPass);
		pipelineInfo.subpass(0);

		LongBuffer pointer = stack.callocLong(1);
		Vk11Utils.crashIfFailure(
			VK10.vkCreateGraphicsPipelines(device.vkDevice(), 0L, pipelineInfo, null, pointer), "Failed to create pipeline " + variant + " for " + pipelineName
		);
		return pointer.get(0);
	}

	private static void applyBlendInformation(final VkPipelineColorBlendAttachmentState.Buffer blendAttachments, final BlendFunction blendFunction) {
		blendAttachments.blendEnable(true)
			.srcColorBlendFactor(Vk11Const.toVk(blendFunction.color().sourceFactor()))
			.dstColorBlendFactor(Vk11Const.toVk(blendFunction.color().destFactor()))
			.colorBlendOp(Vk11Const.toVk(blendFunction.color().op()))
			.srcAlphaBlendFactor(Vk11Const.toVk(blendFunction.alpha().sourceFactor()))
			.dstAlphaBlendFactor(Vk11Const.toVk(blendFunction.alpha().destFactor()))
			.alphaBlendOp(Vk11Const.toVk(blendFunction.alpha().op()));
	}

	public void destroy() {
		if (this.withDepthPipeline != 0L) {
			VK10.vkDestroyPipeline(this.device.vkDevice(), this.withDepthPipeline, null);
		}

		if (this.withoutDepthPipeline != 0L) {
			VK10.vkDestroyPipeline(this.device.vkDevice(), this.withoutDepthPipeline, null);
		}

		VK10.vkDestroyPipelineLayout(this.device.vkDevice(), this.pipelineLayout, null);
		VK10.vkDestroyDescriptorSetLayout(this.device.vkDevice(), this.layout.handle(), null);

		if (this.descriptorPool != null) {
			this.descriptorPool.destroy();
		}

		for (long module : this.shaderModules) {
			VK10.vkDestroyShaderModule(this.device.vkDevice(), module, null);
		}
	}
}
