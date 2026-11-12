package com.sico.autoterms.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Every terminal solve, as pure functions of the board.
 *
 * <p><b>Nothing in here touches Minecraft state.</b> It is handed an array of stacks and gives back
 * a list of clicks, which is what makes the whole thing unit-testable without a server and what
 * lets a custom terminal GUI, when one exists, call exactly the same code the auto-clicker calls
 * rather than growing a second copy of the rules that then drifts.
 *
 * <p>The algorithms are ported from the reference client rather than reinvented — they are
 * field-proven against the real terminals, and Hypixel's window-id change did not touch them. Two
 * deliberate departures are marked at their sites.
 */
public final class TerminalSolve {

    /**
     * One click: which slot, and which of the two shapes Hypixel's terminals understand.
     *
     * <p>{@link ContainerInput#CLONE} — a middle click — is the ordinary one, and it is the ordinary
     * one for a good reason. Vanilla's {@code clicked()} is gated on infinite materials, so in
     * survival it moves nothing locally: the packet goes out with an empty {@code changedSlots} and
     * an empty carried item, and there is no predicted client state that can be wrong. The terminals
     * read the slot number and do not care what kind of click it was.
     */
    public record Click(int slot, ContainerInput input, int button) {

        /** The forward click every terminal uses, and Rubix's clockwise step. */
        public static Click forward(int slot) {
            return new Click(slot, ContainerInput.CLONE, 0);
        }

        /**
         * Rubix's counter-clockwise step — a right click.
         *
         * <p>Unlike {@link #forward}, vanilla really does run this one and picks half the stack into
         * the carried slot, so whoever sends it owns putting the carried item back. See
         * {@code AutoTerms.send}.
         */
        static Click reverse(int slot) {
            return new Click(slot, ContainerInput.PICKUP, 1);
        }
    }

    /** The five colours a Rubix pane cycles through, in cycle order. */
    private static final List<Item> RUBIX_CYCLE = List.of(
            Items.STAINED_GLASS_PANE.red(),
            Items.STAINED_GLASS_PANE.orange(),
            Items.STAINED_GLASS_PANE.yellow(),
            Items.STAINED_GLASS_PANE.green(),
            Items.STAINED_GLASS_PANE.blue());

    /**
     * Item names rewritten into the vocabulary the title uses.
     *
     * <p>Skyblock's terminals still speak 1.8's colour names, so the chest says <i>SILVER</i> while
     * the item in it is a <i>Light Gray Dye</i>, and it says <i>WHITE</i> while the item is
     * <i>Wool</i> or a <i>Bone Meal</i>. Each entry rewrites a leading word, so "light gray dye"
     * becomes "silver dye" and then starts with "silver" like the title asked.
     *
     * <p>Insertion-ordered because the rewrites are applied in order and the first match wins.
     */
    private static final Map<String, String> COLOR_ALIASES = new LinkedHashMap<>();

    static {
        COLOR_ALIASES.put("light gray", "silver");
        COLOR_ALIASES.put("wool", "white");
        COLOR_ALIASES.put("bone", "white");
        COLOR_ALIASES.put("ink", "black");
        COLOR_ALIASES.put("lapis", "blue");
        COLOR_ALIASES.put("cocoa", "brown");
        COLOR_ALIASES.put("dandelion", "yellow");
        COLOR_ALIASES.put("rose", "red");
        COLOR_ALIASES.put("poppy", "red");
        COLOR_ALIASES.put("cactus", "green");
    }

    private TerminalSolve() {}

    /**
     * The clicks that finish this terminal, best first.
     *
     * <p>{@code pending} is the slots already clicked whose result has not come back yet. They are
     * excluded rather than re-clicked: the board still shows them unsolved, so without this every
     * solve would pick the same slot again until the server caught up.
     *
     * <p>Empty for {@link TerminalType#MELODY}, which is a timing problem rather than a set of
     * slots — see {@link #melody}.
     */
    public static List<Click> solve(TerminalType type, ItemStack[] board, String capture, Set<Integer> pending) {
        return solve(type, board, capture, pending, -1);
    }

    /**
     * As {@link #solve}, with Rubix's colour fixed rather than recomputed.
     *
     * <p>{@code rubixTarget} of -1 means "work it out from this board", which is what the caller wants
     * exactly once per terminal — see {@link #rubixTarget(ItemStack[])}.
     */
    public static List<Click> solve(
            TerminalType type, ItemStack[] board, String capture, Set<Integer> pending, int rubixTarget) {
        if (type == null || board == null) {
            return List.of();
        }
        return switch (type) {
            case NUMBERS -> numbers(board, pending);
            case PANES -> panes(board, pending);
            case RUBIX -> rubix(board, pending, rubixTarget);
            case STARTS_WITH -> startsWith(board, capture, pending);
            case SELECT_ALL -> selectAll(board, capture, pending);
            case MELODY -> List.of();
        };
    }

    /**
     * Every red pane, in ascending order of the number printed on it — which is its stack size.
     *
     * <p>A clicked one turns green, so filtering on red is also what drops the ones already done;
     * there is no separate "solved" state to track.
     */
    private static List<Click> numbers(ItemStack[] board, Set<Integer> pending) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < board.length; slot++) {
            if (isRed(board[slot]) && !isPending(pending, slot)) {
                slots.add(slot);
            }
        }
        slots.sort((a, b) -> Integer.compare(board[a].getCount(), board[b].getCount()));
        return clicks(slots);
    }

    /** Every red pane. Order does not matter here, so it is randomised. */
    private static List<Click> panes(ItemStack[] board, Set<Integer> pending) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < board.length; slot++) {
            if (isRed(board[slot]) && !isPending(pending, slot)) {
                slots.add(slot);
            }
        }
        Collections.shuffle(slots);
        return clicks(slots);
    }

    /**
     * Land every pane on one colour, in the fewest clicks.
     *
     * <p>Five candidate target colours, and for each one the cost is the sum over panes of the
     * shorter of the two ways round the cycle. Brute force is the right tool: five candidates times
     * at most forty-five panes is a couple of hundred operations, and anything cleverer would be
     * harder to read for no measurable gain.
     *
     * <p>A pane's clicks are emitted consecutively and the list is <b>not</b> shuffled — the same
     * slot is clicked several times, and interleaving those with other panes buys nothing.
     *
     * <p><b>Departure from the reference:</b> it excluded black panes and assumed everything else
     * was one of the five, so an unexpected item would index the cycle at -1 and poison the whole
     * cost sum. This includes panes positively instead, which is the same thing on a board that
     * looks as expected and harmless on one that does not.
     *
     * <p><b>And the one that matters:</b> a pane in flight is dropped from the emitted clicks, while
     * still counting towards the target colour. Rubix is the only terminal that clicks a slot more
     * than once, and without this a solve running faster than the round trip would keep re-reading a
     * stale board — a pane wanting two clicks would be sent three, four, however many fit in the gap,
     * and cycle straight past the colour it was aimed at. Costing it in but not clicking it is what
     * makes the target stable while the click is outstanding.
     */
    private static List<Click> rubix(ItemStack[] board, Set<Integer> pending, int fixedTarget) {
        List<Integer> slots = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (int slot = 0; slot < board.length; slot++) {
            ItemStack stack = board[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int color = RUBIX_CYCLE.indexOf(stack.getItem());
            if (color >= 0) {
                slots.add(slot);
                colors.add(color);
            }
        }
        if (slots.isEmpty()) {
            return List.of();
        }

        int size = RUBIX_CYCLE.size();
        int best = fixedTarget >= 0 && fixedTarget < size ? fixedTarget : cheapestTarget(colors, size);

        List<Click> solution = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (isPending(pending, slot)) {
                continue;
            }
            int color = colors.get(i);
            int clockwise = forwardSteps(color, best, size);
            int counter = forwardSteps(best, color, size);
            if (clockwise <= counter) {
                for (int step = 0; step < clockwise; step++) {
                    solution.add(Click.forward(slot));
                }
            } else {
                for (int step = 0; step < counter; step++) {
                    solution.add(Click.reverse(slot));
                }
            }
        }
        return solution;
    }

    /**
     * Which colour this board is cheapest to land on.
     *
     * <p>Exposed so a caller can work it out once and hold it. Asking again part-way through is what
     * lets the answer move: a half-turned board can make a different colour momentarily cheaper, and
     * a solve that follows it turns panes back the way they came. Measured on alpha before this was
     * separated out — seven clicks of work went out as sixteen, and as nineteen.
     */
    public static int rubixTarget(ItemStack[] board) {
        List<Integer> colors = new ArrayList<>();
        for (ItemStack stack : board) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int color = RUBIX_CYCLE.indexOf(stack.getItem());
            if (color >= 0) {
                colors.add(color);
            }
        }
        return colors.isEmpty() ? -1 : cheapestTarget(colors, RUBIX_CYCLE.size());
    }

    private static int cheapestTarget(List<Integer> colors, int size) {
        int best = 0;
        int bestCost = Integer.MAX_VALUE;
        for (int target = 0; target < size; target++) {
            int cost = 0;
            for (int color : colors) {
                cost += Math.min(forwardSteps(color, target, size), forwardSteps(target, color, size));
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = target;
            }
        }
        return best;
    }

    /** Steps from {@code from} to {@code to} going forwards around a cycle of {@code size}. */
    private static int forwardSteps(int from, int to, int size) {
        return ((to - from) % size + size) % size;
    }

    /** Every item whose name begins with the letter in the title. */
    private static List<Click> startsWith(ItemStack[] board, String letter, Set<Integer> pending) {
        if (letter == null || letter.isEmpty()) {
            return List.of();
        }
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < board.length; slot++) {
            String name = plainName(board[slot]);
            if (name != null && name.startsWith(letter) && !done(board[slot], pending, slot)) {
                slots.add(slot);
            }
        }
        Collections.shuffle(slots);
        return clicks(slots);
    }

    /**
     * Every item of the colour in the title, both sides put through {@link #COLOR_ALIASES} first.
     *
     * <p><b>Departure from the reference:</b> it rewrote only the item names, which is enough while
     * the chest says SILVER. Rewriting the captured word too costs a line and means the solve still
     * works if it ever says LIGHT GRAY instead.
     */
    private static List<Click> selectAll(ItemStack[] board, String color, Set<Integer> pending) {
        if (color == null || color.isEmpty()) {
            return List.of();
        }
        String wanted = alias(color);
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < board.length; slot++) {
            String name = plainName(board[slot]);
            if (name != null && alias(name).startsWith(wanted) && !done(board[slot], pending, slot)) {
                slots.add(slot);
            }
        }
        Collections.shuffle(slots);
        return clicks(slots);
    }

    /**
     * Which button to press on Melody's harp this instant, or an empty array for "not yet".
     *
     * <p>The board is six rows of nine. Row zero is the moving cursor — a magenta pane somewhere in
     * columns one to five. Exactly one of the lanes is live, marked by lime terracotta in its
     * column seven, and that terracotta <i>is</i> the button. Somewhere in columns one to five of
     * that same row sits a lime pane: the note to hit.
     *
     * <p>How many lanes there are is read off the board ({@link #lastRow}), not assumed. It was rows one to four
     * until Hypixel cut it to three: the owner's screenshot of 2026-11-11 shows lanes in rows one to three, the
     * magenta markers in rows zero and four, and nothing in column seven of row four. With four assumed, the
     * skip-edges burst pressed row four's empty slot and the count read "/4".
     *
     * <p>So the whole solve is "press when the cursor's column is the lime pane's column", and the
     * only reason this is hard is that it has to be checked every tick rather than computed once.
     *
     * <p>{@code skipEdges} presses the live row's button <i>and every row below it</i> in one go.
     * The rows below have not started moving yet, so their presses bank ahead — worth it in the
     * middle of the board, and refused at the edges and on the last row where it would misfire.
     */
    public static int[] melody(ItemStack[] board, boolean skipEdges) {
        if (board == null || board.length < 54) {
            return new int[0];
        }
        int cursor = columnOf(board, 0, Items.STAINED_GLASS_PANE.magenta());
        int row = activeRow(board);
        if (cursor < 0 || row < 0) {
            return new int[0];
        }
        int target = columnOf(board, row, Items.STAINED_GLASS_PANE.lime());
        if (target < 0 || target != cursor) {
            return new int[0];
        }

        int last = lastRow(board, row);
        boolean edge = cursor == 1 || cursor == 5;
        if (!skipEdges || !edge || row == last || (row == 1 && cursor == 1)) {
            return new int[] {index(row, 7)};
        }
        int[] burst = new int[last + 1 - row];
        for (int i = 0; i < burst.length; i++) {
            burst[i] = index(row + i, 7);
        }
        return burst;
    }

    /**
     * How much of this terminal the board says is left, or -1 when it cannot say: clicks for the five slot
     * terminals, as {@link #solve} counts them with nothing in flight, so it falls as the server takes each click
     * rather than as it is sent; rows still to play for Melody, which has no clicks to count.
     */
    public static int remaining(TerminalType type, ItemStack[] board, String capture, int rubixTarget) {
        if (type != TerminalType.MELODY) {
            return type == null || board == null
                    ? -1
                    : solve(type, board, capture, Set.of(), rubixTarget).size();
        }
        if (board == null || board.length < 54) {
            return -1;
        }
        int row = activeRow(board);
        return row < 0 ? -1 : lastRow(board, row) + 1 - row;
    }

    /**
     * The board's last lane: the lowest of rows one to four with a button in column seven (red terracotta, or lime
     * on the live row), and never above the live one. Three on Hypixel since November 2026, four before; see
     * {@link #melody}.
     */
    private static int lastRow(ItemStack[] board, int live) {
        for (int row = 4; row > live; row--) {
            ItemStack stack = board[index(row, 7)];
            if (stack != null
                    && (stack.getItem() == Items.DYED_TERRACOTTA.red()
                            || stack.getItem() == Items.DYED_TERRACOTTA.lime())) {
                return row;
            }
        }
        return live;
    }

    /** Which of rows one to four is live, or -1. Searched bottom-up, as the reference does. */
    private static int activeRow(ItemStack[] board) {
        for (int row = 4; row >= 1; row--) {
            ItemStack stack = board[index(row, 7)];
            if (stack != null && stack.getItem() == Items.DYED_TERRACOTTA.lime()) {
                return row;
            }
        }
        return -1;
    }

    /** The column one to five of {@code row} holding {@code item}, or -1. */
    private static int columnOf(ItemStack[] board, int row, Item item) {
        for (int column = 1; column <= 5; column++) {
            ItemStack stack = board[index(row, column)];
            if (stack != null && stack.getItem() == item) {
                return column;
            }
        }
        return -1;
    }

    /** Slot index of a cell in a nine-wide chest. */
    public static int index(int row, int column) {
        return row * 9 + column;
    }

    private static List<Click> clicks(List<Integer> slots) {
        List<Click> solution = new ArrayList<>(slots.size());
        for (int slot : slots) {
            solution.add(Click.forward(slot));
        }
        return solution;
    }

    private static boolean isRed(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == Items.STAINED_GLASS_PANE.red();
    }

    /**
     * Already dealt with — either the server has marked it done with an enchant glint, or we clicked
     * it and the board has not caught up yet.
     */
    private static boolean done(ItemStack stack, Set<Integer> pending, int slot) {
        return stack.hasFoil() || isPending(pending, slot);
    }

    private static boolean isPending(Set<Integer> pending, int slot) {
        return pending != null && pending.contains(slot);
    }

    /** The item's display name, stripped and lowercased, or null when there is no item. */
    private static String plainName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return TerminalType.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
    }

    /** Rewrite a leading colour word into the vocabulary the terminal titles use. */
    static String alias(String name) {
        for (Map.Entry<String, String> entry : COLOR_ALIASES.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return entry.getValue() + name.substring(entry.getKey().length());
            }
        }
        return name;
    }
}
