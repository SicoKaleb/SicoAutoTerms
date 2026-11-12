package com.sico.autoterms.gui;

import com.sico.autoterms.AutoTermsConfig;
import com.sico.autoterms.AutoTermsConfig.Mode;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The settings: one mode button, four rows of a slider and a box to type the number into, and Done. Saved when the
 * screen closes.
 */
public final class AutoTermsScreen extends Screen {

    private static final int WIDTH = 200;
    private static final int BOX_WIDTH = 46;
    private static final int GAP = 4;
    private static final int ROW = 24;

    private final Screen parent;
    private final AutoTermsConfig config;
    private Row burstAmount;

    /** One setting's slider and the box beside it. */
    private record Row(IntSlider slider, NumberBox box) {}

    public AutoTermsScreen(Screen parent, AutoTermsConfig config) {
        super(Component.literal("SicoAutoTerms"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int x = width / 2 - WIDTH / 2;
        int y = height / 2 - ROW * 3;

        addRenderableWidget(Button.builder(modeLabel(), button -> {
                    config.mode(config.mode() == Mode.NORMAL ? Mode.BURST : Mode.NORMAL);
                    button.setMessage(modeLabel());
                    burstRowActive();
                })
                .bounds(x, y, WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Normal clicks one slot at a time. Burst sends several clicks at once, then waits.")))
                .build());

        row(x, y + ROW, "FC delay (ms)", AutoTermsConfig.FC_DELAY_MIN, AutoTermsConfig.FC_DELAY_MAX,
                config::fcDelay, config::fcDelay, "How long to wait after a terminal opens before the first click.");
        row(x, y + ROW * 2, "Click delay (ms)", AutoTermsConfig.CLICK_DELAY_MIN, AutoTermsConfig.CLICK_DELAY_MAX,
                config::clickDelay, config::clickDelay,
                "Normal: the wait between one click and the next. Burst: the wait between one burst and the next.");
        burstAmount = row(x, y + ROW * 3, "Burst amount", AutoTermsConfig.BURST_AMOUNT_MIN,
                AutoTermsConfig.BURST_AMOUNT_MAX, config::burstAmount, config::burstAmount,
                "How many clicks go out together in each burst. Burst mode only.");
        burstRowActive();
        row(x, y + ROW * 4, "Resync delay (ms)", AutoTermsConfig.RESYNC_DELAY_MIN, AutoTermsConfig.RESYNC_DELAY_MAX,
                config::resyncDelay, config::resyncDelay,
                "How long to wait for a clicked slot to change before clicking it again.");
        addRenderableWidget(Button.builder(noGuiLabel(), button -> {
                    config.noGui(!config.noGui());
                    button.setMessage(noGuiLabel());
                })
                .bounds(x, y + ROW * 5, WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal("While a terminal is being done, its menu stays closed and you"
                        + " stay in place but can look around. Scroll or press a number to pick a hotbar slot, which"
                        + " you swap to when the terminal is done. Escape leaves the terminal.")))
                .build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, y + ROW * 6 + 8, WIDTH, 20)
                .build());
    }

    private Component noGuiLabel() {
        return Component.literal("No-gui: " + (config.noGui() ? "On" : "Off"));
    }

    /** A slider and, to its right, the box its number can be typed into. */
    private Row row(int x, int y, String label, int min, int max, IntSupplier getter, IntConsumer setter,
            String help) {
        IntSlider slider = new IntSlider(x, y, WIDTH - BOX_WIDTH - GAP, label, min, max, getter, setter);
        slider.setTooltip(Tooltip.create(Component.literal(help + " Drag, or type the number in the box.")));
        NumberBox box = new NumberBox(font, x + WIDTH - BOX_WIDTH, y, BOX_WIDTH, Component.literal(label), max, getter,
                setter, slider);
        box.setTooltip(Tooltip.create(Component.literal(help + " " + min + " to " + max + ".")));
        addRenderableWidget(slider);
        addRenderableWidget(box);
        return new Row(slider, box);
    }

    private void burstRowActive() {
        boolean burst = config.mode() == Mode.BURST;
        burstAmount.slider().active = burst;
        burstAmount.box().setEditable(burst);
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + (config.mode() == Mode.BURST ? "Burst" : "Normal"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, "SicoAutoTerms", width / 2, height / 2 - ROW * 3 - 16, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.gui.setScreen(parent);
    }
}
