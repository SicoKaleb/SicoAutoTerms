package com.sico.autoterms.mixin;

import com.sico.autoterms.SicoAutoTerms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With no screen up and a terminal hidden, the wheel picks the slot taken when the terminal ends. */
@Mixin(MouseHandler.class)
public class MixinMouseHandler {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void sicoautoterms$scroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod != null && Minecraft.getInstance().gui.screen() == null && mod.noGui().scroll(xOffset, yOffset)) {
            ci.cancel();
        }
    }
}
