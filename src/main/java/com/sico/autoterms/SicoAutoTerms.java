package com.sico.autoterms;

import com.mojang.blaze3d.platform.InputConstants;
import com.sico.autoterms.gui.AutoTermsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Wires the solver and No-gui to the game: the ticks, the HUD, the settings key, and a reset when the connection ends. */
public final class SicoAutoTerms implements ClientModInitializer {

    /** The one instance, for the mixins, which cannot be handed one. */
    private static SicoAutoTerms instance;

    private AutoTermsConfig config;
    private PingClock clock;
    private AutoTerms autoTerms;
    private NoGui noGui;
    private KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        instance = this;
        config = AutoTermsConfig.load();
        clock = new PingClock();
        autoTerms = new AutoTerms(config, clock);
        noGui = new NoGui(config, autoTerms);

        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sicoautoterms", "main"));
        settingsKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sicoautoterms.settings", InputConstants.KEY_RSHIFT, category));

        ClientTickEvents.START_CLIENT_TICK.register(noGui::tickHead);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sicoautoterms", "no_gui"), noGui::draw);
        ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> {
            noGui.release();
            autoTerms.reset();
            clock.reset();
        });
    }

    private void onTick(Minecraft minecraft) {
        while (settingsKey.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new AutoTermsScreen(null, config));
            }
        }
        autoTerms.tick(minecraft);
    }

    public static SicoAutoTerms instance() {
        return instance;
    }

    public AutoTerms autoTerms() {
        return autoTerms;
    }

    public NoGui noGui() {
        return noGui;
    }

    public PingClock clock() {
        return clock;
    }

    public AutoTermsConfig config() {
        return config;
    }
}
