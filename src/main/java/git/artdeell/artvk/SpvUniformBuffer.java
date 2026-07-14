package git.artdeell.artvk;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
record SpvUniformBuffer(String name, int bindingOffset) {
}
