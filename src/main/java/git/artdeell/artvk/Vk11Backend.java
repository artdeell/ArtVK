package git.artdeell.artvk;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import git.artdeell.ArtVK;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap.Entry;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11Backend implements GpuBackend {
    public static final String NAME = "ArtVK";
    public static final Set<String> REQUIRED_DEVICE_EXTENSIONS = Set.of(
		"VK_KHR_swapchain"
	);

    private boolean libraryLoaded;
    private @Nullable BackendCreationException libraryLoadFailure;

	@Override
	public @NotNull String getName() {
		return NAME;
    }

	@Override
	public void loadLibrary() throws BackendCreationException {
		if (!this.libraryLoaded) {
			if (this.libraryLoadFailure != null) {
				throw this.libraryLoadFailure;
			}

			if (!NativeLibrariesBootstrap.isVulkanLoaderAvailable()) {
				this.libraryLoadFailure = new BackendCreationException("Vulkan loader library is missing", BackendCreationException.Reason.VULKAN_LOADER_MISSING);
				throw this.libraryLoadFailure;
			}

			if (!SDLVulkan.SDL_Vulkan_LoadLibrary(((SharedLibrary) VK.getFunctionProvider()).getPath())) {
				this.libraryLoadFailure = new BackendCreationException(
					"Vulkan is not supported: " + Objects.requireNonNullElse(SDLError.SDL_GetError(), "<no error>"), BackendCreationException.Reason.PLATFORM_ERROR
				);
				throw this.libraryLoadFailure;
			}

			if (VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr") != SDLVulkan.SDL_Vulkan_GetVkGetInstanceProcAddr()) {
				this.libraryLoadFailure = new BackendCreationException("vkGetInstanceProcAddr mismatch", BackendCreationException.Reason.PLATFORM_ERROR);
				SDLVulkan.SDL_Vulkan_UnloadLibrary();
				throw this.libraryLoadFailure;
			}

			this.libraryLoaded = true;
		}
	}

	@Override
	public void unloadLibrary() {
		if (this.libraryLoaded) {
			SDLVulkan.SDL_Vulkan_UnloadLibrary();
			this.libraryLoaded = false;
		}
	}

	@Override
	public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, 268435456L | flags);
	}

	@Override
	public @NotNull GpuDevice createDevice(final @NotNull GpuDebugOptions debugOptions) throws BackendCreationException {
		if (!NativeLibrariesBootstrap.isVulkanLoaderAvailable()) {
			throw new BackendCreationException("Vulkan loader library is missing", BackendCreationException.Reason.VULKAN_LOADER_MISSING);
		}

		Vk11Instance instance = null;
		Vk11PhysicalDevice physicalDevice = null;
		VkDevice device = null;
		IntVMA vma = null;

		try {
			boolean renderdocAttached = "1".equals(System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"));
			boolean validation = "true".equalsIgnoreCase(System.getProperty("artvk.validation", "false"));
            boolean useDebugLabels = debugOptions.useLabels() || renderdocAttached;
			instance = new Vk11Instance(debugOptions.logLevel(), useDebugLabels, validation);
			physicalDevice = findPhysicalDevice(instance);
			device = createVkDevice(physicalDevice);
            // VMA calls Vulkan APIs so pick the lowest of either the instance or device version
			vma = new IntVMA(
                    device,
                    Math.min(instance.apiTarget, physicalDevice.normalizedApiVersion())
            );
		} catch (BackendCreationException e) {
			if(vma != null) vma.close();

			if (device != null) VK10.vkDestroyDevice(device, null);

			if (physicalDevice != null) physicalDevice.close();

			if (instance != null) instance.close();

			throw e;
		}

		return new FrontendGpuDevice(
			new Vk11Device(instance, physicalDevice, device, vma)
		);
	}

	private static Vk11PhysicalDevice findPhysicalDevice(final Vk11Instance instance) throws BackendCreationException {
		VkPhysicalDevice firstDevice = null;
		VkPhysicalDevice selectedDevice = null;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer intBuffer = stack.callocInt(1);
			Vk11Utils.throwIfFailure(
				VK10.vkEnumeratePhysicalDevices(instance.vkInstance(), intBuffer, null),
				"Failed to get number of physical devices",
				BackendCreationException.Reason.VULKAN_NO_DEVICE
			);
			if (intBuffer.get(0) == 0) {
				throw new BackendCreationException("No Vulkan capable devices", BackendCreationException.Reason.VULKAN_NO_DEVICE);
			}

			PointerBuffer pPhysicalDevices = stack.callocPointer(intBuffer.get(0));
			Vk11Utils.throwIfFailure(
				VK10.vkEnumeratePhysicalDevices(instance.vkInstance(), intBuffer, pPhysicalDevices),
				"Failed to get physical devices",
				BackendCreationException.Reason.VULKAN_NO_DEVICE
			);
			int numDevices = intBuffer.get(0);
			if (numDevices == 0) {
				throw new BackendCreationException("No Vulkan capable devices", BackendCreationException.Reason.VULKAN_NO_DEVICE);
			}

			for (int i = 0; i < numDevices; i++) {
				if (pPhysicalDevices.get(i) != 0L) {
					VkPhysicalDevice currentDevice = new VkPhysicalDevice(pPhysicalDevices.get(i), instance.vkInstance());
					if (firstDevice == null) {
						firstDevice = currentDevice;
					}

					if (isDeviceSuitable(instance, currentDevice)) {
						if (selectedDevice == null) {
							selectedDevice = currentDevice;
						} else if (isDeviceDiscrete(currentDevice) && !isDeviceDiscrete(selectedDevice)) {
							ArtVK.LOGGER.info("Preferring discrete GPU: {}", getDeviceName(currentDevice));
							selectedDevice = currentDevice;
							break;
						}
					}
				}
			}
		}

		if (firstDevice == null) {
			throw new BackendCreationException("No Vulkan capable devices", BackendCreationException.Reason.VULKAN_NO_DEVICE);
		}

		if (selectedDevice == null) {
            throw new BackendCreationException("No compatible devices found", BackendCreationException.Reason.VULKAN_NO_DEVICE);
		}

		return new Vk11PhysicalDevice(selectedDevice, instance.propertiesMode);
	}

	private static boolean isDeviceSuitable(final Vk11Instance instance, final VkPhysicalDevice vkPhysicalDevice) throws BackendCreationException {
		try (
                Vk11PhysicalDevice physicalDevice = new Vk11PhysicalDevice(vkPhysicalDevice, instance.propertiesMode);
		) {
			String deviceName = physicalDevice.properties().deviceName();
            Set<String> missingExtensions = physicalDevice.getMissingExtensions(REQUIRED_DEVICE_EXTENSIONS);
            boolean isSuitableDevice = true;

            if (physicalDevice.graphicsQueueFamilyAndIndex() == null) {
                ArtVK.LOGGER.warn("Device [{}] does not have a graphics queue", deviceName);
                isSuitableDevice = false;
            }

            if (!missingExtensions.isEmpty()) {
                ArtVK.LOGGER.warn("Device [{}] does not support required extensions, missing: {}", deviceName, missingExtensions);
                isSuitableDevice = false;
            }

            if (isSuitableDevice) {
                ArtVK.LOGGER.debug("Device [{}] is suitable", deviceName);
            }

            return isSuitableDevice;
		}
	}

	private static boolean isDeviceDiscrete(final VkPhysicalDevice vkPhysicalDevice) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties2 deviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
			VK11.vkGetPhysicalDeviceProperties2(vkPhysicalDevice, deviceProperties);
			return deviceProperties.properties().deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
		}
	}

	private static String getDeviceName(final VkPhysicalDevice vkPhysicalDevice) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties2 deviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
			VK11.vkGetPhysicalDeviceProperties2(vkPhysicalDevice, deviceProperties);
			return deviceProperties.properties().deviceNameString();
		}
	}

	private static VkDevice createVkDevice(final Vk11PhysicalDevice physicalDevice) throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {

			Int2IntMap queuesToCreate = physicalDevice.queueFamilyCreateInfoMap();
			Buffer queueCreationInfo = VkDeviceQueueCreateInfo.calloc(queuesToCreate.size(), stack);

			for (Entry familyCount : queuesToCreate.int2IntEntrySet()) {
				queueCreationInfo.sType$Default();
				queueCreationInfo.queueFamilyIndex(familyCount.getIntKey());
				queueCreationInfo.pQueuePriorities(stack.callocFloat(familyCount.getIntValue()));
				queueCreationInfo.position(queueCreationInfo.position() + 1);
			}

			queueCreationInfo.position(0);

			VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType$Default();
			deviceCreateInfo.pQueueCreateInfos(queueCreationInfo);

            physicalDevice.features().addFeaturesAndExtensions(stack, physicalDevice, deviceCreateInfo);

			PointerBuffer pointer = stack.callocPointer(1);
			Vk11Utils.throwIfFailure(
				VK10.vkCreateDevice(physicalDevice.vkPhysicalDevice(), deviceCreateInfo, null, pointer),
				"Failed to create device",
				BackendCreationException.Reason.VULKAN_NO_DEVICE
			);
			return new VkDevice(pointer.get(0), physicalDevice.vkPhysicalDevice(), deviceCreateInfo);
		}
	}
}
