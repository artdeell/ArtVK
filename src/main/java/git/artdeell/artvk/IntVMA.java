package git.artdeell.artvk;

import com.mojang.blaze3d.systems.BackendCreationException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;

public class IntVMA {
    public final long ptr;
    private final VkAllocationCallbacks allocationCallbacks;

    public IntVMA(VkDevice vkDevice) throws BackendCreationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions vmaVulkanFunctions = VmaVulkanFunctions.calloc(stack).set(vkDevice.getPhysicalDevice().getInstance(), vkDevice);
            allocationCallbacks = VkAllocationCallbacks.calloc();
            allocationCallbacks.pfnAllocation(this::allocate);
            allocationCallbacks.pfnFree(this::free);
            allocationCallbacks.pfnReallocation(this::reallocate);
            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .instance(vkDevice.getPhysicalDevice().getInstance())
                    .vulkanApiVersion(VK11.VK_API_VERSION_1_1)
                    .device(vkDevice)
                    .physicalDevice(vkDevice.getPhysicalDevice())
                    .pVulkanFunctions(vmaVulkanFunctions)
                    .pAllocationCallbacks(allocationCallbacks);
            PointerBuffer pointer = stack.callocPointer(1);
            Vk11Utils.throwIfFailure(Vma.vmaCreateAllocator(createInfo, pointer), "Failed to create VMA allocator", BackendCreationException.Reason.OTHER);
            ptr = pointer.get(0);
        }
    }

    public void close() {
        Vma.vmaDestroyAllocator(ptr);
        allocationCallbacks.free();
    }

    private long allocate(long pUserData, long size, long alignment, int allocationScope) {
        if(alignment < Pointer.CLONG_SIZE) alignment = Pointer.CLONG_SIZE;
        return MemoryUtil.nmemAlignedAlloc(alignment, size);
    }

    private long reallocate(long pUserData, long pOriginal, long size, long alignment, int allocationScope) {
        throw new UnsupportedOperationException();
    }

    private void free(long pUserData, long memory) {
        MemoryUtil.nmemFree(memory);
    }

}
