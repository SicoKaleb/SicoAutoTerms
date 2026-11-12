package com.sico.autoterms.mixin;

import com.sico.autoterms.SicoAutoTerms;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While No-gui hides a terminal the body does what it would with the menu up: nothing, crouch and jump included. Extends
 * {@code ClientInput} to reach its protected {@code moveVector}, which {@code KeyboardInput.tick} sets from the keys.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void sicoautoterms$hold(CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod != null && mod.noGui().holding()) {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
        }
    }
}
