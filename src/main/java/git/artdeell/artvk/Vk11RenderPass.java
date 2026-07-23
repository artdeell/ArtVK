package git.artdeell.artvk;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMultiDraw;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;
import org.lwjgl.vulkan.VkMultiDrawIndexedInfoEXT;
import org.lwjgl.vulkan.VkMultiDrawInfoEXT;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkViewport.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11RenderPass implements RenderPassBackend {
	public static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
	private final Vk11Device device;
	private final Vk11CommandEncoder encoder;
	private final RenderPass.@Nullable RenderArea renderArea;
	private final int outputWidth;
	private final int outputHeight;
	private final boolean hasDepth;
	private final Supplier<String> label;
	protected int pushedDebugGroups = 0;
	private final VkCommandBuffer commandBuffer;
	protected @Nullable Vk11RenderPipeline pipeline;
	private boolean anyDescriptorDirty = false;
	protected final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
	protected final HashMap<String, Vk11RenderPass.TextureViewAndSampler> textures = new HashMap<>();

	public Vk11RenderPass(
		final Vk11Device device,
		final Vk11CommandEncoder encoder,
		final VkCommandBuffer commandBuffer,
		final RenderPass.RenderArea renderArea,
		final int outputWidth,
		final int outputHeight,
		final boolean hasDepth,
		final Supplier<String> label
	) {
		this.device = device;
		this.encoder = encoder;
		this.commandBuffer = commandBuffer;
		this.renderArea = renderArea;
		this.outputWidth = outputWidth;
		this.outputHeight = outputHeight;
		this.hasDepth = hasDepth;
		this.label = label;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			Buffer viewport = VkViewport.calloc(1, stack);
			viewport.x(0.0F);
			viewport.y(0.0F);
			viewport.width(outputWidth);
			viewport.height(outputHeight);
			viewport.minDepth(0.0F);
			viewport.maxDepth(1.0F);
			VK10.vkCmdSetViewport(commandBuffer(), 0, viewport);
			setScissor(stack, commandBuffer(), renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height());
		}
	}

	private VkCommandBuffer commandBuffer() {
		return commandBuffer;
	}

	@Override
	public void pushDebugGroup(final @NotNull Supplier<String> label) {
		pushedDebugGroups++;
		device.instance().debug().beginDebugGroup(commandBuffer(), label);
	}

	@Override
	public void popDebugGroup() {
		if (pushedDebugGroups == 0) {
			throw new IllegalStateException("Can't pop more debug groups than was pushed!");
		}

		pushedDebugGroups--;
		device.instance().debug().endDebugGroup(commandBuffer());
	}

	@Override
	public void setPipeline(final @NotNull RenderPipeline pipeline) {
		Vk11RenderPipeline newPipeline = device.getOrCompilePipeline(pipeline);
		if (!newPipeline.isValid()) {
			throw new IllegalStateException("Pipeline is not valid (may contain invalid shaders?)");
		}

		if (this.pipeline != newPipeline) {
			this.pipeline = newPipeline;
			anyDescriptorDirty = true;
			VK10.vkCmdBindPipeline(commandBuffer(), 0, hasDepth ? this.pipeline.withDepthPipeline() : this.pipeline.withoutDepthPipeline());
		}
	}

	@Override
	public void bindTexture(final @NotNull String name, final @Nullable GpuTextureView textureView, final @Nullable GpuSampler sampler) {
		if (textureView != null && sampler != null) {
			Vk11RenderPass.TextureViewAndSampler newValue = new Vk11RenderPass.TextureViewAndSampler((Vk11GpuTextureView)textureView, (Vk11GpuSampler)sampler);
			Vk11RenderPass.TextureViewAndSampler oldValue = textures.get(name);
			if (oldValue == null || oldValue.view() != newValue.view() || oldValue.sampler() != newValue.sampler()) {
				textures.put(name, newValue);
				anyDescriptorDirty = true;
			}
		} else if (textureView == null && sampler == null) {
			if (textures.remove(name) != null) {
				anyDescriptorDirty = true;
			}
		} else {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public void setUniform(final @NotNull String name, final GpuBuffer value) {
		GpuBufferSlice newSlice = value.slice();
		GpuBufferSlice oldSlice = uniforms.get(name);
		if (oldSlice == null || oldSlice.buffer() != newSlice.buffer() || oldSlice.offset() != newSlice.offset() || oldSlice.length() != newSlice.length()) {
			uniforms.put(name, newSlice);
			anyDescriptorDirty = true;
		}
	}

	@Override
	public void setUniform(final @NotNull String name, final @NotNull GpuBufferSlice value) {
		GpuBufferSlice oldSlice = uniforms.get(name);
		if (oldSlice == null || oldSlice.buffer() != value.buffer() || oldSlice.offset() != value.offset() || oldSlice.length() != value.length()) {
			uniforms.put(name, value);
			anyDescriptorDirty = true;
		}
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			setScissor(stack, commandBuffer(), x, y, width, height);
		}
	}

	private static void setScissor(final MemoryStack stack, final VkCommandBuffer commandBuffer, final int x, final int y, final int width, final int height) {
		VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
		scissor.offset().set(x, y);
		scissor.extent().set(width, height);
		VK10.vkCmdSetScissor(commandBuffer, 0, scissor);
	}

	@Override
	public void disableScissor() {
		if (renderArea != null) {
			enableScissor(renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height());
		} else {
			enableScissor(0, 0, outputWidth, outputHeight);
		}
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long buffer = vertexBuffer != null ? ((Vk11GpuBuffer)vertexBuffer.buffer()).vkBuffer() : 0L;
			long offset = vertexBuffer != null ? vertexBuffer.offset() : 0L;
			VK10.vkCmdBindVertexBuffers(commandBuffer(), slot, stack.longs(buffer), stack.longs(offset));
		}
	}

	@Override
	public void setIndexBuffer(final @NotNull GpuBuffer indexBuffer, final IndexType indexType) {
		int type = switch (indexType) {
			case SHORT -> VK10.VK_INDEX_TYPE_UINT16;
			case INT -> VK10.VK_INDEX_TYPE_UINT32;
		};
		VK10.vkCmdBindIndexBuffer(commandBuffer(), ((Vk11GpuBuffer)indexBuffer).vkBuffer(), 0L, type);
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		if (pipeline != null && pipeline.isValid()) {
            pushDescriptors();
			VK10.vkCmdDrawIndexed(commandBuffer(), indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	@Override
	public void multiDrawIndexed(final @NotNull IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (pipeline != null && pipeline.isValid()) {
            pushDescriptors();
			EXTMultiDraw.nvkCmdDrawMultiIndexedEXT(
				commandBuffer(), drawCount, MemoryUtil.memAddress(drawParameters), instanceCount, firstInstance, VkMultiDrawIndexedInfoEXT.SIZEOF, 0L
			);
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	@Override
	public void multiDrawIndexed(final @NotNull PointerBuffer firstIndexOffsets, final @NotNull IntBuffer indexCounts, final @NotNull IntBuffer vertexOffsets, final int drawCount) {
		throw new UnsupportedOperationException("Vulkan does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndexedIndirect(final @NotNull GpuBufferSlice commands, final int drawCount) {
		if (pipeline != null && pipeline.isValid()) {
            pushDescriptors();
			VK10.vkCmdDrawIndexedIndirect(
				commandBuffer(), ((Vk11GpuBuffer)commands.buffer()).vkBuffer(), commands.offset(), drawCount, VkDrawIndexedIndirectCommand.SIZEOF
			);
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<@NotNull T>> draws,
		final @Nullable GpuBuffer defaultIndexBuffer,
		final @Nullable IndexType defaultIndexType,
		final @NotNull Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		for (RenderPass.Draw<@NotNull T> draw : draws) {
			BiConsumer<T, RenderPass.UniformUploader> uniformUploaderConsumer = draw.uniformUploaderConsumer();
			if (uniformUploaderConsumer != null) {
				uniformUploaderConsumer.accept(uniformArgument, this::setUniform);
			}

			assert draw.indexBuffer() != null || defaultIndexBuffer != null;
			assert draw.indexType() != null || defaultIndexType != null;
			setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultIndexType : draw.indexType());
			setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (pipeline != null && pipeline.isValid()) {
            pushDescriptors();
			VK10.vkCmdDraw(commandBuffer(), vertexCount, instanceCount, firstVertex, firstInstance);
		}
	}

	@Override
	public void multiDraw(final @NotNull IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (pipeline != null && pipeline.isValid()) {
            pushDescriptors();
			EXTMultiDraw.nvkCmdDrawMultiEXT(
				commandBuffer(), drawCount, MemoryUtil.memAddress(drawParameters), instanceCount, firstInstance, VkMultiDrawInfoEXT.SIZEOF
			);
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	@Override
	public void multiDraw(final @NotNull IntBuffer firstVertices, final @NotNull IntBuffer vertexCounts, final int drawCount) {
		throw new UnsupportedOperationException("Vulkan does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndirect(final @NotNull GpuBufferSlice commands, final int drawCount) {
		if (pipeline != null && pipeline.isValid()) {
			pushDescriptors();
			VK10.vkCmdDrawIndirect(commandBuffer(), ((Vk11GpuBuffer)commands.buffer()).vkBuffer(), commands.offset(), drawCount, VkDrawIndirectCommand.SIZEOF);
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	private void pushDescriptors() {
        if(!anyDescriptorDirty) return;
        
        if (VALIDATION) {
            for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.info().getBindGroupLayouts())) {
                GpuBufferSlice value = uniforms.get(uniform.name());
                if (value == null) {
                    throw new IllegalStateException("Missing uniform " + uniform.name() + " (should be " + uniform.type() + ")");
                }

                if (uniform.type() == UniformType.UNIFORM_BUFFER) {
                    if (value.buffer().isClosed()) {
                        throw new IllegalStateException("Uniform buffer " + uniform.name() + " is already closed");
                    }

                    if ((value.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) {
                        throw new IllegalStateException("Uniform buffer " + uniform.name() + " must have GpuBuffer.USAGE_UNIFORM");
                    }
                }

                if (uniform.type() == UniformType.TEXEL_BUFFER) {
                    if (value.offset() != 0L || value.length() != value.buffer().size()) {
                        throw new IllegalStateException("Uniform texel buffers do not support a slice of a buffer, must be entire buffer");
                    }

                    if ((value.buffer().usage() & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) == 0) {
                        throw new IllegalStateException("Uniform texel buffer " + uniform.name() + " must have GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER");
                    }

                    if (uniform.gpuFormat() == null) {
                        throw new IllegalStateException("Invalid uniform texel buffer " + uniform.name() + " (missing a texture format)");
                    }
                }
            }
        }

        int frameIndex = encoder.currentSubmitIndex();
        assert pipeline != null;
        Vk11BindGroupLayout layout = pipeline.layout();
        Vk11DescriptorPool pool = pipeline.descriptorPool();

        pool.allocateSet(frameIndex);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < layout.entries().size(); i++) {
                Vk11BindGroupLayout.Entry entry = layout.entries().get(i);
                switch (entry.type()) {
                    case UNIFORM_BUFFER -> {
                        GpuBufferSlice buffer = uniforms.get(entry.name());
                        if (buffer == null) {
                            throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                        }
                        pool.updateUniformBuffer(device, i, ((Vk11GpuBuffer)buffer.buffer()).vkBuffer(), buffer.offset(), buffer.length());
                    }
                    case SAMPLED_IMAGE -> {
                        Vk11RenderPass.TextureViewAndSampler value = textures.get(entry.name());
                        if (value == null) {
                            throw new IllegalStateException("Missing sampler " + entry.name());
                        }
                        pool.updateSampledImage(device, i, value.view.vkImageView(), value.sampler.vkSampler());
                    }
                    case TEXEL_BUFFER -> {
                        GpuBufferSlice value = uniforms.get(entry.name());
                        if (value == null) {
                            throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                        }

                        LongBuffer bufferViewPtr = stack.callocLong(1);
                        try (MemoryStack innerStack = stack.push()) {
                            assert entry.texelBufferFormat() != null;
                            VkBufferViewCreateInfo viewCreateInfo = VkBufferViewCreateInfo.calloc(innerStack).sType$Default();
                            viewCreateInfo.buffer(((Vk11GpuBuffer)value.buffer()).vkBuffer());
                            viewCreateInfo.offset(value.offset());
                            viewCreateInfo.range(value.length());
                            viewCreateInfo.format(Vk11Const.toVk(entry.texelBufferFormat()));
                            Vk11Utils.crashIfFailure(
                                    VK10.vkCreateBufferView(device.vkDevice(), viewCreateInfo, null, bufferViewPtr), "Couldn't create buffer view for texel buffer"
                            );
                            long bufferViewHandle = bufferViewPtr.get(0);
                            encoder.queueForDestroy(() -> VK10.vkDestroyBufferView(device.vkDevice(), bufferViewHandle, null));
                        }
                        pool.updateTexelBuffer(device, i, bufferViewPtr.get(0));
                    }
                }
            }

            pool.bind(commandBuffer(), pipeline.pipelineLayout());
        }

        anyDescriptorDirty = false;
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		long queryPool = ((Vk11QueryPool)pool).vkQueryPool();
		VK10.vkCmdResetQueryPool(commandBuffer(), queryPool, index, 1);
		VK10.vkCmdWriteTimestamp(commandBuffer(), VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, index);
	}

	public Supplier<String> getLabel() {
		return label;
	}

	@Environment(EnvType.CLIENT)
	protected record TextureViewAndSampler(Vk11GpuTextureView view, Vk11GpuSampler sampler) {
	}
}
