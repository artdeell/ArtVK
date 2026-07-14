package git.artdeell.artvk;

import com.mojang.blaze3d.systems.BackendCreationException;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import git.artdeell.ArtVK;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkExtensionProperties.Buffer;

@Environment(EnvType.CLIENT)
public class Vk11Instance implements AutoCloseable {
    private static final String APPLICATION_NAME = "Minecraft Java Edition (ArtVK renderer)";
	private static final int APPLICATION_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();
	private static final String ENGINE_NAME = "MinecraftJE-ArtVK";
	private static final int ENGINE_VERSION = 0;
	private final Set<String> enabledExtensions = new HashSet<>();
	private final VkInstance vkInstance;
	private final Vk11Debug debug;

	protected Vk11Instance(final int debugVerbosity, boolean wantsDebugLabels, final boolean validation) throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
				.sType$Default()
				.pApplicationName(stack.UTF8(APPLICATION_NAME))
				.applicationVersion(APPLICATION_VERSION)
				.pEngineName(stack.UTF8(ENGINE_NAME))
				.engineVersion(ENGINE_VERSION)
				.apiVersion(VK11.VK_API_VERSION_1_1);
			List<String> validationLayers = this.getSupportedValidationLayers();
			PointerBuffer requiredLayers = null;
			if (validation) {
				if (validationLayers.contains("VK_LAYER_KHRONOS_validation")) {
					requiredLayers = stack.callocPointer(1);
					requiredLayers.put(0, stack.ASCII("VK_LAYER_KHRONOS_validation"));
					ArtVK.LOGGER.warn("Enabling Vulkan validation layers");
					wantsDebugLabels = true;
				} else {
					ArtVK.LOGGER.warn("Vulkan validation layers requested but not found");
				}
			}

			Set<String> availableExtensions = this.getSupportedInstanceExtensions();
			PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
			if (glfwExtensions == null) {
				throw new BackendCreationException("Failed to find the GLFW platform surface extensions", BackendCreationException.Reason.GLFW_ERROR);
			}

			while (glfwExtensions.remaining() > 0) {
				this.enabledExtensions.add(MemoryUtil.memUTF8(glfwExtensions.get()));
			}

			this.debug = Vk11Debug.create(debugVerbosity, wantsDebugLabels, availableExtensions, this.enabledExtensions);
			boolean usePortability = availableExtensions.contains("VK_KHR_portability_enumeration") && Util.getPlatform() == Util.OS.OSX;
			if (usePortability) {
				this.enabledExtensions.add("VK_KHR_portability_enumeration");
			}

			PointerBuffer enabledExtensionsBuffer = stack.callocPointer(this.enabledExtensions.size());

			for (String name : this.enabledExtensions) {
				enabledExtensionsBuffer.put(stack.UTF8(name));
			}

			enabledExtensionsBuffer.flip();
			VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
				.sType$Default()
				.pApplicationInfo(appInfo)
				.ppEnabledLayerNames(requiredLayers)
				.ppEnabledExtensionNames(enabledExtensionsBuffer);
			if (usePortability) {
				instanceInfo.flags(KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);
			}

            if(validation) {
                VkValidationFeaturesEXT features = VkValidationFeaturesEXT.calloc(stack)
                        .sType$Default()
                        .pEnabledValidationFeatures(stack.ints(
                                EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT
                        ));
                System.out.println("validation features: "+features.enabledValidationFeatureCount());
                instanceInfo.pNext(features);
            }

			this.debug.chainCreateInfo(instanceInfo, stack);
			PointerBuffer pInstance = stack.callocPointer(1);
			Vk11Utils.throwIfFailure(
				VK10.vkCreateInstance(instanceInfo, null, pInstance), "Error creating instance", BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED
			);
			this.vkInstance = new VkInstance(pInstance.get(0), instanceInfo);
			this.debug.setup(this.vkInstance);
		}
	}

	public VkInstance vkInstance() {
		return this.vkInstance;
	}

	private Set<String> getSupportedInstanceExtensions() throws BackendCreationException {
		Set<String> instanceExtensions = new HashSet<>();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer numExtensionsBuf = stack.callocInt(1);
			Vk11Utils.throwIfFailure(
				VK10.vkEnumerateInstanceExtensionProperties((String)null, numExtensionsBuf, null),
				"Error enumerating instance extensions",
				BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED
			);
			int numExtensions = numExtensionsBuf.get(0);
			Buffer instanceExtensionsProps = VkExtensionProperties.calloc(numExtensions, stack);
			Vk11Utils.throwIfFailure(
				VK10.vkEnumerateInstanceExtensionProperties((String)null, numExtensionsBuf, instanceExtensionsProps),
				"Error enumerating instance extensions",
				BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED
			);

			for (int i = 0; i < numExtensions; i++) {
				VkExtensionProperties props = instanceExtensionsProps.get(i);
				String extensionName = props.extensionNameString();
				instanceExtensions.add(extensionName);
			}
		}

		return instanceExtensions;
	}

	private List<String> getSupportedValidationLayers() throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer numLayersArr = stack.callocInt(1);
			Vk11Utils.throwIfFailure(
				VK10.vkEnumerateInstanceLayerProperties(numLayersArr, null),
				"Error enumerating validation layers",
				BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED
			);
			int numLayers = numLayersArr.get(0);
			org.lwjgl.vulkan.VkLayerProperties.Buffer propsBuf = VkLayerProperties.calloc(numLayers, stack);
			Vk11Utils.throwIfFailure(
				VK10.vkEnumerateInstanceLayerProperties(numLayersArr, propsBuf),
				"Error enumerating validation layers",
				BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED
			);
			List<String> supportedLayers = new ArrayList<>();

			for (int i = 0; i < numLayers; i++) {
				VkLayerProperties props = propsBuf.get(i);
				String layerName = props.layerNameString();
				supportedLayers.add(layerName);
			}

			return supportedLayers;
		}
	}

	@Override
	public void close() {
		this.debug.destroy(this.vkInstance);
		VK10.vkDestroyInstance(this.vkInstance, null);
	}

	public Set<String> getEnabledExtensions() {
		return this.enabledExtensions;
	}

	public Vk11Debug debug() {
		return this.debug;
	}
}
