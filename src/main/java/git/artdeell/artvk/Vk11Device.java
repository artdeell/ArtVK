package git.artdeell.artvk;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import git.artdeell.ArtVK;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.*;

@Environment(EnvType.CLIENT)
public class Vk11Device implements GpuDeviceBackend {
	private final Vk11Instance instance;
	private final VkDevice vkDevice;
    private final IntVMA vmaObj;
	private final long vma;
	private final int apiVersion;
	private final DeviceInfo deviceInfo;
	private final Vk11Queue graphicsQueue;
	private final Vk11Queue computeQueue;
	private final Vk11Queue transferQueue;
	private final boolean isIntegratedIntelMoltenVK;
    public final Vk11PhysicalDevice.Features features;
	private final Vk11CommandEncoder commandEncoder;
	private final Vk11RenderPassCache renderPassCache;
    private final Vk11FramebufferCache framebufferCache;

	public Vk11Device(
		final Vk11Instance instance,
		final Vk11PhysicalDevice physicalDevice,
        final VkDevice vkDevice,
		final IntVMA vma
	) {
		this.instance = instance;
		this.vkDevice = vkDevice;
		this.vmaObj = vma;
        this.vma = vmaObj.ptr;

        Vk11PhysicalDevice.Properties properties = physicalDevice.properties();
        features = physicalDevice.features();

		this.apiVersion = physicalDevice.normalizedApiVersion();

        if(!features.fillModeNonSolid()) ArtVK.LOGGER.warn("Device does not support fillModeNonSolid, wireframe rendering won't work");

		this.deviceInfo = new DeviceInfo(
			properties.deviceName(),
			physicalDevice.vendorName(),
			properties.driverInfo(),
			true,
			Vk11Backend.NAME,
			properties.timestampPeriod(),
			new DeviceLimits(
				features.samplerAnisotropy() ? properties.maxSamplerAnisotropy() : 1,
                properties.minUniformBufferOffsetAlignment(),
				properties.maxImageDimension2D(),
				properties.maxMemoryAllocationSize(),
				Integer.MAX_VALUE,
				properties.maxColorAttachments(),
				Integer.MAX_VALUE
			),
			// TODO: Correctly fill actual supported features
			new DeviceFeatures(true, features.shaderDrawParameters(), features.multiDraw(), features.multiDraw(), true, true, true, true),
			Collections.emptySet(), // TODO: maybe implement this?
			new HintsAndWorkarounds(false, false, false, false),
			physicalDevice.deviceType()
		);

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

		this.isIntegratedIntelMoltenVK = properties.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU
			&& properties.vendorId() == 0x8086
			&& properties.driverId() == 14;
		physicalDevice.close();
		this.renderPassCache = new Vk11RenderPassCache(this);
        this.framebufferCache = new Vk11FramebufferCache(this);
		this.commandEncoder = new Vk11CommandEncoder(this);
	}

	@Override
	public void close() {
		this.commandEncoder.destroy();
        this.framebufferCache.destroy();
		this.renderPassCache.destroy();
		vmaObj.close();
		VK10.vkDestroyDevice(this.vkDevice, null);
		this.instance.close();
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

	public int apiVersion() {
		return apiVersion;
	}

	public Vk11RenderPassCache renderPassCache() {
		return this.renderPassCache;
	}

    public Vk11FramebufferCache framebufferCache() {
        return this.framebufferCache;
    }
    
	@Override
	public @NotNull GpuSurfaceBackend createSurface(final long windowHandle, final @NotNull BooleanSupplier isIconified) {
		return new Vk11GpuSurface(this, windowHandle, isIconified);
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
		final @Nullable String label,
		final @GpuTexture.Usage int usage,
		final @NotNull  GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return new Vk11GpuTexture(this, usage, this.isDebuggingEnabled() && label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public @NotNull GpuTextureView createTextureView(final @NotNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new Vk11GpuTextureView(this, (Vk11GpuTexture)texture, baseMipLevel, mipLevels);
	}

	@Override
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
	public @NotNull BackendRenderPipeline.Pending compilePipeline(final @NotNull BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
		// TODO: XXX ABI BREAKAGE - the frontend now owns shader compilation and reflection, so the backend
		// receives ready-made SPIR-V modules and an already-flattened uniform list instead of a RenderPipeline.
		Vk11RenderPipeline pipeline = Vk11RenderPipeline.compile(this, pipelineCreateInfo);
		return () -> pipeline;
	}

	@Override
	public @NotNull GpuQueryPool createTimestampQueryPool(final int size) {
		return new Vk11QueryPool(this, size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		double timestampPeriod = this.deviceInfo.timestampPeriod();
		long deviceTime = this.commandEncoder.getTimestampNow();
		long hostTime = System.nanoTime();
		long deviceTimeInNanos = timestampPeriod == 1.0 ? deviceTime : (long) (deviceTime * timestampPeriod);
		return hostTime - deviceTimeInNanos;
	}
}
