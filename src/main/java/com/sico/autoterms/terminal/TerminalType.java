package com.sico.autoterms.terminal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The six phase-three terminals, identified by the title of the chest the server opens.
 *
 * <p>Every regex and every window size here was read out of the reference client's own bytecode
 * ({@code TerminalType.<clinit>}, {@code javap -c}) rather than written from memory, and a second
 * unrelated client agrees on all six independently. They are anchored at both ends on purpose:
 * {@code Select all the (.+?) items!} would otherwise match halfway into a longer line.
 *
 * <p>The size is the size of the <b>chest</b> half of the menu, not the whole container. Slots at or
 * above it are the player's own inventory, and a terminal never wants those — filtering on it is
 * what stops a solve clicking a slot in your hotbar because the item happened to start with the
 * right letter.
 */
public enum TerminalType {

    /** Red panes numbered by stack size; click them in ascending order. */
    NUMBERS("^Click in order!$", 36),

    /** A grid of red and green panes; turn every red one green. */
    PANES("^Correct all the panes!$", 45),

    /** Panes that cycle through five colours; land them all on one colour. */
    RUBIX("^Change all to same color!$", 45),

    /** Click every item whose name starts with the captured letter. */
    STARTS_WITH("^What starts with: '(.+?)'\\?$", 45),

    /** Click every item of the captured colour. */
    SELECT_ALL("^Select all the (.+?) items!$", 54),

    /** Melody's harp. Solved by watching the board, not by a click list — see {@link TerminalSolve}. */
    MELODY("^Click the button on time!$", 54);

    /** Formatting codes, which the title arrives carrying and no regex here accounts for. */
    private static final Pattern LEGACY = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    private final Pattern pattern;
    private final int windowSize;

    TerminalType(String regex, int windowSize) {
        this.pattern = Pattern.compile(regex);
        this.windowSize = windowSize;
    }

    /** How many slots belong to the terminal itself. Slots at or above this are the player's. */
    public int windowSize() {
        return windowSize;
    }

    /** Which terminal this chest title is, or null when it is not one of them. */
    public static TerminalType identify(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        String title = strip(rawTitle);
        for (TerminalType type : values()) {
            if (type.pattern.matcher(title).matches()) {
                return type;
            }
        }
        return null;
    }

    /**
     * The one parameter in the title — the letter for Starts With, the colour word for Select All —
     * lowercased, or null for the four terminals that carry none.
     *
     * <p>Lowercased here rather than at each use site because both consumers compare it against a
     * lowercased item name, and doing it twice is how one of them ends up not doing it at all.
     */
    public String capture(String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(strip(rawTitle));
        if (!matcher.matches() || matcher.groupCount() < 1) {
            return null;
        }
        String group = matcher.group(1);
        return group == null ? null : group.toLowerCase(Locale.ROOT);
    }

    public static String strip(String text) {
        return LEGACY.matcher(text).replaceAll("");
    }
}
