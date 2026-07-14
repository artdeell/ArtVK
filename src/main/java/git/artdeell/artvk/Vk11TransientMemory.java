package git.artdeell.artvk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceReferenceImmutablePair;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.stream.IntStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;
import org.lwjgl.vulkan.VkBufferCopy.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11TransientMemory implements TransientMemory, Destroyable {
	private static final long BLOCK_SIZE = 524288L;
	private static final long MAX_CPU_ALIGNMENT = 16L;
	private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
	private static final int BUFFER_USAGE_BITS = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
	private final Vk11Device device;
	private final Vk11CommandEncoder encoder;
	private final boolean useDeviceMemoryForMappedGpuStaging;
	private final TransientBlockAllocator<Long> cpuBlockAllocator = new TransientBlockAllocator<>(
		BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
	);
	private final TransientBlockAllocator<Vk11TransientMemory.VulkanAllocation> stagingBlockAllocator;
	private final TransientBlockAllocator<Vk11TransientMemory.VulkanAllocation> gpuBlockAllocator;
	private final TransientBlockAllocator<Pair<Vk11TransientMemory.VulkanAllocation, Vk11TransientMemory.VulkanAllocation>> gpuMappedBlockAllocator;
	private long submitIndex = 0L;
	private boolean anyCommandRecorded = false;
	private @Nullable VkCommandBuffer commandBuffer;

	Vk11TransientMemory(final Vk11Device device, final Vk11CommandEncoder encoder) {
		this.device = device;
		this.encoder = encoder;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceMemoryProperties(device.vkDevice().getPhysicalDevice(), memoryProperties);
			int heapCount = memoryProperties.memoryHeapCount();
			int typeCount = memoryProperties.memoryTypeCount();
			int largestDeviceLocalHeapIndex = -1;
			long largestDeviceLocalHeapSize = -1L;

			for (int i = 0; i < heapCount; i++) {
				VkMemoryHeap heapProperties = memoryProperties.memoryHeaps(i);
				if (Vk11Utils.hasAnyBit(heapProperties.flags(), VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) && heapProperties.size() >= largestDeviceLocalHeapSize) {
					largestDeviceLocalHeapIndex = i;
					largestDeviceLocalHeapSize = heapProperties.size();
				}
			}

			assert largestDeviceLocalHeapIndex != -1;
			boolean largestHeapIsHostVisibleAndCoherent = false;

			for (int i = 0; i < typeCount; i++) {
				VkMemoryType typeProperties = memoryProperties.memoryTypes(i);
				if (typeProperties.heapIndex() == largestDeviceLocalHeapIndex && Vk11Utils.hasAllBits(typeProperties.propertyFlags(), VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
					largestHeapIsHostVisibleAndCoherent = true;
					break;
				}
			}

			this.useDeviceMemoryForMappedGpuStaging = largestHeapIsHostVisibleAndCoherent;
		}

		this.stagingBlockAllocator = new TransientBlockAllocator<>(
			BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(size -> this.allocateVulkanBlock(size, true), this::freeVulkanBlock)
		);
		this.gpuBlockAllocator = new TransientBlockAllocator<>(
			BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(size -> this.allocateVulkanBlock(size, false), this::queueFreeVulkanBlock)
		);
		this.gpuMappedBlockAllocator = new TransientBlockAllocator<>(
			BLOCK_SIZE,
			MAX_GPU_ALIGNMENT,
			TransientBlockAllocator.Allocator.create(this::allocateGpuMappedVulkanBlock, this::freeGpuMappedVulkanBlock),
			this::recordGpuMappedCopy
		);
	}

	@Override
	public void destroy() {
		this.cpuBlockAllocator.close();
		this.stagingBlockAllocator.close();
		this.gpuBlockAllocator.close();
		this.gpuMappedBlockAllocator.close();
	}

	public void beginSubmit() {
		assert this.commandBuffer == null;
		this.commandBuffer = this.encoder.allocateAndBeginTransientCommandBuffer();
		this.encoder.execute(this.commandBuffer);
		this.anyCommandRecorded = false;
	}

	public void endSubmit() {
		this.cpuBlockAllocator.rotate().run();
		this.encoder.queueForDestroy(this.stagingBlockAllocator.rotate()::run);
		if (this.useDeviceMemoryForMappedGpuStaging) {
			this.encoder.queueForDestroy(this.gpuBlockAllocator.rotate()::run);
		} else {
			this.gpuBlockAllocator.rotate().run();
		}

		this.gpuMappedBlockAllocator.rotate();
		assert this.commandBuffer != null;
		if (this.anyCommandRecorded) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Vk11CommandEncoder.memoryBarrier(this.commandBuffer, stack);
			}
		}

		VK10.vkEndCommandBuffer(this.commandBuffer);
		this.commandBuffer = null;
		this.submitIndex++;
	}

	private void recordGpuMappedCopy(final Pair<Vk11TransientMemory.VulkanAllocation, Vk11TransientMemory.VulkanAllocation> block) {
		if (block.first() != block.second()) {
			assert block.first().size == block.second().size;

			try (MemoryStack stack = MemoryStack.stackPush()) {
				Buffer region = VkBufferCopy.calloc(1, stack);
				region.srcOffset(0L);
				region.dstOffset(0L);
				region.size(block.first().size);
				assert this.commandBuffer != null;
				VK10.vkCmdCopyBuffer(this.commandBuffer, block.first().vkBuffer, block.second().vkBuffer, region);
				this.anyCommandRecorded = true;
			}
		}
	}

	private Vk11TransientMemory.VulkanAllocation allocateVulkanBlock(final long size, final boolean staging) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
			bufferCreateInfo.size(size);
			bufferCreateInfo.usage(BUFFER_USAGE_BITS);
			bufferCreateInfo.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			bufferCreateInfo.pQueueFamilyIndices(null);
			VmaAllocationCreateInfo allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
			if (staging) {
				allocCreateInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
			} else {
				allocCreateInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			}

			if (this.useDeviceMemoryForMappedGpuStaging || staging) {
				allocCreateInfo.requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
				allocCreateInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
			}

			LongBuffer bufferPtr = stack.callocLong(1);
			PointerBuffer allocPtr = stack.callocPointer(1);
			int result = Vma.vmaCreateBuffer(this.device.vma(), bufferCreateInfo, allocCreateInfo, bufferPtr, allocPtr, null);
			Vk11Utils.crashIfFailure(result, "Failed to allocate VkBuffer");
			PointerBuffer hostPtrPtr = stack.callocPointer(1);
			if (staging || this.useDeviceMemoryForMappedGpuStaging) {
				Vk11Utils.crashIfFailure(Vma.vmaMapMemory(this.device.vma(), allocPtr.get(0), hostPtrPtr), "Failed to map buffer");
			}

			this.device.instance().debug().setObjectName(this.device.vkDevice(), VK10.VK_OBJECT_TYPE_BUFFER, bufferPtr.get(0), "Vk11 Transient Memory Buffer");
			return new Vk11TransientMemory.VulkanAllocation(bufferPtr.get(0), allocPtr.get(0), hostPtrPtr.get(0), size);
		}
	}

	private void queueFreeVulkanBlock(final Vk11TransientMemory.VulkanAllocation allocation) {
		this.encoder.queueForDestroy(() -> this.freeVulkanBlock(allocation));
	}

	private void freeVulkanBlock(final Vk11TransientMemory.VulkanAllocation allocation) {
		Vma.vmaDestroyBuffer(this.device.vma(), allocation.vkBuffer, allocation.vmaAllocation);
	}

	private Pair<Vk11TransientMemory.VulkanAllocation, Vk11TransientMemory.VulkanAllocation> allocateGpuMappedVulkanBlock(final long size) {
		assert size >= BLOCK_SIZE;
		assert size >= this.gpuBlockAllocator.blockSize();
		if (this.useDeviceMemoryForMappedGpuStaging) {
			TransientBlockAllocator.Allocation<Vk11TransientMemory.VulkanAllocation> block = this.gpuBlockAllocator.allocate(size, MAX_CPU_ALIGNMENT, size, 1L);
			assert block.offset() == 0L;
			return new ReferenceReferenceImmutablePair<>(block.block(), block.block());
		} else {
			assert size >= this.stagingBlockAllocator.blockSize();
			TransientBlockAllocator.Allocation<Vk11TransientMemory.VulkanAllocation> stagingBlock = this.stagingBlockAllocator.allocate(size, MAX_CPU_ALIGNMENT, size, 1L);
			TransientBlockAllocator.Allocation<Vk11TransientMemory.VulkanAllocation> gpuBlock = this.gpuBlockAllocator.allocate(size, MAX_CPU_ALIGNMENT, size, 1L);
			return new ReferenceReferenceImmutablePair<>(stagingBlock.block(), gpuBlock.block());
		}
	}

	private void freeGpuMappedVulkanBlock(final Pair<Vk11TransientMemory.VulkanAllocation, Vk11TransientMemory.VulkanAllocation> allocations) {
	}

	@Override
	public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
		assert size <= Integer.MAX_VALUE;
		TransientBlockAllocator.Allocation<Long> alloc = this.cpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
		return MemoryUtil.memByteBuffer(alloc.block() + alloc.offset(), (int)alloc.size());
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(
		final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
	) {
		assert size <= Integer.MAX_VALUE;
		TransientBlockAllocator.Allocation<Vk11TransientMemory.VulkanAllocation> alloc = this.stagingBlockAllocator
			.allocate(size, alignment, minimumAllocation, elementSize);
		Vk11TransientMemory.TransientGpuBuffer apiBuffer = new Vk11TransientMemory.TransientGpuBuffer(
			alloc.block().vkBuffer, usage, (int)alloc.block().size, this.submitIndex
		);
		ByteBuffer cpuBuffer = MemoryUtil.memByteBuffer(alloc.block().hostPtr + alloc.offset(), (int)alloc.size());
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(apiBuffer, alloc.offset(), alloc.size()), cpuBuffer, () -> {});
	}

	@Override
	public GpuBufferSlice allocateGpu(
		final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
	) {
		assert size <= Integer.MAX_VALUE;
		TransientBlockAllocator.Allocation<Vk11TransientMemory.VulkanAllocation> alloc = this.gpuBlockAllocator
			.allocate(size, alignment, minimumAllocation, elementSize);
		Vk11TransientMemory.TransientGpuBuffer apiBuffer = new Vk11TransientMemory.TransientGpuBuffer(
			alloc.block().vkBuffer, usage, (int)alloc.block().size, this.submitIndex
		);
		return new GpuBufferSlice(apiBuffer, alloc.offset(), alloc.size());
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(
		final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
	) {
		assert size <= Integer.MAX_VALUE;
		TransientBlockAllocator.Allocation<Pair<Vk11TransientMemory.VulkanAllocation, Vk11TransientMemory.VulkanAllocation>> alloc = this.gpuMappedBlockAllocator
			.allocate(size, alignment, minimumAllocation, elementSize);
		Vk11TransientMemory.TransientGpuBuffer apiBuffer = new Vk11TransientMemory.TransientGpuBuffer(
			alloc.block().second().vkBuffer, usage, (int)alloc.block().first().size, this.submitIndex
		);
		ByteBuffer cpuBuffer = MemoryUtil.memByteBuffer(alloc.block().first().hostPtr + alloc.offset(), (int)alloc.size());
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(apiBuffer, alloc.offset(), alloc.size()), cpuBuffer, () -> {});
	}

	@Override
	public GpuBufferSlice uploadStaging(
		final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
	) {
		return this.upload(data, alignment, usage, minimumAllocation, elementSize, true);
	}

	@Override
	public GpuBufferSlice uploadGpu(
		final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
	) {
		return this.upload(data, alignment, usage, minimumAllocation, elementSize, false);
	}

	public GpuBufferSlice upload(
		final List<ByteBuffer> data,
		final long alignment,
		final @GpuBuffer.Usage int usage,
		final long minimumAllocation,
		final long elementSize,
		final boolean staging
	) {
		long totalSize = 0L;

		for (ByteBuffer buffer : data) {
			totalSize += buffer.remaining();
			totalSize = Mth.roundToward(totalSize, alignment);
		}

		try (GpuBufferSlice.MappedView mapped = staging
				? this.allocateStaging(totalSize, alignment, usage, minimumAllocation, elementSize)
				: this.allocateGpuMapped(totalSize, alignment, usage, minimumAllocation, elementSize)) {
			long mappedPtr = MemoryUtil.memAddress(mapped.data());
			long offset = 0L;

			for (ByteBuffer buffer : data) {
				MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPtr + offset, Math.min(mapped.slice().length() - offset, buffer.remaining()));
				offset += buffer.remaining();
				offset = Mth.roundToward(offset, alignment);
				if (offset >= mapped.slice().length()) {
					break;
				}
			}

			return mapped.slice();
		}
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		return this.multiUpload(data, alignment, usage, true);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		return this.multiUpload(data, alignment, usage, false);
	}

	public List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final boolean staging) {
		ReferenceArrayList<GpuBufferSlice> uploadedBuffers = new ReferenceArrayList<>();
		uploadedBuffers.size(data.size());
		TransientBlockAllocator<?> allocatorInUse = staging ? this.stagingBlockAllocator : this.gpuMappedBlockAllocator;
		IntArrayList sortedDataIndices = IntArrayList.toList(IntStream.range(0, data.size()));
		sortedDataIndices.sort(IntComparator.comparing(index -> data.get(index).remaining()));

		while (!sortedDataIndices.isEmpty()) {
			boolean allocatedAnything = false;

			for (int i = sortedDataIndices.size() - 1; i >= 0; i--) {
				int bufferIndex = sortedDataIndices.getInt(i);
				ByteBuffer currentBuffer = data.get(bufferIndex);
				if (allocatorInUse.canAllocateInCurrentBlock(currentBuffer.remaining(), alignment)) {
					sortedDataIndices.removeInt(i);

					try (GpuBufferSlice.MappedView view = staging
							? this.allocateStaging(currentBuffer.remaining(), alignment, usage)
							: this.allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
						MemoryUtil.memCopy(currentBuffer, view.data());
						uploadedBuffers.set(bufferIndex, view.slice());
					}

					allocatedAnything = true;
					break;
				}
			}

			if (!allocatedAnything) {
				int bufferIndex = sortedDataIndices.popInt();
				ByteBuffer currentBuffer = data.get(bufferIndex);

				try (GpuBufferSlice.MappedView view = this.allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
					MemoryUtil.memCopy(currentBuffer, view.data());
					uploadedBuffers.set(bufferIndex, view.slice());
				}
			}
		}

		return uploadedBuffers;
	}

	@Environment(EnvType.CLIENT)
	private class TransientGpuBuffer extends Vk11GpuBuffer {
		private boolean closed = false;
		private final long bufferSubmitIndex;

		public TransientGpuBuffer(final long vkBuffer, final @GpuBuffer.Usage int usage, final int size, final long bufferSubmitIndex) {
			super(vkBuffer, usage, size);
			this.bufferSubmitIndex = bufferSubmitIndex;
		}

		@Override
		public void destroy() {
		}

		@Override
		public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
			throw new IllegalStateException("Cannot map transient buffer");
		}

		@Override
		public boolean isClosed() {
			if (this.closed) {
				return true;
			}

			this.closed = this.bufferSubmitIndex < Vk11TransientMemory.this.submitIndex;
			return this.closed;
		}

		@Override
		public void close() {
			this.closed = true;
		}

		@Override
		public GpuBufferSlice slice(final long offset, final long length) {
			throw new IllegalStateException("Cannot slice transient buffer");
		}

		@Override
		public GpuBufferSlice slice() {
			throw new IllegalStateException("Cannot slice transient buffer");
		}
	}

	@Environment(EnvType.CLIENT)
	private record VulkanAllocation(long vkBuffer, long vmaAllocation, long hostPtr, long size) {
	}
}
