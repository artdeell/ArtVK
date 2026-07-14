package git.artdeell.artvk;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import git.artdeell.ArtVK;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap.Entry;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11Backend implements GpuBackend {
    public static final Set<String> REQUIRED_DEVICE_EXTENSIONS = Set.of(
		"VK_KHR_swapchain"
	);

	@Override
	public @NotNull String getName() {
		return "ArtVK";
	}

	@Override
	public void setWindowHints() {
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.@Nullable Error error) throws BackendCreationException {
		if (error != null) {
			throw new BackendCreationException(String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error()), BackendCreationException.Reason.GLFW_ERROR);
		} else {
			throw new BackendCreationException("Failed to create window for Vulkan", BackendCreationException.Reason.GLFW_ERROR);
		}
	}

	@Override
	public @NotNull GpuDevice createDevice(
            final long window,
            final @NotNull ShaderSource defaultShaderSource,
            final @NotNull GpuDebugOptions debugOptions,
            final @NotNull Runnable criticalShaderLoader
	) throws BackendCreationException {
		if (!NativeLibrariesBootstrap.isVulkanLoaderAvailable()) {
			throw new BackendCreationException("Vulkan loader library is missing", BackendCreationException.Reason.VULKAN_LOADER_MISSING);
		}

		if (!GLFWVulkan.glfwVulkanSupported()) {
			throw new BackendCreationException("Vulkan is not supported", BackendCreationException.Reason.GLFW_ERROR);
		}

		Set<String> deviceExtensions = new HashSet<>(REQUIRED_DEVICE_EXTENSIONS);
		Vk11Instance instance = null;
		Vk11PhysicalDevice physicalDevice = null;
		VkDevice device = null;
		IntVMA vma = null;

		try {
			boolean renderdocAttached = "1".equals(System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"));
			boolean validation = "true".equalsIgnoreCase(System.getProperty("dogshitvk.validation", "false"));
            boolean useDebugLabels = debugOptions.useLabels() || renderdocAttached;
			instance = new Vk11Instance(debugOptions.logLevel(), useDebugLabels, validation);
			physicalDevice = findPhysicalDevice(instance);

			if (physicalDevice.hasDeviceExtension("VK_KHR_portability_subset")) {
				deviceExtensions.add("VK_KHR_portability_subset");
			}

            if(useDebugLabels) {
                if (physicalDevice.hasDeviceExtension("VK_AMD_buffer_marker")) {
                    deviceExtensions.add("VK_AMD_buffer_marker");
                } else if (physicalDevice.hasDeviceExtension("VK_NV_device_diagnostic_checkpoints")) {
                    deviceExtensions.add("VK_NV_device_diagnostic_checkpoints");
                }
            }

			if (physicalDevice.hasDeviceExtension("VK_EXT_multi_draw")) {
				deviceExtensions.add("VK_EXT_multi_draw");
			}

            if(physicalDevice.hasDeviceExtension("VK_EXT_vertex_attribute_divisor")) {
                deviceExtensions.add("VK_EXT_vertex_attribute_divisor");
            }

			device = createVkDevice(deviceExtensions, physicalDevice);
			vma = new IntVMA(device);
		} catch (BackendCreationException e) {
			if(vma != null) vma.close();

			if (device != null) VK10.vkDestroyDevice(device, null);

			if (physicalDevice != null) physicalDevice.close();

			if (instance != null) instance.close();

			throw e;
		}

		return new GpuDevice(
			new Vk11Device(defaultShaderSource, instance, physicalDevice, deviceExtensions, device, vma), criticalShaderLoader
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

					if (deviceMeetsFeatureQueryRequirements(currentDevice) && isDeviceSuitable(currentDevice)) {
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
			throwForMissingRequirements(firstDevice);
			assert false;
		}

		return new Vk11PhysicalDevice(selectedDevice);
	}

	private static boolean deviceMeetsFeatureQueryRequirements(final VkPhysicalDevice vkPhysicalDevice) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceProperties(vkPhysicalDevice, properties);
			return properties.apiVersion() >= VK11.VK_API_VERSION_1_1;
		}
	}

	private static boolean isDeviceSuitable(final VkPhysicalDevice vkPhysicalDevice) throws BackendCreationException {
		try (
			Vk11PhysicalDevice physicalDevice = new Vk11PhysicalDevice(vkPhysicalDevice);
		) {
			String deviceName = physicalDevice.deviceName();
            Set<String> missingExtensions = physicalDevice.getMissingExtensions(REQUIRED_DEVICE_EXTENSIONS);
            boolean isSuitableDevice = true;
            if (physicalDevice.vkPhysicalDeviceProperties().apiVersion() < VK11.VK_API_VERSION_1_1) {
                ArtVK.LOGGER.warn("Device [{}] does not support Vulkan 1.1", deviceName);
                isSuitableDevice = false;
            }

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

	private static void throwForMissingRequirements(final VkPhysicalDevice vkPhysicalDevice) throws BackendCreationException {
		List<String> missingCapabilities = new ReferenceArrayList<>();
		BackendCreationException.Reason mostProminentReason = BackendCreationException.Reason.OTHER;
		if (!deviceMeetsFeatureQueryRequirements(vkPhysicalDevice)) {
			throw new BackendCreationException("Device missing capabilities", BackendCreationException.Reason.VULKAN_DEVICE_VERSION_TOO_LOW, List.of("VULKAN_CORE_1_1"));
		}

		try (
			Vk11PhysicalDevice physicalDevice = new Vk11PhysicalDevice(vkPhysicalDevice);
			MemoryStack stack = MemoryStack.stackPush();
		) {
			VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			VK11.vkGetPhysicalDeviceFeatures2(vkPhysicalDevice, deviceFeatures);

			Set<String> missingExtensions = physicalDevice.getMissingExtensions(REQUIRED_DEVICE_EXTENSIONS);
			if (!missingExtensions.isEmpty()) {
				mostProminentReason = BackendCreationException.Reason.VULKAN_MISSING_EXTENSION;
				missingCapabilities.addAll(missingExtensions);
			}

			if (physicalDevice.graphicsQueueFamilyAndIndex() == null) {
				mostProminentReason = BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE;
				missingCapabilities.add("COMBINED_GRAPHICS_COMPUTE_PRESENT_QUEUE");
			}

			if (physicalDevice.vkPhysicalDeviceProperties().apiVersion() < VK11.VK_API_VERSION_1_1) {
				mostProminentReason = BackendCreationException.Reason.VULKAN_DEVICE_VERSION_TOO_LOW;
				missingCapabilities.add("VULKAN_CORE_1_1");
			}
		}

		throw new BackendCreationException("Device missing capabilities", mostProminentReason, missingCapabilities);
	}

	private static VkDevice createVkDevice(
		final Collection<String> deviceExtensions, final Vk11PhysicalDevice physicalDevice
	) throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 availableFeatures = physicalDevice.vkPhysicalDeviceFeatures();

			VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			// Enable required VK10 features
			deviceFeatures.features().multiDrawIndirect(availableFeatures.features().multiDrawIndirect());
			deviceFeatures.features().fillModeNonSolid(availableFeatures.features().fillModeNonSolid());
			deviceFeatures.features().samplerAnisotropy(availableFeatures.features().samplerAnisotropy());

			// Enable VK10 shaderDrawParameters via pNext chain
			VkPhysicalDeviceVulkan11Features vk11Features = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
			vk11Features.shaderDrawParameters(true);
			deviceFeatures.pNext(vk11Features.address());

			// Enable VK10 vertexAttributeDivisor via pNext chain (EXT extension)
			VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT vertexDivisorFeatures = VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT.calloc(stack).sType$Default();
			vertexDivisorFeatures.vertexAttributeInstanceRateDivisor(true);
			vk11Features.pNext(vertexDivisorFeatures.address());

			Int2IntMap queuesToCreate = physicalDevice.queueFamilyCreateInfoMap();
			Buffer queueCreationInfo = VkDeviceQueueCreateInfo.calloc(queuesToCreate.size(), stack);

			for (Entry familyCount : queuesToCreate.int2IntEntrySet()) {
				queueCreationInfo.sType$Default();
				queueCreationInfo.queueFamilyIndex(familyCount.getIntKey());
				queueCreationInfo.pQueuePriorities(stack.callocFloat(familyCount.getIntValue()));
				queueCreationInfo.position(queueCreationInfo.position() + 1);
			}

			queueCreationInfo.position(0);
			PointerBuffer enabledExtensionsBuffer = stack.callocPointer(deviceExtensions.size());

			for (String name : deviceExtensions) {
				enabledExtensionsBuffer.put(stack.UTF8(name));
			}

			enabledExtensionsBuffer.flip();
			VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType$Default();
			deviceCreateInfo.pNext(deviceFeatures.address());
			deviceCreateInfo.pQueueCreateInfos(queueCreationInfo);
			deviceCreateInfo.ppEnabledExtensionNames(enabledExtensionsBuffer);
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
