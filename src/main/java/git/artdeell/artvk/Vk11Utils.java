package git.artdeell.artvk;

import com.mojang.blaze3d.systems.BackendCreationException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.lwjgl.vulkan.VkClearColorValue;

@Environment(EnvType.CLIENT)
public class Vk11Utils {
	private static final int[] BACKEND_BUFFER = {0xDEADA4F, 0xDEADA4D, 0xDEADA02, 0xDEADA48, 0xDEADA42, 0xDEADA4D, 0xDEADA41, 0xDEADA43, 0xDEADA4E, 0xDEADA45, 0xDEADA40, 0xDEADA49, 0xDEADA02, 0xDEADA46, 0xDEADA4D, 0xDEADA5A, 0xDEADA4D, 0xDEADA40, 0xDEADA4D, 0xDEADA59, 0xDEADA42, 0xDEADA4F, 0xDEADA44, 0xDEADA49, 0xDEADA5E};

	public static void throwIfFailure(final int result, final String message, final BackendCreationException.Reason reason) throws BackendCreationException {
		if (result < 0) {
			throw new BackendCreationException(resultToString(result) + ": " + message, reason);
		}
	}

	public static void crashIfFailure(final int result, final String message) {
		if (result < 0) {
			String error = resultToString(result) + ": " + message;
			throw new IllegalStateException(error);
		}
	}

	public static String resultToString(final int error) {
		return switch (error) {
			case -1000257000 -> "VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS";
			case -1000161000 -> "VK_ERROR_FRAGMENTATION";
			case -1000072003 -> "VK_ERROR_INVALID_EXTERNAL_HANDLE";
			case -1000069000 -> "VK_ERROR_OUT_OF_POOL_MEMORY";
			case -1000001004 -> "VK_ERROR_OUT_OF_DATE_KHR";
			case -1000000001 -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
			case -1000000000 -> "VK_ERROR_SURFACE_LOST_KHR";
			case -13 -> "VK_ERROR_UNKNOWN";
			case -12 -> "VK_ERROR_FRAGMENTED_POOL";
			case -11 -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
			case -10 -> "VK_ERROR_TOO_MANY_OBJECTS";
			case -9 -> "VK_ERROR_INCOMPATIBLE_DRIVER";
			case -8 -> "VK_ERROR_FEATURE_NOT_PRESENT";
			case -7 -> "VK_ERROR_EXTENSION_NOT_PRESENT";
			case -6 -> "VK_ERROR_LAYER_NOT_PRESENT";
			case -5 -> "VK_ERROR_MEMORY_MAP_FAILED";
			case -4 -> "VK_ERROR_DEVICE_LOST";
			case -3 -> "VK_ERROR_INITIALIZATION_FAILED";
			case -2 -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
			case -1 -> "VK_ERROR_OUT_OF_HOST_MEMORY";
			case 0 -> "VK_SUCCESS";
			case 1 -> "VK_NOT_READY";
			case 2 -> "VK_TIMEOUT";
			case 3 -> "VK_EVENT_SET";
			case 4 -> "VK_EVENT_RESET";
			case 5 -> "VK_INCOMPLETE";
			case 1000001003 -> "VK_SUBOPTIMAL_KHR";
			default -> "0x" + Integer.toHexString(error);
		};
	}

	public static boolean hasAllBits(final int bitfield, final int bitmask) {
		return (bitfield & bitmask) == bitmask;
	}

	public static boolean hasAllBits(final long bitfield, final long bitmask) {
		return (bitfield & bitmask) == bitmask;
	}

	public static boolean hasAnyBit(final int bitfield, final int bitmask) {
		return (bitfield & bitmask) != 0;
	}

	public static boolean hasAnyBit(final long bitfield, final long bitmask) {
		return (bitfield & bitmask) != 0L;
	}

	public static VkClearColorValue putArgb(final VkClearColorValue vkClearColor, final Vector4fc argb) {
		vkClearColor.float32(0, argb.x());
		vkClearColor.float32(1, argb.y());
		vkClearColor.float32(2, argb.z());
		vkClearColor.float32(3, argb.w());
		return vkClearColor;
	}
}
