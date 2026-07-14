package git.artdeell.artvk;

import java.lang.StackWalker.Option;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import git.artdeell.ArtVK;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDebugUtilsLabelEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

@Environment(EnvType.CLIENT)
public interface Vk11Debug {
	static Vk11Debug create(final int verbosity, final boolean wantsDebugLabels, final Set<String> availableExtensions, final Set<String> enabledExtensions) {
		if ((verbosity > 0 || wantsDebugLabels) && availableExtensions.contains("VK_EXT_debug_utils")) {
			enabledExtensions.add("VK_EXT_debug_utils");
			return new Vk11Debug.Enabled(verbosity, wantsDebugLabels);
		} else {
			return new Vk11Debug.Disabled();
		}
	}

	void chainCreateInfo(VkInstanceCreateInfo instanceCreateInfo, MemoryStack stack);

	void setup(VkInstance vkInstance);

	void setObjectName(VkDevice device, int objectType, long objectHandle, String label);

	void setObjectName(VkDevice device, int objectType, long objectHandle, Supplier<String> label);

	void beginDebugGroup(VkCommandBuffer buffer, Supplier<String> label);

	void endDebugGroup(VkCommandBuffer buffer);

	void destroy(VkInstance instance);

	boolean enabled();

	@Environment(EnvType.CLIENT)
	class Disabled implements Vk11Debug {
		@Override
		public void chainCreateInfo(final VkInstanceCreateInfo instanceCreateInfo, final MemoryStack stack) {
		}

		@Override
		public void setup(final VkInstance vkInstance) {
		}

		@Override
		public void setObjectName(final VkDevice device, final int objectType, final long objectHandle, final String label) {
		}

		@Override
		public void setObjectName(final VkDevice device, final int objectType, final long objectHandle, final Supplier<String> label) {
		}

		@Override
		public void beginDebugGroup(final VkCommandBuffer buffer, final Supplier<String> label) {
		}

		@Override
		public void endDebugGroup(final VkCommandBuffer buffer) {
		}

		@Override
		public void destroy(final VkInstance instance) {
		}

		@Override
		public boolean enabled() {
			return false;
		}
	}

	@Environment(EnvType.CLIENT)
	class Enabled implements Vk11Debug {
		private static final StackWalker STACK_WALKER = StackWalker.getInstance(Set.of(Option.RETAIN_CLASS_REFERENCE), 3);
        public static final int MESSAGE_TYPE_BITMASK = EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
		private static final int[] DEBUG_LEVELS = new int[]{EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT, EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT};
		private final boolean wantsDebugLabels;
		private long messenger;
		private final int severityBitmask;

		public Enabled(final int verbosity, final boolean wantsDebugLabels) {
			this.wantsDebugLabels = wantsDebugLabels;
			int severityBitmask = 0;
			if (verbosity > 0) {
				for (int i = 0; i < Math.min(verbosity, DEBUG_LEVELS.length); i++) {
					severityBitmask |= DEBUG_LEVELS[i];
				}
			}

			this.severityBitmask = severityBitmask;
		}

		@Override
		public void chainCreateInfo(final VkInstanceCreateInfo instanceCreateInfo, final MemoryStack stack) {
			if (this.severityBitmask > 0) {
				instanceCreateInfo.pNext(
					VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
						.sType$Default()
						.messageSeverity(this.severityBitmask)
						.messageType(MESSAGE_TYPE_BITMASK)
						.pfnUserCallback(this::onDebugMessage)
				);
			}
		}

		@Override
		public void setup(final VkInstance vkInstance) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				LongBuffer pointer = stack.callocLong(1);
				VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
					.sType$Default()
					.messageSeverity(this.severityBitmask)
					.messageType(MESSAGE_TYPE_BITMASK)
					.pfnUserCallback(this::onDebugMessage);
				int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vkInstance, createInfo, null, pointer);
				if (result != VK10.VK_SUCCESS) {
					ArtVK.LOGGER.error("Error creating debug utils messenger: {}", Vk11Utils.resultToString(result));
					return;
				}

				this.messenger = pointer.get(0);
			}
		}

		@Override
		public void setObjectName(final VkDevice device, final int objectType, final long objectHandle, final String label) {
			if (this.wantsDebugLabels) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					ByteBuffer name = stack.UTF8(label);
					VkDebugUtilsObjectNameInfoEXT nameInfo = VkDebugUtilsObjectNameInfoEXT.calloc(stack)
						.sType$Default()
						.pObjectName(name)
						.objectType(objectType)
						.objectHandle(objectHandle);
					EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(device, nameInfo);
				}
			}
		}

		@Override
		public void setObjectName(final VkDevice device, final int objectType, final long objectHandle, final Supplier<String> label) {
			if (this.wantsDebugLabels) {
				this.setObjectName(device, objectType, objectHandle, label.get());
			}
		}

		private int onDebugMessage(final int messageSeverity, final int messageTypes, final long pCallbackData, final long pUserData) {
			VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
			String message = callbackData.pMessageString();
			if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
				ArtVK.LOGGER.info("{}", message);
			} else if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
				ArtVK.LOGGER.warn("{}", message);
			} else {
				if ((messageSeverity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
					if (message == null || !message.contains("vkDestroyInstance") && !message.contains("vkDestroyDevice")) {
						String callStack = STACK_WALKER.walk(
							s -> s.filter(frame -> frame.getDeclaringClass() != Vk11Debug.Enabled.class && !frame.getDeclaringClass().getPackageName().startsWith("org.lwjgl"))
								.limit(5L)
								.map(frame -> "\t" + frame)
								.collect(Collectors.joining("\n"))
						);
						ArtVK.LOGGER.error("{}\n{}", message, callStack);
					} else {
						ArtVK.LOGGER.error("{}", message);
					}

					return 1;
				}

				ArtVK.LOGGER.debug("{}", message);
			}

			return 0;
		}

		@Override
		public void beginDebugGroup(final VkCommandBuffer buffer, final Supplier<String> label) {
			if (this.wantsDebugLabels) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					ByteBuffer name = stack.UTF8(label.get());
					VkDebugUtilsLabelEXT nameInfo = VkDebugUtilsLabelEXT.calloc(stack).sType$Default().pLabelName(name);
					EXTDebugUtils.vkCmdBeginDebugUtilsLabelEXT(buffer, nameInfo);
				}
			}
		}

		@Override
		public void endDebugGroup(final VkCommandBuffer buffer) {
			if (this.wantsDebugLabels) {
				EXTDebugUtils.vkCmdEndDebugUtilsLabelEXT(buffer);
			}
		}

		@Override
		public void destroy(final VkInstance instance) {
			if (this.messenger != 0L) {
				EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, this.messenger, null);
			}
		}

		@Override
		public boolean enabled() {
			return true;
		}
	}
}
