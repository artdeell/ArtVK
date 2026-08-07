package git.artdeell;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtVK implements ModInitializer {
	public static final String MOD_ID = "artvk";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String MOD_VERSION = FabricLoader.getInstance().getModContainer(MOD_ID).get().getMetadata().getVersion().getFriendlyString();

	@Override
	public void onInitialize() {}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
