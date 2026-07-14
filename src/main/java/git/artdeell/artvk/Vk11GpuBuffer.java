package git.artdeell.artvk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

@Environment(EnvType.CLIENT)
public abstract class Vk11GpuBuffer extends GpuBuffer implements Destroyable {
	private final long vkBuffer;

	public Vk11GpuBuffer(final long vkBuffer, final @GpuBuffer.Usage int usage, final long size) {
		super(usage, size);
		this.vkBuffer = vkBuffer;
	}

	public long vkBuffer() {
		return this.vkBuffer;
	}

	@Environment(EnvType.CLIENT)
	public static class Direct extends Vk11GpuBuffer {
		private boolean closed;
		protected final Vk11Device device;
		private final long vmaAllocation;
		private int mappingRefCount;

		public Direct(
			final Vk11Device device,
			final @Nullable Supplier<String> label,
			final @GpuBuffer.Usage int usage,
			final long size,
			final boolean forceHostVisibleAllocation
		) {
			this.device = device;

			long vkBuffer;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
				bufferCreateInfo.size(size);
				bufferCreateInfo.usage(Vk11Const.bufferUsageToVk(usage));
				bufferCreateInfo.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
				bufferCreateInfo.pQueueFamilyIndices(null);
				VmaAllocationCreateInfo allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
				allocCreateInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
				if (forceHostVisibleAllocation) {
					allocCreateInfo.requiredFlags(allocCreateInfo.requiredFlags() | VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
				}

				if (Vk11Utils.hasAnyBit(usage, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) {
					allocCreateInfo.requiredFlags(allocCreateInfo.requiredFlags() | VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
					if (Vk11Utils.hasAnyBit(usage, GpuBuffer.USAGE_MAP_READ)) {
						allocCreateInfo.preferredFlags(allocCreateInfo.preferredFlags() | VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
						allocCreateInfo.flags(allocCreateInfo.flags() | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_ALLOW_TRANSFER_INSTEAD_BIT);
					} else {
						allocCreateInfo.flags(allocCreateInfo.flags() | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
					}
				}

				if (Vk11Utils.hasAnyBit(usage, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_HINT_CLIENT_STORAGE)) {
					allocCreateInfo.usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
				}

				LongBuffer bufferPtr = stack.callocLong(1);
				PointerBuffer allocPtr = stack.callocPointer(1);
				int result = Vma.vmaCreateBuffer(device.vma(), bufferCreateInfo, allocCreateInfo, bufferPtr, allocPtr, null);
				Vk11Utils.crashIfFailure(result, "Failed to allocate VkBuffer");
				vkBuffer = bufferPtr.get(0);
				this.vmaAllocation = allocPtr.get(0);
				if (label != null) {
					device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_BUFFER, vkBuffer, label);
				}
			}

			super(vkBuffer, usage, size);
			this.closed = false;
			this.mappingRefCount = 0;
		}

		@Override
		public void destroy() {
			Vma.vmaDestroyBuffer(this.device.vma(), this.vkBuffer(), this.vmaAllocation);
		}

		@Override
		public boolean isClosed() {
			return this.closed;
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				if (this.mappingRefCount != 0) {
					throw new IllegalStateException("Attempt to close a mapped buffer");
				}

				this.device.createCommandEncoder().queueForDestroy(this);
			}
		}

		@Override
		public @NotNull GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
			if (this.isClosed()) {
				throw new IllegalStateException("Buffer already closed");
			}

			if (!read && !write) {
				throw new IllegalArgumentException("At least read or write must be true");
			}

			if (read && (this.usage() & 1) == 0) {
				throw new IllegalStateException("Buffer is not readable");
			}

			if (write && (this.usage() & 2) == 0) {
				throw new IllegalStateException("Buffer is not writable");
			}

			if (offset + length > this.size()) {
				throw new IllegalArgumentException(
					"Cannot map more data than this buffer can hold (attempting to map " + length + " bytes at offset " + offset + " from " + this.size() + " size buffer)"
				);
			}

			if (length > 2147483647L) {
				throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
			}

			if (offset >= 0L && length >= 0L) {
				this.mappingRefCount++;

				try (MemoryStack stack = MemoryStack.stackPush()) {
					PointerBuffer pointer = stack.callocPointer(1);
					Vk11Utils.crashIfFailure(Vma.vmaMapMemory(this.device.vma(), this.vmaAllocation, pointer), "Failed to map buffer");
					ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(pointer.get(0) + offset, (int)length);
					return new GpuBufferSlice.MappedView(this.slice(offset, length), byteBuffer, new Runnable() {
						private boolean closed = false;

						@Override
						public void run() {
							if (!this.closed) {
								this.closed = true;
								Direct.this.mappingRefCount--;
								Vma.vmaUnmapMemory(Direct.this.device.vma(), Direct.this.vmaAllocation);
							}
						}
					});
				}
			} else {
				throw new IllegalArgumentException("Offset or length must be positive integer values");
			}
		}
	}
}
