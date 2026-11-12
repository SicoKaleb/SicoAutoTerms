package com.sico.autoterms.mixin;

import com.sico.autoterms.SicoAutoTerms;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No-gui refuses a terminal's screen here, before it is assigned, so the mouse is never released and no frame of it is
 * drawn. On 26.3 the open screen belongs to {@code Gui} and {@code MenuScreens} opens a container through
 * {@code Gui.setScreen} (javap, 26.3 jar). A null screen always passes: that is the game closing something.
 */
@Mixin(Gui.class)
public class MixinGui {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void sicoautoterms$refuse(Screen screen, CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (screen != null && mod != null && mod.noGui().refuses(screen)) {
            ci.cancel();
        }
    }
}
