package git.artdeell.artvk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import git.artdeell.ArtVK;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.*;

@Environment(EnvType.CLIENT)
public class Vk11Device implements GpuDeviceBackend {
    private final ShaderSource defaultShaderSource;
	private final Map<RenderPipeline, Vk11RenderPipeline> pipelineCache = new IdentityHashMap<>();
	private final Map<ShaderCompilationKey, Vk11IntermediaryShaderModule> shaderCache = new HashMap<>();
	private final Vk11Instance instance;
	private final VkDevice vkDevice;
    private final IntVMA vmaObj;
	private final long vma;
	private final Vk11GlslCompiler glslCompiler = new Vk11GlslCompiler();
	private final DeviceInfo deviceInfo;
	private final Vk11Queue graphicsQueue;
	private final Vk11Queue computeQueue;
	private final Vk11Queue transferQueue;
	private final boolean isIntegratedIntelMoltenVK;
    public final boolean hasFillModeNonSolid, hasAnisotropy, hasAttributeDivisor;
	private final Vk11CommandEncoder commandEncoder;
	private final Vk11RenderPassCache renderPassCache;

	public Vk11Device(
		final ShaderSource defaultShaderSource,
		final Vk11Instance instance,
		final Vk11PhysicalDevice physicalDevice,
		final Set<String> enabledDeviceExtensions,
		final VkDevice vkDevice,
		final IntVMA vma
	) {
		this.defaultShaderSource = defaultShaderSource;
		this.instance = instance;
		this.vkDevice = vkDevice;
		this.vmaObj = vma;
        this.vma = vmaObj.ptr;
		Set<String> extensionNames = new HashSet<>();

		for (String name : instance.getEnabledExtensions()) {
			extensionNames.add(name + " (I)");
		}

		for (String name : enabledDeviceExtensions) {
			extensionNames.add(name + " (D)");
		}

		VkPhysicalDeviceLimits limits = physicalDevice.vkPhysicalDeviceProperties().limits();
		VkPhysicalDeviceVulkan11Properties vk11Properties = physicalDevice.vkPhysicalDeviceVulkan11Properties();
        VkPhysicalDeviceFeatures features = physicalDevice.vkPhysicalDeviceFeatures().features();

        hasFillModeNonSolid = features.fillModeNonSolid();
        hasAnisotropy = features.samplerAnisotropy();
        hasAttributeDivisor = enabledDeviceExtensions.contains("VK_EXT_vertex_attribute_divisor");

        if(!hasFillModeNonSolid) ArtVK.LOGGER.warn("Device does not support fillModeNonSolid, wireframe rendering won't work");

		this.deviceInfo = new DeviceInfo(
			physicalDevice.deviceName(),
			physicalDevice.vendorName(),
			physicalDevice.driverInfo(),
			true,
			"ArtVK",
			limits.timestampPeriod(),
			new DeviceLimits(
				hasAnisotropy ? (int)limits.maxSamplerAnisotropy() : 1,
				(int)limits.minUniformBufferOffsetAlignment(),
				limits.maxImageDimension2D(),
				vk11Properties.maxMemoryAllocationSize() <= 0L ? Long.MAX_VALUE : vk11Properties.maxMemoryAllocationSize(),
				Integer.MAX_VALUE,
				limits.maxColorAttachments()
			),
			new DeviceFeatures(true, enabledDeviceExtensions.contains("VK_EXT_multi_draw"), false, features.multiDrawIndirect(), true, true, true),
			Collections.unmodifiableSet(extensionNames),
			new HintsAndWorkarounds(false, false),
			physicalDevice.deviceType()
		);

        System.out.println("Max texture size for RGBA: "+deviceInfo.limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM) +" Memory alloc limit: "+vk11Properties.maxMemoryAllocationSize());

		IntIntPair graphicsQueueFamily = physicalDevice.graphicsQueueFamilyAndIndex();
		assert graphicsQueueFamily != null;
		IntIntPair computeQueueFamily = physicalDevice.computeQueueFamilyAndIndex();
		IntIntPair transferQueueFamily = physicalDevice.transferQueueFamilyAndIndex();
		this.graphicsQueue = new Vk11Queue(this, graphicsQueueFamily.leftInt(), graphicsQueueFamily.rightInt());
		if (computeQueueFamily != null) {
			this.computeQueue = new Vk11Queue(this, computeQueueFamily.leftInt(), computeQueueFamily.rightInt());
		} else {
			this.computeQueue = this.graphicsQueue;
		}

		if (transferQueueFamily != null) {
			this.transferQueue = new Vk11Queue(this, transferQueueFamily.leftInt(), transferQueueFamily.rightInt());
		} else {
			this.transferQueue = this.computeQueue;
		}

		this.isIntegratedIntelMoltenVK = physicalDevice.vkPhysicalDeviceProperties().deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU
			&& physicalDevice.vkPhysicalDeviceProperties().vendorID() == 32902
			&& physicalDevice.vkPhysicalDeviceDriverProperties().driverID() == 14;
		physicalDevice.close();
		this.renderPassCache = new Vk11RenderPassCache(this);
		this.commandEncoder = new Vk11CommandEncoder(this);
	}

	@Override
	public void close() {
		this.commandEncoder.destroy();
		this.clearPipelineCache();
		this.renderPassCache.destroy();
		vmaObj.close();;
		VK10.vkDestroyDevice(this.vkDevice, null);
		this.instance.close();
		this.glslCompiler.close();
	}

	@Override
	public @NotNull DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}

	public Vk11Instance instance() {
		return this.instance;
	}

	public VkDevice vkDevice() {
		return this.vkDevice;
	}

	public Vk11Queue graphicsQueue() {
		return this.graphicsQueue;
	}

	public Vk11Queue computeQueue() {
		return this.computeQueue;
	}

	public Vk11Queue transferQueue() {
		return this.transferQueue;
	}

	public long vma() {
		return this.vma;
	}

	public Vk11RenderPassCache renderPassCache() {
		return this.renderPassCache;
	}

	@Override
	public @NotNull GpuSurfaceBackend createSurface(final long windowHandle) {
		return new Vk11GpuSurface(this, windowHandle);
	}

	public @NotNull Vk11CommandEncoder createCommandEncoder() {
		return this.commandEncoder;
	}

	@Override
	public @NotNull GpuSampler createSampler(
		final @NotNull AddressMode addressModeU,
		final @NotNull AddressMode addressModeV,
		final @NotNull FilterMode minFilter,
		final @NotNull FilterMode magFilter,
		final int maxAnisotropy,
		final @NotNull OptionalDouble maxLod
	) {
		return new Vk11GpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public @NotNull GpuTexture createTexture(
		final @Nullable Supplier<String> label,
		final @GpuTexture.Usage int usage,
		final @NotNull  GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return new Vk11GpuTexture(this, usage, this.isDebuggingEnabled() && label != null ? label.get() : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public @NotNull GpuTexture createTexture(
		final @Nullable String label,
		final @GpuTexture.Usage int usage,
		final @NotNull GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return new Vk11GpuTexture(this, usage, this.isDebuggingEnabled() && label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public @NotNull GpuTextureView createTextureView(@NotNull final GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public @NotNull GpuTextureView createTextureView(final @NotNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new Vk11GpuTextureView(this, (Vk11GpuTexture)texture, baseMipLevel, mipLevels);
	}

	public @NotNull Vk11GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
		return new Vk11GpuBuffer.Direct(this, label, usage, size, this.isIntegratedIntelMoltenVK);
	}

	@Override
	public @NotNull GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		GpuBuffer buffer = this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
		this.createCommandEncoder().writeToBuffer(buffer.slice(), data);
		return buffer;
	}

	@Override
	public @NotNull List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return this.instance.debug().enabled();
	}

	@Override
	public @NotNull CompiledRenderPipeline precompilePipeline(final @NotNull RenderPipeline pipeline, final @Nullable ShaderSource customShaderSource) {
		ShaderSource shaderSource = customShaderSource == null ? this.defaultShaderSource : customShaderSource;
		return this.pipelineCache.computeIfAbsent(pipeline, ignored -> this.compilePipeline(pipeline, shaderSource));
	}

	protected Vk11RenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
		return this.pipelineCache.computeIfAbsent(pipeline, ignored -> this.compilePipeline(pipeline, this.defaultShaderSource));
	}

	protected Vk11IntermediaryShaderModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
		ShaderCompilationKey key = new ShaderCompilationKey(id, type, defines);
		return this.shaderCache.computeIfAbsent(key, ignored -> this.compileShader(key, shaderSource));
	}

	private Vk11IntermediaryShaderModule compileShader(final ShaderCompilationKey key, final ShaderSource shaderSource) {
		String source = shaderSource.get(key.id, key.type);
		if (source == null) {
			ArtVK.LOGGER.error("Couldn't find source for {} shader ({})", key.type, key.id);
			return Vk11IntermediaryShaderModule.INVALID;
		}

		String sourceWithDefines = GlslPreprocessor.injectDefines(source, key.defines);

		try {
			return this.glslCompiler.createIntermediary(key.id.toDebugFileName(), sourceWithDefines, key.type);
		} catch (ShaderCompileException e) {
			ArtVK.LOGGER.error("Couldn't compile {} shader {}: {}", key.type, key.id, e.getMessage());
			return Vk11IntermediaryShaderModule.INVALID;
		}
	}

	private Vk11RenderPipeline compilePipeline(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		Vk11IntermediaryShaderModule vertexShader = this.getOrCompileShader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
		Vk11IntermediaryShaderModule fragmentShader = this.getOrCompileShader(
			pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource
		);
		if (vertexShader == Vk11IntermediaryShaderModule.INVALID) {
			ArtVK.LOGGER.error("Couldn't compile pipeline {}: vertex shader {} was invalid", pipeline.getLocation(), pipeline.getVertexShader());
			return new Vk11RenderPipeline(pipeline, this, 0L, 0L, 0L, Vk11BindGroupLayout.INVALID_LAYOUT, null, 0L, 0L);
		}

		if (fragmentShader == Vk11IntermediaryShaderModule.INVALID) {
			ArtVK.LOGGER.error("Couldn't compile pipeline {}: fragment shader {} was invalid", pipeline.getLocation(), pipeline.getFragmentShader());
			return new Vk11RenderPipeline(pipeline, this, 0L, 0L, 0L, Vk11BindGroupLayout.INVALID_LAYOUT, null, 0L, 0L);
		}

		try {
			Vk11GlslCompiler.CompiledModules modules = this.glslCompiler.compile(this, pipeline, vertexShader, fragmentShader);
			List<Integer> colorFormats = new java.util.ArrayList<>();
			for (ColorTargetState cts : pipeline.getColorTargetStates()) {
				if (cts != null && cts.format() != null) {
					colorFormats.add(Vk11Const.toVk(cts.format()));
				} else {
					colorFormats.add(VK10.VK_FORMAT_R8G8B8A8_UNORM);
				}
			}
			int depthFormat = VK10.VK_FORMAT_D32_SFLOAT;
			long renderPassWithDepth = this.renderPassCache.getOrCreateRenderPass(colorFormats, true, depthFormat);
			long renderPassWithoutDepth = this.renderPassCache.getOrCreateRenderPass(colorFormats, false, VK10.VK_FORMAT_UNDEFINED);
			return Vk11RenderPipeline.compile(this, modules.layout(), pipeline, modules.vertex(), modules.fragment(), renderPassWithDepth, renderPassWithoutDepth);
		} catch (ShaderCompileException e) {
			ArtVK.LOGGER.error("Couldn't compile pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return new Vk11RenderPipeline(pipeline, this, 0L, 0L, 0L, Vk11BindGroupLayout.INVALID_LAYOUT, null, 0L, 0L);
		}
	}

	@Override
	public void clearPipelineCache() {
		this.graphicsQueue.waitIdle();
		this.pipelineCache.values().forEach(Vk11RenderPipeline::destroy);
		this.pipelineCache.clear();
		this.shaderCache.values().forEach(Vk11IntermediaryShaderModule::close);
		this.shaderCache.clear();
	}

	@Override
	public @NotNull GpuQueryPool createTimestampQueryPool(final int size) {
		return new Vk11QueryPool(this, size);
	}

	@Override
	public long getTimestampNow() {
		return this.commandEncoder.getTimestampNow();
	}

	@Environment(EnvType.CLIENT)
	private record ShaderCompilationKey(Identifier id, ShaderType type, ShaderDefines defines) {
		@Override
		public @NotNull String toString() {
			String string = this.id + " (" + this.type + ")";
			return !this.defines.isEmpty() ? string + " with " + this.defines : string;
		}
	}
}
