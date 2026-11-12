package com.sico.autoterms.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * A vanilla slider over a whole-number range. Dragging is as fine as the slider is wide, so the arrow keys move it by
 * exactly one once it has been clicked, and the {@link NumberBox} beside it takes a typed number.
 */
final class IntSlider extends AbstractSliderButton {

    private final int min;
    private final int max;
    private final IntSupplier getter;
    private final IntConsumer setter;
    private Runnable onMoved = () -> {};

    IntSlider(int x, int y, int width, String label, int min, int max, IntSupplier getter, IntConsumer setter) {
        super(x, y, width, 20, Component.literal(label), fraction(getter.getAsInt(), min, max));
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
    }

    /** Called whenever the slider changes the value itself, so the box beside it can follow. */
    void onMoved(Runnable onMoved) {
        this.onMoved = onMoved;
    }

    /** Move the handle to the value the setting holds now, after it was typed. */
    void sync() {
        value = fraction(getter.getAsInt(), min, max);
    }

    private static double fraction(int value, int min, int max) {
        return (double) (value - min) / (max - min);
    }

    @Override
    protected void updateMessage() {}

    @Override
    protected void applyValue() {
        setter.accept((int) Math.round(min + value * (max - min)));
        onMoved.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !(event.isLeft() || event.isRight())) {
            return super.keyPressed(event);
        }
        setter.accept(Math.max(min, Math.min(max, getter.getAsInt() + (event.isLeft() ? -1 : 1))));
        sync();
        onMoved.run();
        return true;
    }
}
