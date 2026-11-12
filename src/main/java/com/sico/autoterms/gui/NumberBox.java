package com.sico.autoterms.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The typed half of a slider row: digits only, applied as they are typed (clamped to the range) and the slider moved to
 * match. The first digit typed after a click (or after Tab lands here) replaces what was there, so clicking and typing
 * 250 gives 250 rather than 400250; Backspace or an arrow key first edits the number instead. Leaving the box shows the value actually kept, which differs only when the number typed was out of range.
 */
final class NumberBox extends EditBox {

    private final IntSupplier getter;
    private boolean replaceOnType;

    NumberBox(Font font, int x, int y, int width, Component label, int max, IntSupplier getter, IntConsumer setter,
            IntSlider slider) {
        super(font, x, y, width, 20, label);
        this.getter = getter;
        setMaxLength(String.valueOf(max).length());
        setValue(String.valueOf(getter.getAsInt()));
        setResponder(text -> {
            if (!text.isEmpty()) {
                setter.accept(Integer.parseInt(text));
                slider.sync();
            }
        });
        slider.onMoved(() -> {
            if (!isFocused()) {
                setValue(String.valueOf(getter.getAsInt()));
            }
        });
    }

    @Override
    public void insertText(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        if (replaceOnType && !digits.isEmpty()) {
            setValue("");
        }
        replaceOnType = false;
        super.insertText(digits);
    }

    /** Every click, not only the one that focuses the box: clicking back into it to type a new number replaces too. */
    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        super.onClick(event, doubleClick);
        replaceOnType = true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        replaceOnType = false;
        return super.keyPressed(event);
    }

    @Override
    public void setFocused(boolean focused) {
        if (focused && !isFocused()) {
            replaceOnType = true;
        } else if (!focused) {
            replaceOnType = false;
            setValue(String.valueOf(getter.getAsInt()));
        }
        super.setFocused(focused);
    }
}
