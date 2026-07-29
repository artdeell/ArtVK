package git.artdeell.artvk;

import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.DeviceType;
import git.artdeell.ArtVK;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.ints.IntIntPair;

import java.nio.IntBuffer;
import java.util.*;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkExtensionProperties.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11PhysicalDevice implements AutoCloseable {
    public static final byte PROPERTIES_VK10 = 0;
    public static final byte PROPERTIES_VK11 = 1;
    public static final byte PROPERTIES_KHR = 2;

	private final VkPhysicalDevice vkPhysicalDevice;
	private final Buffer vkDeviceExtensions;

    private final Properties properties;
	private final Features features;


	private final Int2IntMap queueFamilyCreateInfoMap;
	private final @Nullable IntIntPair graphicsQueueFamilyAndIndex;
	private final @Nullable IntIntPair computeQueueFamilyAndIndex;
	private final @Nullable IntIntPair transferQueueFamilyAndIndex;

	public Vk11PhysicalDevice(final VkPhysicalDevice vkPhysicalDevice, byte propertiesMode) throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			this.vkPhysicalDevice = vkPhysicalDevice;
			IntBuffer intBuffer = stack.callocInt(1);
			Vk11Utils.throwIfFailure(
				VK10.vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String)null, intBuffer, null),
				"Failed to get number of device extension properties",
				BackendCreationException.Reason.VULKAN_NO_DEVICE
			);
			this.vkDeviceExtensions = VkExtensionProperties.calloc(intBuffer.get(0));
			Vk11Utils.throwIfFailure(
				VK10.vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, (String)null, intBuffer, this.vkDeviceExtensions),
				"Failed to get extension properties",
				BackendCreationException.Reason.VULKAN_NO_DEVICE
			);

            VkPhysicalDeviceProperties physicalDeviceProperties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(vkPhysicalDevice, physicalDeviceProperties);

            properties = Properties.detectProperties(
                    this,
                    propertiesMode
            );

            features = Features.detectFeatures(
                    this,
                    physicalDeviceProperties.apiVersion(),
                    propertiesMode
            );

			VK10.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, intBuffer, null);
			org.lwjgl.vulkan.VkQueueFamilyProperties.Buffer vkQueueFamilyProps = VkQueueFamilyProperties.calloc(intBuffer.get(0), stack);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, intBuffer, vkQueueFamilyProps);
			int graphicsQueueFamily = -1;
			int computeQueueFamily = -1;
			int transferQueueFamily = -1;
			int computeQueueFamilyBits = -1;
			int transferQueueFamilyBits = -1;
			int numQueueFamilies = vkQueueFamilyProps.capacity();

			for (int i = 0; i < numQueueFamilies; i++) {
				int familyUsedQueues = 0;
				VkQueueFamilyProperties queueFamilyProperties = vkQueueFamilyProps.get(i);
				if (graphicsQueueFamily == -1
					&& Vk11Utils.hasAllBits(queueFamilyProperties.queueFlags(), VK10.VK_QUEUE_GRAPHICS_BIT | VK10.VK_QUEUE_COMPUTE_BIT)
					&& GLFWVulkan.glfwGetPhysicalDevicePresentationSupport(vkPhysicalDevice.getInstance(), vkPhysicalDevice, i)) {
					graphicsQueueFamily = i;
					familyUsedQueues++;
				}

				if (queueFamilyProperties.queueCount() > familyUsedQueues) {
					if (Vk11Utils.hasAllBits(queueFamilyProperties.queueFlags(), VK10.VK_QUEUE_COMPUTE_BIT)
						&& (computeQueueFamily == -1 || Integer.bitCount(queueFamilyProperties.queueFlags()) <= Integer.bitCount(computeQueueFamilyBits))) {
						computeQueueFamily = i;
						computeQueueFamilyBits = queueFamilyProperties.queueFlags();
						familyUsedQueues++;
					}

					if (queueFamilyProperties.queueCount() > familyUsedQueues
						&& Vk11Utils.hasAnyBit(queueFamilyProperties.queueFlags(), VK10.VK_QUEUE_GRAPHICS_BIT | VK10.VK_QUEUE_COMPUTE_BIT | VK10.VK_QUEUE_TRANSFER_BIT)
						&& (transferQueueFamily == -1 || Integer.bitCount(queueFamilyProperties.queueFlags()) <= Integer.bitCount(transferQueueFamilyBits))) {
						transferQueueFamily = i;
						transferQueueFamilyBits = queueFamilyProperties.queueFlags();
						familyUsedQueues++;
					}
				}
			}

			Int2IntMap familyMap = new Int2IntArrayMap();
			int graphicsQueueIndex = familyMap.put(graphicsQueueFamily, familyMap.get(graphicsQueueFamily) + 1);
			int computeQueueIndex = familyMap.put(computeQueueFamily, familyMap.get(computeQueueFamily) + 1);
			int transferQueueIndex = familyMap.put(transferQueueFamily, familyMap.get(transferQueueFamily) + 1);
			familyMap.remove(-1);
			this.queueFamilyCreateInfoMap = Int2IntMaps.unmodifiable(familyMap);
			this.graphicsQueueFamilyAndIndex = graphicsQueueFamily == -1 ? null : new IntIntImmutablePair(graphicsQueueFamily, graphicsQueueIndex);
			this.computeQueueFamilyAndIndex = computeQueueFamily == -1 ? null : new IntIntImmutablePair(computeQueueFamily, computeQueueIndex);
			this.transferQueueFamilyAndIndex = transferQueueFamily == -1 ? null : new IntIntImmutablePair(transferQueueFamily, transferQueueIndex);
		}
	}

	@Override
	public void close() {
		this.vkDeviceExtensions.free();
	}


	public String vendorName() {
		int vendorId = properties().vendorId;

		return switch (vendorId) {
			case 0x1002, 0x1022 -> "AMD";
			case 0x1010 -> "Imagination Technologies";
			case 0x106B -> "Apple";
			case 0x10DE, 0x12D2 -> "NVIDIA";
			case 0x13B5 -> "ARM";
			case 0x1414 -> "Microsoft Corporation";
			case 0x14E4 -> "Broadcom";
			case 0x168C, 0x17CB, 0x1969, 0x5143 -> "Qualcomm";
			case 0x8086 -> "Intel";
			case 0x10005 -> "Mesa";
			case 0x1AE0 -> "Google";
			case 0x144D -> "Samsung";
			default -> String.format(Locale.ROOT, "0x%x", vendorId);
		};
	}

	public VkPhysicalDevice vkPhysicalDevice() {
		return this.vkPhysicalDevice;
	}

    public Features features() {
        return features;
    }

    public Properties properties() {
        return properties;
    }

	public boolean hasDeviceExtension(final String name) {
		return this.vkDeviceExtensions.stream().anyMatch(e -> e.extensionNameString().equals(name));
	}

    public int normalizedApiVersion() {
        return Vk11Utils.normalizeApiVersion(properties.apiVersion);
    }

	public Set<String> getMissingExtensions(final Collection<String> required) {
		Set<String> remaining = new HashSet<>(required);

		for (VkExtensionProperties extension : this.vkDeviceExtensions) {
			remaining.remove(extension.extensionNameString());
		}

		return remaining;
	}

	public Int2IntMap queueFamilyCreateInfoMap() {
		return this.queueFamilyCreateInfoMap;
	}

	public @Nullable IntIntPair graphicsQueueFamilyAndIndex() {
		return this.graphicsQueueFamilyAndIndex;
	}

	public @Nullable IntIntPair computeQueueFamilyAndIndex() {
		return this.computeQueueFamilyAndIndex;
	}

	public @Nullable IntIntPair transferQueueFamilyAndIndex() {
		return this.transferQueueFamilyAndIndex;
	}

	private static String getStandardEncodingVersion(final int version) {
		int major = version >>> 22 & 127;
		int minor = version >>> 12 & 1023;
		int patch = version & 4095;
		return String.format(Locale.ROOT, "%d.%d.%d", major, minor, patch);
	}

	private static String getDriverInfo(int apiVersion, VkPhysicalDeviceDriverProperties driverProperties) {
		String versionString = getStandardEncodingVersion(apiVersion);
        if(driverProperties == null) return String.format(Locale.ROOT, "%s (no extended info)", versionString);
        else return String.format(
			Locale.ROOT, "%s %s %s", versionString, driverProperties.driverNameString(), driverProperties.driverInfoString()
		);
	}

	public DeviceType deviceType() {
		return switch (properties.deviceType) {
			case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DeviceType.INTEGRATED;
			case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DeviceType.DISCRETE;
			case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DeviceType.VIRTUAL;
			case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> DeviceType.CPU;
			default -> DeviceType.OTHER;
		};
	}

    public record Features(
            boolean multiDrawIndirect,
            boolean fillModeNonSolid,
            boolean samplerAnisotropy,
            boolean shaderDrawParameters,
            boolean vertexAttributeDivisor,
            boolean multiDraw
    ) {

        private static Features detectBasicFeatures(Vk11PhysicalDevice device) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
                VK10.vkGetPhysicalDeviceFeatures(device.vkPhysicalDevice(), features);
                return new Features(
                        features.multiDrawIndirect(),
                        features.fillModeNonSolid(),
                        features.samplerAnisotropy(),
                        device.hasDeviceExtension(KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME),
                        false,
                        false
                );
            }

        }
        public static Features detectFeatures(
                Vk11PhysicalDevice device,
                int deviceApiVersion,
                byte queryMode
        ) {
            if (queryMode == PROPERTIES_VK10) {
                return detectBasicFeatures(device);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                VkPhysicalDeviceVertexAttributeDivisorFeatures divisorFeatures = null;
                VkPhysicalDeviceShaderDrawParametersFeatures drawParametersFeatures = null;
                VkPhysicalDeviceMultiDrawFeaturesEXT multiDrawFeatures = null;

                boolean shaderDrawParameters =
                        device.hasDeviceExtension(KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME) || deviceApiVersion >= VK11.VK_API_VERSION_1_1;
                boolean vertexAttribDivisor =
                        device.hasDeviceExtension(KHRVertexAttributeDivisor.VK_KHR_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME) || deviceApiVersion >= VK14.VK_API_VERSION_1_4;
                boolean multiDraw = device.hasDeviceExtension(EXTMultiDraw.VK_EXT_MULTI_DRAW_EXTENSION_NAME);

                if (shaderDrawParameters) {
                    drawParametersFeatures = VkPhysicalDeviceShaderDrawParametersFeatures.calloc(stack).sType$Default();
                    features.pNext(drawParametersFeatures);
                }

                if (vertexAttribDivisor) {
                    divisorFeatures = VkPhysicalDeviceVertexAttributeDivisorFeatures.calloc(stack).sType$Default();
                    features.pNext(divisorFeatures);
                }

                if (multiDraw) {
                    multiDrawFeatures = VkPhysicalDeviceMultiDrawFeaturesEXT.calloc(stack).sType$Default();
                    features.pNext(multiDrawFeatures);
                }

                switch (queryMode) {
                    case PROPERTIES_VK11 -> VK11.vkGetPhysicalDeviceFeatures2(device.vkPhysicalDevice(), features);
                    case PROPERTIES_KHR -> KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceFeatures2KHR(device.vkPhysicalDevice(), features);
                }

                return new Features(
                        features.features().multiDrawIndirect(),
                        features.features().fillModeNonSolid(),
                        features.features().samplerAnisotropy(),
                        shaderDrawParameters && drawParametersFeatures.shaderDrawParameters(),
                        vertexAttribDivisor && divisorFeatures.vertexAttributeInstanceRateDivisor(),
                        multiDraw && multiDrawFeatures.multiDraw()
                );
            }
        }

        public void addFeaturesAndExtensions(
                MemoryStack memoryStack,
                Vk11PhysicalDevice device,
                VkDeviceCreateInfo createInfo
        ) {
            VkPhysicalDeviceFeatures basicFeatures = VkPhysicalDeviceFeatures.calloc(memoryStack);
            basicFeatures.fillModeNonSolid(fillModeNonSolid);
            basicFeatures.multiDrawIndirect(multiDrawIndirect);
            basicFeatures.samplerAnisotropy(samplerAnisotropy);

            createInfo.pEnabledFeatures(basicFeatures);

            Set<String> enabledExts = new HashSet<>(3);

            if (shaderDrawParameters) {
                VkPhysicalDeviceShaderDrawParametersFeatures drawParametersFeatures =
                        VkPhysicalDeviceShaderDrawParametersFeatures.calloc(memoryStack).sType$Default();
                drawParametersFeatures.shaderDrawParameters(true);
                createInfo.pNext(drawParametersFeatures);
                if (device.hasDeviceExtension(KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME))
                    enabledExts.add(KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME);
            }

            if (vertexAttributeDivisor) {
                VkPhysicalDeviceVertexAttributeDivisorFeatures attributeDivisorFeatures =
                        VkPhysicalDeviceVertexAttributeDivisorFeatures.calloc(memoryStack).sType$Default();
                attributeDivisorFeatures.vertexAttributeInstanceRateDivisor(true);
                createInfo.pNext(attributeDivisorFeatures);
                if (device.hasDeviceExtension(EXTVertexAttributeDivisor.VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME))
                    enabledExts.add(EXTVertexAttributeDivisor.VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME);
                else if (device.hasDeviceExtension(KHRVertexAttributeDivisor.VK_KHR_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME))
                    enabledExts.add(KHRVertexAttributeDivisor.VK_KHR_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME);
            }

            if (multiDraw) {
                VkPhysicalDeviceMultiDrawFeaturesEXT multiDrawFeatures =
                        VkPhysicalDeviceMultiDrawFeaturesEXT.calloc(memoryStack).sType$Default();
                multiDrawFeatures.multiDraw(true);
                createInfo.pNext(multiDrawFeatures);
                enabledExts.add(EXTMultiDraw.VK_EXT_MULTI_DRAW_EXTENSION_NAME);
            }

            if(device.hasDeviceExtension(KHRMaintenance3.VK_KHR_MAINTENANCE3_EXTENSION_NAME)) {
                enabledExts.add(KHRMaintenance3.VK_KHR_MAINTENANCE3_EXTENSION_NAME);
            }

            if (device.hasDeviceExtension(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) {
                enabledExts.add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            }

            enabledExts.addAll(Vk11Backend.REQUIRED_DEVICE_EXTENSIONS);

            PointerBuffer extensionsBuffer = memoryStack.callocPointer(enabledExts.size());
            StringBuilder logExtensions = new StringBuilder()
                    .append("Enabled device extensions:");
            for (String s : enabledExts) {
                extensionsBuffer.put(memoryStack.UTF8(s));
                logExtensions.append(' ').append(s);
            }
            ArtVK.LOGGER.info(logExtensions.toString());
            extensionsBuffer.flip();

            createInfo.ppEnabledExtensionNames(extensionsBuffer);
        }
    }

    public record Properties (
            String deviceName,
            String driverInfo,
            int apiVersion,
            int vendorId,
            int driverId,
            int deviceType,
            float timestampPeriod,
            int maxSamplerAnisotropy,
            int minUniformBufferOffsetAlignment,
            int maxImageDimension2D,
            long maxMemoryAllocationSize,
            int maxColorAttachments
    ) {

        private static Properties basicProperties(VkPhysicalDeviceProperties properties) {
            VkPhysicalDeviceLimits limits = properties.limits();
            return new Properties(
                    properties.deviceNameString(),
                    getStandardEncodingVersion(properties.driverVersion()),
                    properties.apiVersion(),
                    properties.vendorID(),
                    -1,
                    properties.deviceType(),
                    limits.timestampPeriod(),
                    (int) limits.maxSamplerAnisotropy(),
                    (int) limits.minUniformBufferOffsetAlignment(),
                    limits.maxImageDimension2D(),
                    Long.MAX_VALUE,
                    limits.maxColorAttachments()
            );
        }

        private static Properties detectProperties(Vk11PhysicalDevice device, byte propertiesMode) {
            try(MemoryStack memoryStack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties2 extendedProperties = VkPhysicalDeviceProperties2.calloc(memoryStack).sType$Default();
                VkPhysicalDeviceProperties properties = extendedProperties.properties();
                VkPhysicalDeviceLimits limits = properties.limits();
                VkPhysicalDeviceMaintenance3Properties m3Properties = null;
                VkPhysicalDeviceDriverProperties driverProperties = null;
                VK10.vkGetPhysicalDeviceProperties(device.vkPhysicalDevice, properties);
                if(propertiesMode == PROPERTIES_VK10) return basicProperties(properties);

                int apiVersion = properties.apiVersion();

                if(device.hasDeviceExtension(KHRMaintenance3.VK_KHR_MAINTENANCE3_EXTENSION_NAME) || apiVersion >= VK11.VK_API_VERSION_1_1) {
                    m3Properties = VkPhysicalDeviceMaintenance3Properties.calloc(memoryStack).sType$Default();
                    extendedProperties.pNext(m3Properties);
                }
                if(device.hasDeviceExtension(KHRDriverProperties.VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME) || apiVersion >= VK12.VK_API_VERSION_1_2) {
                    driverProperties = VkPhysicalDeviceDriverProperties.calloc(memoryStack).sType$Default();
                    extendedProperties.pNext(driverProperties);
                }

                switch (propertiesMode) {
                    case PROPERTIES_VK11 -> VK11.vkGetPhysicalDeviceProperties2(device.vkPhysicalDevice(), extendedProperties);
                    case PROPERTIES_KHR -> KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceProperties2KHR(device.vkPhysicalDevice(), extendedProperties);
                }

                long maxMemoryAllocationSize = Long.MAX_VALUE;
                if(m3Properties != null && m3Properties.maxMemoryAllocationSize() > 0L) {
                    maxMemoryAllocationSize = m3Properties.maxMemoryAllocationSize();
                }

                return new Properties(
                        properties.deviceNameString(),
                        getDriverInfo(apiVersion, driverProperties),
                        properties.apiVersion(),
                        properties.vendorID(),
                        driverProperties != null ? driverProperties.driverID() : -1,
                        properties.deviceType(),
                        limits.timestampPeriod(),
                        (int) limits.maxSamplerAnisotropy(),
                        (int) limits.minUniformBufferOffsetAlignment(),
                        limits.maxImageDimension2D(),
                        maxMemoryAllocationSize,
                        limits.maxColorAttachments()
                );
            }
        }
    }
}
