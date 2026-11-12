package com.sico.autoterms;

import com.sico.autoterms.terminal.TerminalType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Vector2i;

/**
 * No-gui: AshFall's Queue Leap without the leap. A terminal stays off the screen while it is solved, the body stays
 * where it is, the mouse still looks around, and a hotbar slot picked meanwhile is swapped to when the terminal ends.
 *
 * <p>The screen is refused at {@code Gui.setScreen}, after vanilla has already made the terminal the client's
 * {@code containerMenu} ({@code MenuScreens$ScreenConstructor.fromPacket}, javap 26.3). So the server and the client
 * both say the terminal is open and {@link AutoTerms} keeps clicking it (it checks {@code containerMenu}, not the
 * screen), while the mouse stays grabbed, which is the looking around.
 *
 * <p>The hands and feet do what they would with the menu up. Movement is {@code Input.EMPTY}, as vanilla's
 * {@code releaseAll} on a screen opening leaves it, which also keeps window clicks off the one Grim check that drops a
 * click made while moving. The nine hotbar keys and the wheel pick a slot that is drawn as selected and taken only when
 * the terminal ends, when vanilla's own carried-item check sends it; the real slot and the server's do not move before.
 * Attack, use, pick, drop and swap-hands are emptied at the head of {@code handleKeybinds} before vanilla reads them.
 * Escape and the inventory key, which close a terminal's menu, close the terminal with the same close packet.
 *
 * <p>Nothing here has been run against Grim or Hypixel. What a vanilla client cannot produce is turning with a
 * container open; whether Hypixel looks at that is not known.
 */
public final class NoGui {

    private static final int NONE = -1;

    /** Where the text's top sits, as a share of the screen's height: AshFall's, a quarter down. */
    private static final float TOP_SHARE = 0.24f;

    /** AshFall draws the title at 22 and the count at 14; the vanilla font is 9 tall. */
    private static final float TITLE_SCALE = 2.5f;

    private static final float COUNT_SCALE = 1.5f;
    private static final int BLUE = 0xFF5AA8F0;
    private static final String TITLE = "In Terminal";

    private final AutoTermsConfig config;
    private final AutoTerms autoTerms;

    /** The hidden terminal's window, or {@link #NONE}. */
    private int containerId = NONE;

    /** Why the player asked out (Escape or the inventory key), or null: the terminal is closed at the next tick head. */
    private String leaveWhy;

    /** The terminal's own "(done/needed)", or null before its board has loaded. Built on the tick, never per frame. */
    private String progress;

    /** The most the held terminal has needed, and what was left when {@link #progress} was built. */
    private int needed;

    private int left = -1;

    /** The hotbar slot picked while held, taken when the terminal ends; {@link #NONE} for none. */
    private int slot = NONE;

    /** The wheel's notch accumulator, one of its own as {@code MouseHandler} keeps one. */
    private final ScrollWheelHandler wheel = new ScrollWheelHandler();

    public NoGui(AutoTermsConfig config, AutoTerms autoTerms) {
        this.config = config;
        this.autoTerms = autoTerms;
    }

    /** Whether a terminal is open on the server and kept off the screen: the body is still and the hands are off. */
    public boolean holding() {
        return containerId != NONE;
    }

    /** A screen is about to open. Whether it must be refused. */
    public boolean refuses(Screen screen) {
        if (holding()) {
            return refusesWhileHolding(screen);
        }
        if (!config.noGui() || !(screen instanceof AbstractContainerScreen<?> menu) || terminal(menu) == null) {
            return false;
        }
        containerId = menu.getMenu().containerId;
        leaveWhy = null;
        refreshProgress();
        return true;
    }

    /**
     * A screen opening over a held terminal. Escape means leave it, as it does in its menu; the pause screen the game
     * opens by itself on losing focus does not, so alt-tabbing out and back finds the terminal as it was. An inventory
     * screen opened by anything else is refused, since it would click a menu the server does not have open. The same
     * terminal in a new window is hidden again. Anything else (chat) opens over it as normal.
     */
    private boolean refusesWhileHolding(Screen screen) {
        if (screen instanceof PauseScreen && Minecraft.getInstance().getWindow().isFocused()) {
            leaveWhy = "escape";
            return true;
        }
        if (screen instanceof InventoryScreen) {
            return true;
        }
        if (screen instanceof AbstractContainerScreen<?> menu && terminal(menu) != null) {
            containerId = menu.getMenu().containerId;
            return true;
        }
        return false;
    }

    private static TerminalType terminal(AbstractContainerScreen<?> menu) {
        return TerminalType.identify(menu.getTitle().getString());
    }

    /** The tick head: the terminal ended, was left, or is still open. */
    public void tickHead(Minecraft minecraft) {
        if (!holding()) {
            return;
        }
        LocalPlayer self = minecraft.player;
        if (self == null) {
            release();
            return;
        }
        if (leaveWhy != null || !config.noGui()) {
            leave();
            return;
        }
        if (self.containerMenu.containerId == containerId) {
            refreshProgress();
            return;
        }
        // Closed by the server (or replaced by another menu): the terminal is over, and the picked slot is taken.
        takeSlot(self);
        release();
    }

    /** Every click and key vanilla is about to act on, taken first. See the class note. */
    public void onKeybinds(Minecraft minecraft) {
        if (!holding()) {
            return;
        }
        Options options = minecraft.options;
        drain(options.keyAttack);
        drain(options.keyUse);
        options.keyAttack.setDown(false);
        options.keyUse.setDown(false);
        drain(options.keyPickItem);
        drain(options.keyDrop);
        drain(options.keySwapOffhand);
        while (options.keyInventory.consumeClick()) {
            leaveWhy = "inventory-key";
        }
        KeyMapping[] hotbar = options.keyHotbarSlots;
        for (int key = 0; key < hotbar.length; key++) {
            while (hotbar[key].consumeClick()) {
                slot = key;
            }
        }
    }

    /** The hotbar slot to draw as selected: the one picked while held, else {@code selected}. */
    public int shownSlot(int selected) {
        return holding() && slot != NONE ? slot : selected;
    }

    /**
     * A wheel event with no screen up: while held it steps the picked slot as vanilla's scroll would step the real
     * one, scaled as {@code MouseHandler.onScroll} scales it, and is always taken.
     */
    public boolean scroll(double xOffset, double yOffset) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer self = minecraft.player;
        if (!holding() || self == null) {
            return false;
        }
        boolean discrete = minecraft.options.discreteMouseScroll().get();
        double sensitivity = minecraft.options.mouseWheelSensitivity().get();
        Vector2i steps = wheel.onMouseScroll(
                (discrete ? Math.signum(xOffset) : xOffset) * sensitivity,
                (discrete ? Math.signum(yOffset) : yOffset) * sensitivity);
        int step = steps.y != 0 ? steps.y : -steps.x;
        if (step != 0) {
            int from = shownSlot(self.getInventory().getSelectedSlot());
            slot = ScrollWheelHandler.getNextScrollWheelSelection(step, from, Inventory.getSelectionSize());
        }
        return true;
    }

    /** The slot picked while held, made the real one; vanilla's carried-item check sends it this tick. */
    private void takeSlot(LocalPlayer self) {
        if (slot != NONE && slot != self.getInventory().getSelectedSlot()) {
            self.getInventory().setSelectedSlot(slot);
        }
    }

    private static void drain(KeyMapping key) {
        while (key.consumeClick()) {
            // Nothing: the key did what it does with a menu up.
        }
    }

    /** The progress line, rebuilt only when what is left moves. */
    private void refreshProgress() {
        int now = autoTerms.remaining();
        if (now < 0 || now == left) {
            return;
        }
        needed = Math.max(needed, now);
        left = now;
        progress = "(" + (needed - now) + "/" + needed + ")";
    }

    /** The HUD: "In Terminal" and the terminal's progress, centred a quarter of the way down. */
    public void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!holding() || minecraft.gui.hud.isHidden()) {
            return;
        }
        Font font = minecraft.font;
        float centre = graphics.guiWidth() / 2f;
        float top = graphics.guiHeight() * TOP_SHARE;
        line(graphics, font, TITLE, centre, top, TITLE_SCALE);
        if (progress != null) {
            line(graphics, font, progress, centre, top + font.lineHeight * TITLE_SCALE + 4f, COUNT_SCALE);
        }
    }

    private static void line(GuiGraphicsExtractor graphics, Font font, String text, float centre, float y, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(centre - font.width(text) * scale / 2f, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, text, 0, 0, BLUE, true);
        graphics.pose().popMatrix();
    }

    /**
     * Close the held terminal for real: the close packet a menu's Escape sends, and the client's menu back to the
     * inventory. Not {@code LocalPlayer.closeContainer}, whose screen change would also shut whatever is open over the
     * terminal (the settings screen, when this runs because No-gui was switched off in it).
     */
    public void leave() {
        if (!holding()) {
            return;
        }
        LocalPlayer self = Minecraft.getInstance().player;
        if (self != null && self.containerMenu.containerId == containerId) {
            self.connection.send(new ServerboundContainerClosePacket(containerId));
            self.containerMenu = self.inventoryMenu;
        }
        if (self != null) {
            takeSlot(self);
        }
        release();
    }

    public void release() {
        containerId = NONE;
        leaveWhy = null;
        progress = null;
        needed = 0;
        left = -1;
        slot = NONE;
    }
}
