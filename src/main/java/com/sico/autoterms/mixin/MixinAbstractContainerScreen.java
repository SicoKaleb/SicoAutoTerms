package com.sico.autoterms.mixin;

import com.sico.autoterms.SicoAutoTerms;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen {

    /**
     * Every route into a slot (left, right, shift, middle, number key, drop, drag) funnels through this one. The full
     * descriptor because 26.3 added a private overload taking a {@code MouseButtonEvent} (javap, 26.3 jar), so the bare
     * name matches two methods.
     */
    @Inject(
            method =
                    "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void sicoautoterms$blockManual(Slot slot, int slotId, int button, ContainerInput type, CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod != null && mod.autoTerms().blocksManualClick((AbstractContainerScreen<?>) (Object) this)) {
            ci.cancel();
        }
    }

    /** Before each slot draws, after the container art: the one point where a tint sits under the item. */
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void sicoautoterms$tint(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod != null) {
            mod.autoTerms().paintSlot(graphics, slot);
        }
    }
}
