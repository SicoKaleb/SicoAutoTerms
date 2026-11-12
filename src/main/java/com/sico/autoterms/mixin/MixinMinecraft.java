package com.sico.autoterms.mixin;

import com.sico.autoterms.SicoAutoTerms;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The head of {@code handleKeybinds}: No-gui takes the click and key queue before vanilla acts on it. */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void sicoautoterms$keybinds(CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod != null) {
            mod.noGui().onKeybinds((Minecraft) (Object) this);
        }
    }
}
