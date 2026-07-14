package git.artdeell.artvk;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo.Buffer;

@Environment(EnvType.CLIENT)
public record Vk11RenderPipeline(
	RenderPipeline info,
	Vk11Device device,
	long withDepthPipeline,
	long withoutDepthPipeline,
	long pipelineLayout,
	Vk11BindGroupLayout layout,
	Vk11DescriptorPool descriptorPool,
	long vertexModule,
	long fragmentModule
) implements CompiledRenderPipeline, Destroyable {
	public static final long INVALID_PIPELINE = 0L;

	@Override
	public boolean isValid() {
		return this.withDepthPipeline != 0L;
	}

	public static Vk11RenderPipeline compile(
		final Vk11Device device, final Vk11BindGroupLayout layout, final RenderPipeline pipeline, final long vertexModule, final long fragmentModule,
		final long vkRenderPassWithDepth, final long vkRenderPassWithoutDepth
	) {
		long pipelineLayout;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(layout.handle()));
			LongBuffer pointer = stack.callocLong(1);
			Vk11Utils.crashIfFailure(
                    VK10.vkCreatePipelineLayout(device.vkDevice(), createInfo, null, pointer), "Can't create pipeline for " + pipeline.getLocation()
			);
			pipelineLayout = pointer.get(0);
			device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, pipelineLayout, () -> "Pipeline layout for " + pipeline.getLocation());
		}

		Vk11DescriptorPool descriptorPool = new Vk11DescriptorPool(device, device.createCommandEncoder(), layout);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			ByteBuffer nameMain = stack.UTF8("main");
			VkPipelineShaderStageCreateInfo vertexStage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(nameMain);
			VkPipelineShaderStageCreateInfo fragmentStage = VkPipelineShaderStageCreateInfo.calloc(stack)
				.sType$Default()
				.stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
				.module(fragmentModule)
				.pName(nameMain);
			shaderStages.put(vertexStage).put(fragmentStage).flip();
			VertexFormat[] vertexBindings = pipeline.getVertexFormatBindings();
			org.lwjgl.vulkan.VkVertexInputAttributeDescription.Buffer vertexAttributeDescriptions = VkVertexInputAttributeDescription.calloc(
				vertexBindings.length, stack
			);
			org.lwjgl.vulkan.VkVertexInputBindingDescription.Buffer vertexBindingDescriptions = VkVertexInputBindingDescription.calloc(vertexBindings.length, stack);
			org.lwjgl.vulkan.VkVertexInputBindingDivisorDescriptionEXT.Buffer vertexBindingDivisorDescriptions = VkVertexInputBindingDivisorDescriptionEXT.calloc(
				vertexBindings.length, stack
			);
			int attribLocation = 0;

			for (int i = 0; i < vertexBindings.length; i++) {
				VertexFormat bindings = vertexBindings[i];
				if(bindings == null) continue;


                VkVertexInputBindingDescription bindingDescription = VkVertexInputBindingDescription.calloc(stack)
                        .binding(i)
                        .stride(bindings.getVertexSize())
                        .inputRate(bindings.getStepRate() > 0 ? VK10.VK_VERTEX_INPUT_RATE_INSTANCE : VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                vertexBindingDescriptions.put(bindingDescription);

                if (bindings.getStepRate() > 0) {
                    if(device.hasAttributeDivisor) {
                        VkVertexInputBindingDivisorDescriptionEXT divisorBinding = VkVertexInputBindingDivisorDescriptionEXT.calloc(stack)
                                .binding(i)
                                .divisor(bindings.getStepRate());
                        vertexBindingDivisorDescriptions.put(divisorBinding);
                    } else if (bindings.getStepRate() > 1) {
                        throw new IllegalStateException("Device does not support instance attribute divisor above 1");
                    }
                }

                for (VertexFormatElement element : bindings.getElements()) {
                    VkVertexInputAttributeDescription attributeDescription = VkVertexInputAttributeDescription.calloc(stack)
                            .location(attribLocation)
                            .binding(i)
                            .offset(element.offset())
                            .format(Vk11Const.toVk(element.format()));
                    vertexAttributeDescriptions.put(attributeDescription);
                    attribLocation++;
                }
			}

			vertexAttributeDescriptions.flip();
			vertexBindingDescriptions.flip();
			vertexBindingDivisorDescriptions.flip();
			VkPipelineVertexInputDivisorStateCreateInfoEXT vertexInputDivisorState = VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack)
				.sType$Default()
				.pVertexBindingDivisors(vertexBindingDivisorDescriptions);
			VkPipelineVertexInputStateCreateInfo vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack)
				.sType$Default()
				.pVertexAttributeDescriptions(vertexAttributeDescriptions)
				.pVertexBindingDescriptions(vertexBindingDescriptions);
			if (vertexInputDivisorState.vertexBindingDivisorCount() > 0) {
				vertexInputState.pNext(vertexInputDivisorState);
			}

			VkPipelineInputAssemblyStateCreateInfo inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
				.sType$Default()
				.topology(Vk11Const.toVk(pipeline.getPrimitiveTopology()));

            int polygonMode;

            if(!device.hasFillModeNonSolid) polygonMode = VK10.VK_POLYGON_MODE_FILL;
            else polygonMode = Vk11Const.toVk(pipeline.getPolygonMode());

			VkPipelineRasterizationStateCreateInfo rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
				.sType$Default()
				.polygonMode(polygonMode)
				.cullMode(pipeline.isCull() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE)
				.frontFace(VK10.VK_FRONT_FACE_CLOCKWISE)
				.lineWidth(1.0F);
			VkPipelineDepthStencilStateCreateInfo depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
			if (pipeline.getDepthStencilState() != null) {
				rasterizationState.depthBiasEnable(
					pipeline.getDepthStencilState().depthBiasConstant() != 0.0F && pipeline.getDepthStencilState().depthBiasScaleFactor() != 0.0F
				);
				rasterizationState.depthBiasConstantFactor(pipeline.getDepthStencilState().depthBiasConstant());
				rasterizationState.depthBiasSlopeFactor(pipeline.getDepthStencilState().depthBiasScaleFactor());
				depthStencilState.depthTestEnable(true);
				depthStencilState.depthWriteEnable(pipeline.getDepthStencilState().writeDepth());
				depthStencilState.depthCompareOp(Vk11Const.toVk(pipeline.getDepthStencilState().depthTest()));
			}

			ColorTargetState[] colorTargetStates = pipeline.getColorTargetStates();
			org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(colorTargetStates.length, stack);

			for (ColorTargetState colorTargetState : colorTargetStates) {
				blendAttachments.colorWriteMask(colorTargetState != null ? Vk11Const.toVk(colorTargetState) : 0);
				if (colorTargetState != null && colorTargetState.blendFunction().isPresent()) {
					applyBlendInformation(blendAttachments, colorTargetState.blendFunction().get());
				}

				blendAttachments.position(blendAttachments.position() + 1);
			}

			blendAttachments.position(0);
			VkPipelineColorBlendStateCreateInfo colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttachments);
			VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
			VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
				.sType$Default()
				.pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
			VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);

			long withDepthPipeline;
			long withoutDepthPipeline;

			{
				VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
				pipelineInfo.sType$Default();
				pipelineInfo.stageCount(2);
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
				pipelineInfo.renderPass(vkRenderPassWithDepth);
				pipelineInfo.subpass(0);

				LongBuffer pointer = stack.callocLong(1);
				Vk11Utils.crashIfFailure(
                        VK10.vkCreateGraphicsPipelines(device.vkDevice(), 0L, pipelineInfo, null, pointer), "Failed to create pipeline with depth"
				);
				withDepthPipeline = pointer.get(0);
			}

			{
				depthStencilState.depthTestEnable(false);
				depthStencilState.depthWriteEnable(false);

				VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
				pipelineInfo.sType$Default();
				pipelineInfo.stageCount(2);
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
				pipelineInfo.renderPass(vkRenderPassWithoutDepth);
				pipelineInfo.subpass(0);

				LongBuffer pointer = stack.callocLong(1);
				Vk11Utils.crashIfFailure(
                        VK10.vkCreateGraphicsPipelines(device.vkDevice(), 0L, pipelineInfo, null, pointer), "Failed to create pipeline without depth"
				);
				withoutDepthPipeline = pointer.get(0);
			}

			return new Vk11RenderPipeline(pipeline, device, withDepthPipeline, withoutDepthPipeline, pipelineLayout, layout, descriptorPool, vertexModule, fragmentModule);
		}
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
		this.descriptorPool.destroy();
		VK10.vkDestroyShaderModule(this.device.vkDevice(), this.vertexModule, null);
		VK10.vkDestroyShaderModule(this.device.vkDevice(), this.fragmentModule, null);
	}
}
