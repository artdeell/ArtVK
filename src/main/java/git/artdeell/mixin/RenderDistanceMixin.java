package git.artdeell.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class RenderDistanceMixin {
    @Final @Shadow public Options options;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onMinecraftConstructed(GameConfig gameConfig, CallbackInfo ci) {
        OptionInstance<@NotNull Integer> renderDistanceOption = options.renderDistance();
        renderDistanceOption.values = new OptionInstance.IntRange(2, 8);
        if(renderDistanceOption.get() > 8) renderDistanceOption.set(8);
    }

}
