package git.artdeell.artvk;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
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
	// The number of uniforms a pipeline declares is only known once the pipeline is bound, so grow on demand
	protected @Nullable Object[] uniforms = new Object[0];
	protected @Nullable TextureViewAndSampler[] textures = new TextureViewAndSampler[0];

	public Vk11RenderPass(
		final Vk11Device device,
		final Vk11CommandEncoder encoder,
		final VkCommandBuffer commandBuffer,
		final RenderPass.@Nullable RenderArea renderArea,
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
	public void setPipeline(final @NotNull BackendRenderPipeline pipeline) {
		if (!(pipeline instanceof Vk11RenderPipeline newPipeline)) {
			throw new IllegalArgumentException("Pipeline must have been compiled by this backend");
		}

		if (newPipeline.isClosed()) {
			throw new IllegalStateException("Pipeline is closed");
		}

		if (this.pipeline != newPipeline) {
			this.pipeline = newPipeline;
			// The frontend replays every uniform after setPipeline, but a stale binding from a previous
			// pipeline would otherwise survive, so start from a clean slate
			this.uniforms = new @Nullable Object[newPipeline.layout().entries().size()];
			this.textures = new @Nullable TextureViewAndSampler[newPipeline.layout().entries().size()];
			anyDescriptorDirty = true;
			VK10.vkCmdBindPipeline(commandBuffer(), 0, hasDepth ? this.pipeline.withDepthPipeline() : this.pipeline.withoutDepthPipeline());
		}
	}

	@Override
	public void pushConstants(final ByteBuffer value) {
		if (this.pipeline == null) {
			throw new IllegalStateException("Must bind pipeline before pushing constants");
		}

		VK10.vkCmdPushConstants(commandBuffer(), this.pipeline.pipelineLayout(), VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, value);
	}

	@Override
	public void setUniform(final int index, final @Nullable Object value) {
		// TODO: XXX ABI BREAKAGE - uniforms are now addressed by their index into the pipeline's flattened
		// uniform list (a GpuBufferSlice, a TextureViewAndSampler, or null) instead of by name.
		if (value == null) {
			if (uniforms[index] != null || textures[index] != null) {
				uniforms[index] = null;
				textures[index] = null;
				anyDescriptorDirty = true;
			}

			return;
		}

		if (value instanceof GpuBufferSlice slice) {
			GpuBufferSlice oldSlice = (GpuBufferSlice) uniforms[index];

			if (oldSlice == null || oldSlice.buffer() != slice.buffer() || oldSlice.offset() != slice.offset() || oldSlice.length() != slice.length()) {
				uniforms[index] = slice;
				textures[index] = null;
				anyDescriptorDirty = true;
			}
		} else if (value instanceof TextureViewAndSampler pair) {
			TextureViewAndSampler oldValue = textures[index];

			if (oldValue == null || oldValue.view() != pair.view() || oldValue.sampler() != pair.sampler()) {
				textures[index] = pair;
				uniforms[index] = null;
				anyDescriptorDirty = true;
			}
		} else {
			throw new IllegalArgumentException("Unsupported uniform value type " + value.getClass().getName());
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
		if (pipeline != null && !pipeline.isClosed()) {
            pushDescriptors();
			VK10.vkCmdDrawIndexed(commandBuffer(), indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	@Override
	public void multiDrawIndexed(final @NotNull IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (pipeline != null && !pipeline.isClosed()) {
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
		if (pipeline != null && !pipeline.isClosed()) {
            pushDescriptors();
			long buf = ((Vk11GpuBuffer)commands.buffer()).vkBuffer();
			if(device.features.multiDrawIndirect())
				VK10.vkCmdDrawIndexedIndirect(
					commandBuffer(), buf, commands.offset(), drawCount, VkDrawIndexedIndirectCommand.SIZEOF
				);
			else {
				// Uh oh! Looping through the draws...
				long offset = commands.offset();
				int stride = VkDrawIndexedIndirectCommand.SIZEOF;
				for(int i = 0; i < drawCount; i++){
					VK10.vkCmdDrawIndexedIndirect(commandBuffer(), buf, offset + (long) i *stride, 1, stride);
				}
			}
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (pipeline != null && !pipeline.isClosed()) {
            pushDescriptors();
			VK10.vkCmdDraw(commandBuffer(), vertexCount, instanceCount, firstVertex, firstInstance);
		}
	}

	@Override
	public void multiDraw(final @NotNull IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (pipeline != null && !pipeline.isClosed()) {
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
		if (pipeline != null && !pipeline.isClosed()) {
			pushDescriptors();
			long buf = ((Vk11GpuBuffer)commands.buffer()).vkBuffer();
			if(device.features.multiDrawIndirect())
				VK10.vkCmdDrawIndirect(commandBuffer(), buf, commands.offset(), drawCount, VkDrawIndirectCommand.SIZEOF);
			else {
				long offset = commands.offset();
				int stride = VkDrawIndirectCommand.SIZEOF;
				for(int i = 0; i < drawCount; i++){
					VK10.vkCmdDrawIndirect(commandBuffer(), buf, offset + (long) i *stride, 1, stride);
				}
			}
		} else {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
	}

	private void pushDescriptors() {
        if(!anyDescriptorDirty) return;
        
        if (VALIDATION) {
            List<BindGroupLayout.UniformDescription> declaredUniforms = pipeline.layout().entries();

            for (int i = 0; i < declaredUniforms.size(); i++) {
                BindGroupLayout.UniformDescription uniform = declaredUniforms.get(i);
                GpuBufferSlice value = (GpuBufferSlice) uniforms[i];
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

        int numEntries = layout.entries().size();
        try (
                MemoryStack stack = MemoryStack.stackPush();
                Vk11DescriptorPool.DescriptorSetAlloc update = pool.allocateSet(stack, numEntries, frameIndex)
        ) {
            for (int i = 0; i < numEntries; i++) {
                BindGroupLayout.UniformDescription entry = layout.entries().get(i);
                switch (entry.type()) {
                    case UNIFORM_BUFFER -> {
                        GpuBufferSlice buffer = (GpuBufferSlice) uniforms[i];
                        if (buffer == null) {
                            throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                        }
                        update.addUniformBuffer(i, ((Vk11GpuBuffer)buffer.buffer()).vkBuffer(), buffer.offset(), buffer.length());
                    }
                    case COMBINED_IMAGE_SAMPLER -> {
                        TextureViewAndSampler value = textures[i];
                        if (value == null) {
                            throw new IllegalStateException("Missing sampler " + entry.name());
                        }
                        update.addSampledImage(i, ((Vk11GpuTextureView)value.view()).vkImageView(), ((Vk11GpuSampler)value.sampler()).vkSampler());
                    }
                    case TEXEL_BUFFER -> {
                        GpuBufferSlice value = (GpuBufferSlice) uniforms[i];
                        if (value == null) {
                            throw new IllegalStateException("Missing uniform " + entry.name() + " (should be " + entry.type() + ")");
                        }

                        LongBuffer bufferViewPtr = stack.callocLong(1);
                        try (MemoryStack innerStack = stack.push()) {
                            assert entry.gpuFormat() != null;
                            VkBufferViewCreateInfo viewCreateInfo = VkBufferViewCreateInfo.calloc(innerStack).sType$Default();
                            viewCreateInfo.buffer(((Vk11GpuBuffer)value.buffer()).vkBuffer());
                            viewCreateInfo.offset(value.offset());
                            viewCreateInfo.range(value.length());
                            viewCreateInfo.format(Vk11Const.toVk(entry.gpuFormat()));
                            Vk11Utils.crashIfFailure(
                                    VK10.vkCreateBufferView(device.vkDevice(), viewCreateInfo, null, bufferViewPtr), "Couldn't create buffer view for texel buffer"
                            );
                            long bufferViewHandle = bufferViewPtr.get(0);
                            encoder.queueForDestroy(() -> VK10.vkDestroyBufferView(device.vkDevice(), bufferViewHandle, null));
                        }
                        update.addTexelBuffer(i, bufferViewPtr.get(0));
                    }
                }
            }
            update.updateAndBind(commandBuffer(), pipeline.pipelineLayout());
        }

        anyDescriptorDirty = false;
	}

	@Override
	public void writeTimestamp(final @NotNull GpuQueryPool pool, final int index) {
		long queryPool = ((Vk11QueryPool)pool).vkQueryPool();
		VK10.vkCmdResetQueryPool(commandBuffer(), queryPool, index, 1);
		VK10.vkCmdWriteTimestamp(commandBuffer(), VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, index);
	}

	public Supplier<String> getLabel() {
		return label;
	}

	public VkCommandBuffer getCommandBuffer(){
		return commandBuffer;
	}
	public Vk11RenderPipeline getPipeline(){
		return pipeline;
	}
}
