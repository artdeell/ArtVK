package git.artdeell;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtVK implements ModInitializer {
	public static final String MOD_ID = "artvk";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final String hash = "c47dd7eb68a6921d905bdf46459508c8bf0becf7b732b6b00973650ff0354b7e";

	@Override
	public void onInitialize() {}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
