package com.sico.autoterms.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sico.autoterms.SicoAutoTerms;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The hotbar draws the slot No-gui has picked while it hides a terminal, before the real one moves. On 26.3 the hotbar
 * is drawn by {@code Hud.extractItemHotbar} (javap, 26.3 jar). Wrapped rather than redirected, so another mod hooking
 * the same call still loads.
 */
@Mixin(Hud.class)
public class MixinHud {

    @WrapOperation(
            method =
                    "extractItemHotbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedSlot()I"))
    private int sicoautoterms$shownSlot(Inventory inventory, Operation<Integer> original) {
        int selected = original.call(inventory);
        SicoAutoTerms mod = SicoAutoTerms.instance();
        return mod == null ? selected : mod.noGui().shownSlot(selected);
    }
}
