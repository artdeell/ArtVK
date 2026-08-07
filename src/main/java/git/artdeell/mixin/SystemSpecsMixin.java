package git.artdeell.mixin;

import git.artdeell.ArtVK;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugEntrySystemSpecs.class)
public class SystemSpecsMixin {

    @Final
    @Shadow
    private static final Identifier GROUP = null;

    @Inject(method = "display",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/util/Collection;)V",
                    shift = At.Shift.AFTER))
    public void injectVersion(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk, LevelChunk serverChunk, CallbackInfo ci){
        displayer.addToGroup(GROUP, "§aArtVK v" + ArtVK.MOD_VERSION);
    }
}
