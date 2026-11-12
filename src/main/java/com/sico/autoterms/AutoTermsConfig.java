package com.sico.autoterms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The settings, saved as {@code config/sicoautoterms.json}. Every value is clamped to its range when it is set and
 * again when the file is read, so a hand-edited file cannot put the solver outside the ranges the screen offers.
 */
public final class AutoTermsConfig {

    public enum Mode {
        NORMAL,
        BURST
    }

    public static final int FC_DELAY_MIN = 0;
    public static final int FC_DELAY_MAX = 500;
    public static final int CLICK_DELAY_MIN = 0;
    public static final int CLICK_DELAY_MAX = 1000;
    public static final int BURST_AMOUNT_MIN = 1;
    public static final int BURST_AMOUNT_MAX = 10;
    public static final int RESYNC_DELAY_MIN = 1;
    public static final int RESYNC_DELAY_MAX = 1000;

    private static final Logger LOG = LoggerFactory.getLogger("sicoautoterms");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    private Mode mode = Mode.NORMAL;
    private int fcDelay = 400;
    private int clickDelay = 100;
    private int burstAmount = 5;
    private int resyncDelay = 600;
    private boolean noGui;

    private AutoTermsConfig(Path file) {
        this.file = file;
    }

    /** What Gson reads and writes; kept apart so the file holds the settings and nothing else. */
    private record Saved(Mode mode, int fcDelay, int clickDelay, int burstAmount, int resyncDelay, boolean noGui) {}

    public static AutoTermsConfig load() {
        AutoTermsConfig config =
                new AutoTermsConfig(FabricLoader.getInstance().getConfigDir().resolve("sicoautoterms.json"));
        if (!Files.isRegularFile(config.file)) {
            config.save();
            return config;
        }
        try {
            Saved saved = GSON.fromJson(Files.readString(config.file, StandardCharsets.UTF_8), Saved.class);
            if (saved != null) {
                config.mode(saved.mode() == null ? Mode.NORMAL : saved.mode());
                config.fcDelay(saved.fcDelay());
                config.clickDelay(saved.clickDelay());
                config.burstAmount(saved.burstAmount());
                config.resyncDelay(saved.resyncDelay());
                config.noGui(saved.noGui());
            }
        } catch (IOException | JsonParseException failure) {
            LOG.warn("could not read {}, using defaults: {}", config.file, failure.toString());
        }
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    GSON.toJson(new Saved(mode, fcDelay, clickDelay, burstAmount, resyncDelay, noGui)),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOG.warn("could not save {}: {}", file, failure.toString());
        }
    }

    public Mode mode() {
        return mode;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public int fcDelay() {
        return fcDelay;
    }

    public void fcDelay(int value) {
        fcDelay = clamp(value, FC_DELAY_MIN, FC_DELAY_MAX);
    }

    public int clickDelay() {
        return clickDelay;
    }

    public void clickDelay(int value) {
        clickDelay = clamp(value, CLICK_DELAY_MIN, CLICK_DELAY_MAX);
    }

    public int burstAmount() {
        return burstAmount;
    }

    public void burstAmount(int value) {
        burstAmount = clamp(value, BURST_AMOUNT_MIN, BURST_AMOUNT_MAX);
    }

    public int resyncDelay() {
        return resyncDelay;
    }

    public void resyncDelay(int value) {
        resyncDelay = clamp(value, RESYNC_DELAY_MIN, RESYNC_DELAY_MAX);
    }

    /** Off by default, as AshFall's Queue Leap is: it hides a menu and holds the body still. */
    public boolean noGui() {
        return noGui;
    }

    public void noGui(boolean value) {
        noGui = value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
