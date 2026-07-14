package git.artdeell.artvk;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class ShaderCompileException extends Exception {
	public ShaderCompileException(final String message) {
		super(message);
	}
}
