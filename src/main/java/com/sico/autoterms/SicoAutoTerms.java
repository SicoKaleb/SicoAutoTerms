package com.sico.autoterms;

import com.mojang.blaze3d.platform.InputConstants;
import com.sico.autoterms.gui.AutoTermsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Wires the solver and No-gui to the game: the ticks, the HUD, {@code /sico} and the settings key, and a reset when the
 * connection ends.
 */
public final class SicoAutoTerms implements ClientModInitializer {

    /** The one instance, for the mixins, which cannot be handed one. */
    private static SicoAutoTerms instance;

    private AutoTermsConfig config;
    private PingClock clock;
    private AutoTerms autoTerms;
    private NoGui noGui;

    /**
     * Right Shift opens the settings. It is read straight off the keyboard rather than registered as a key mapping,
     * because a registered mapping's name answers a server: a keybind component naming it, put on a sign or an anvil
     * item, comes back resolved to the bound key when the mod is installed and as the raw name when it is not. The
     * sign editor and the anvil both turn server text into a plain string (javap, 26.3). Not rebindable as a result;
     * /sico opens the same screen.
     */
    private boolean settingsKeyDown;

    /**
     * Set by {@code /sico}. The screen opens at the end of the tick rather than in the command, because the chat screen
     * the command was typed in closes itself straight after running it ({@code ChatScreen.keyPressed}, javap 26.3) and
     * would take the settings with it.
     */
    private boolean openSettings;

    @Override
    public void onInitializeClient() {
        instance = this;
        config = AutoTermsConfig.load();
        clock = new PingClock();
        autoTerms = new AutoTerms(config, clock);
        noGui = new NoGui(config, autoTerms);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(ClientCommands.literal("sico").executes(command -> {
                    openSettings = true;
                    return 1;
                })));

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
        boolean down = minecraft.isWindowActive() && InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
        openSettings |= down && !settingsKeyDown && minecraft.gui.screen() == null;
        settingsKeyDown = down;
        if (openSettings && minecraft.gui.screen() == null) {
            openSettings = false;
            minecraft.gui.setScreen(new AutoTermsScreen(null, config));
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
