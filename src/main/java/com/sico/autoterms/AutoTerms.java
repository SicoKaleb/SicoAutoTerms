package com.sico.autoterms;

import com.sico.autoterms.AutoTermsConfig.Mode;
import com.sico.autoterms.terminal.TerminalBoard;
import com.sico.autoterms.terminal.TerminalSolve;
import com.sico.autoterms.terminal.TerminalType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solves the floor seven and Master Mode seven phase-three terminals: AshFall's Auto Terminal, cut down to its Burst
 * and Single cadences (Single is called Normal here) and to five settings.
 *
 * <p>The rules live in {@link TerminalSolve} and the board in {@link TerminalBoard}, both ported unchanged; this class
 * is the cadence. What AshFall's module had and this one does not, on purpose: the burst-delay learner (the Click
 * delay is the gap between bursts here, as asked), the Randomization row (no setting for it, so every delay means its
 * number, as AshFall's Adaptive mode already did), the per-terminal toggles (all on), Dungeons only (off in AshFall by
 * default), and the debug lines. What it keeps without a setting, at AshFall's defaults: Melody presses the live row
 * and every row below it at an edge, the next clicks are tinted green, and hand clicks are refused while a terminal is
 * being solved.
 *
 * <p>Every click goes through vanilla's own {@code handleContainerInput}. A {@code CLONE} click in survival moves
 * nothing locally, so the packet leaves with nothing changed and nothing carried.
 */
public final class AutoTerms {

    private static final Logger LOG = LoggerFactory.getLogger("sicoautoterms");

    /**
     * How close behind our own last click a new window has to open to count as the same terminal continuing. Hypixel
     * keeps one window per terminal now, so this is normally never reached; it stays because Hypixel has been seen
     * doing both.
     */
    private static final long REOPEN_GRACE_MS = 400L;

    /** How long past the opening wait a board that has not finished describing itself is still waited for. */
    private static final long BOARD_GRACE_MS = 600L;

    /** Server ticks that must have passed since the window opened before the first click (scaled for short waits). */
    private static final int OPEN_SERVER_TICKS = 4;

    /**
     * How long a terminal with clicks left may go without one before the pacing state is thrown away and started again.
     * Above every gap a setting can ask for (the Click delay tops out at 1000), so it only fires when something latched.
     */
    private static final long STALL_MS = 2500L;

    /** AshFall's default for Melody skip edges. */
    private static final boolean MELODY_SKIP_EDGES = true;

    /** AshFall's highlight colour (its Theme.GREEN). */
    private static final int HIGHLIGHT = 0xFF4CD471;

    private final AutoTermsConfig config;
    private final PingClock clock;
    private final TerminalBoard terminal = new TerminalBoard();

    /** The slots the last solve wanted, for the slot tint, which runs once per slot per frame. */
    private final Set<Integer> highlighted = ConcurrentHashMap.newKeySet();

    /** The melody button pressed last, and when, so an alignment held across two ticks is not pressed twice. */
    private int lastMelodySlot = -1;

    private long lastMelodyAt;

    /** Clicks sent for the terminal currently open. */
    private int clicksSent;

    /** Burst: our copy of the server's allowance, and when it last hit zero. */
    private int tokens;

    private long drainedAt;

    /** When the last click went out, in either mode. Normal paces from it; the re-open check reads it. */
    private long lastClickAt;

    /** When the current terminal session began, on the wall clock and on the server's. */
    private long sessionStartAt;

    private long sessionStartTicks;

    /** The terminal type of the session in progress, to tell a re-open from a genuinely new terminal. */
    private TerminalType lastSessionType;

    /** The colour this Rubix is being turned to, kept across a re-open. */
    private int sessionRubixTarget = -1;

    /** True once this terminal re-opened behind our own click: a burst then lands one click per window. */
    private boolean reopenRegime;

    /** Which window the session state above belongs to. */
    private long lastTerminalOpenedAt;

    public AutoTerms(AutoTermsConfig config, PingClock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** Netty thread: assemble the board off the wire. */
    public void onPacketReceived(Packet<?> packet) {
        terminal.onPacket(packet);
    }

    /**
     * A close of the client's own for the terminal's window. The server sends no close packet for a close the client
     * made, so without this the board would say a terminal is open after Escape until the next window replaced it.
     */
    public void onPacketSend(Packet<?> packet) {
        if (packet instanceof ServerboundContainerClosePacket close
                && terminal.open()
                && close.getContainerId() == terminal.containerId()) {
            terminal.reset();
        }
    }

    /** Client thread, once a tick: solve and click. */
    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !terminal.open()) {
            highlighted.clear();
            lastMelodySlot = -1;
            return;
        }
        // The board is built from packets, which arrive before the screen exists and keep arriving after it is gone.
        // Clicking is only legal while the menu vanilla sends the click against really is the terminal's.
        int containerId = terminal.containerId();
        if (player.containerMenu == null || player.containerMenu.containerId != containerId) {
            return;
        }
        TerminalType type = terminal.type();
        if (type == null) {
            highlighted.clear();
            return;
        }
        noteSession(type);
        lastSessionType = type;

        if (type == TerminalType.MELODY) {
            solveMelody(player, containerId);
            return;
        }
        lastMelodySlot = -1;
        terminal.resync(config.resyncDelay());

        List<TerminalSolve.Click> solution = solveBoard(type);
        if (solution.isEmpty() || !firstClickReady(config.fcDelay())) {
            return;
        }
        if (clicksSent > 0 && System.currentTimeMillis() - lastClickAt > STALL_MS) {
            unstick(solution.size());
            return;
        }
        if (config.mode() == Mode.BURST) {
            paceBurst(player, containerId, solution);
        } else {
            paceNormal(player, containerId, solution);
        }
    }

    /** A new window: a terminal continuing behind our own click keeps its session, anything else starts one. */
    private void noteSession(TerminalType type) {
        long openedAt = terminal.openedAt();
        if (openedAt == lastTerminalOpenedAt) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean continuation = lastClickAt != 0L && now - lastClickAt <= REOPEN_GRACE_MS && type == lastSessionType;
        lastTerminalOpenedAt = openedAt;
        tokens = config.burstAmount();
        drainedAt = 0L;
        if (continuation) {
            reopenRegime = true;
            // The board clears the Rubix colour on every open; the session's colour is put back so a re-open does
            // not recompute it from a half-turned board and chase a different one.
            if (type == TerminalType.RUBIX && sessionRubixTarget >= 0) {
                terminal.rubixTarget(sessionRubixTarget);
            }
            return;
        }
        sessionStartAt = openedAt;
        sessionStartTicks = clock.ticks();
        sessionRubixTarget = -1;
        clicksSent = 0;
        lastClickAt = 0L;
        reopenRegime = false;
    }

    /** This tick's solve of the board, its clicks highlighted. */
    private List<TerminalSolve.Click> solveBoard(TerminalType type) {
        // Rubix's colour is decided once for the terminal and then held: asked again each solve, a half-turned board
        // can make a different colour momentarily cheapest and the solve turns panes back the way they came.
        ItemStack[] board = terminal.board();
        if (type == TerminalType.RUBIX && terminal.rubixTarget() < 0) {
            terminal.rubixTarget(TerminalSolve.rubixTarget(board));
        }
        if (type == TerminalType.RUBIX) {
            sessionRubixTarget = terminal.rubixTarget();
        }
        List<TerminalSolve.Click> solution =
                TerminalSolve.solve(type, board, terminal.capture(), terminal.pending(), terminal.rubixTarget());
        highlighted.clear();
        for (TerminalSolve.Click click : solution) {
            highlighted.add(click.slot());
        }
        return solution;
    }

    /**
     * Burst: as many clicks off ONE solve as the allowance holds, then the Click delay before it comes back whole.
     *
     * <p>Taken off one solve rather than re-solving between clicks, which is what makes it right for Rubix: a pane
     * wanting three steps has them consecutively in the list, and re-reading the board between them would see a colour
     * the server has not moved yet.
     */
    private void paceBurst(LocalPlayer player, int containerId, List<TerminalSolve.Click> solution) {
        int allowance = spendable();
        if (allowance <= 0) {
            return;
        }
        int cap = reopenRegime ? 1 : allowance;
        int sent = 0;
        for (TerminalSolve.Click click : solution) {
            if (sent >= cap) {
                break;
            }
            send(player, containerId, click);
            sent++;
        }
        tokens -= sent;
        if (tokens <= 0) {
            drainedAt = System.currentTimeMillis();
        }
        clicksSent += sent;
    }

    /** The allowance right now: the Burst amount, then a flat Click delay after it empties before it returns whole. */
    private int spendable() {
        if (tokens <= 0 && System.currentTimeMillis() - drainedAt >= config.clickDelay()) {
            tokens = config.burstAmount();
        }
        return tokens;
    }

    /**
     * Normal: one click, then the Click delay before the next. Only the first click of the solve is taken; the board
     * updates on the server's answer during the gap, so the next tick re-solves against a board that actually moved.
     */
    private void paceNormal(LocalPlayer player, int containerId, List<TerminalSolve.Click> solution) {
        if (System.currentTimeMillis() - lastClickAt < config.clickDelay()) {
            return;
        }
        send(player, containerId, solution.get(0));
        clicksSent++;
    }

    /**
     * The net under the cadence: forget every outstanding click and every pacing wait, so the next tick re-solves the
     * board as the server last described it. The board, the Rubix colour and the session are kept.
     */
    private void unstick(int left) {
        terminal.resync(0L);
        tokens = config.burstAmount();
        drainedAt = 0L;
        lastClickAt = 0L;
        reopenRegime = false;
        LOG.warn("terminal stalled, restarting its clicks type={} left={} sent={}", terminal.type(), left, clicksSent);
    }

    /**
     * Whether the opening wait is over: the FC delay since the window opened, the board fully described (or a grace
     * past the delay), and the server alive for a few of its own ticks since the open — a wall clock runs through a
     * freeze, the ping stream does not. Only the first click of a session is gated.
     */
    private boolean firstClickReady(long openingDelay) {
        if (clicksSent > 0) {
            return true;
        }
        long waited = System.currentTimeMillis() - sessionStartAt;
        if (waited < openingDelay) {
            return false;
        }
        if (!terminal.loaded() && waited < openingDelay + BOARD_GRACE_MS) {
            return false;
        }
        return !clock.healthy() || clock.ticks() - sessionStartTicks >= openServerTicks(openingDelay);
    }

    /** Four server ticks, or as many as the wait itself spans when it is shorter, so a low FC delay means its number. */
    static int openServerTicks(long openingDelayMs) {
        return (int) Math.min(OPEN_SERVER_TICKS, Math.max(0L, openingDelayMs) / 50L);
    }

    /**
     * Melody, a timing problem rather than a list of slots: press when the cursor lines up with the live row's note.
     * Its own opening gate on the FC delay and no click pacing. A press the server did not act on leaves the same row
     * live, so the same button may be pressed again once a Resync delay has passed.
     */
    private void solveMelody(LocalPlayer player, int containerId) {
        if (!firstClickReady(config.fcDelay())) {
            return;
        }
        int[] press = TerminalSolve.melody(terminal.board(), MELODY_SKIP_EDGES);
        highlighted.clear();
        for (int slot : press) {
            highlighted.add(slot);
        }
        long now = System.currentTimeMillis();
        if (press.length == 0 || (press[0] == lastMelodySlot && now - lastMelodyAt < config.resyncDelay())) {
            return;
        }
        // The skip burst is several buttons at one alignment, so they go together or not at all.
        for (int slot : press) {
            send(player, containerId, TerminalSolve.Click.forward(slot));
        }
        clicksSent += press.length;
        lastMelodySlot = press[0];
        lastMelodyAt = now;
    }

    private void send(LocalPlayer player, int containerId, TerminalSolve.Click click) {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractContainerMenu menu = player.containerMenu;
        // Vanilla plays the click against the client menu first and indexes the slot list without a bound.
        if (minecraft.gameMode == null || click.slot() < 0 || click.slot() >= menu.slots.size()) {
            return;
        }
        minecraft.gameMode.handleContainerInput(containerId, click.slot(), click.button(), click.input(), player);
        lastClickAt = System.currentTimeMillis();
        terminal.clicked(click.slot());
        if (click.input() == ContainerInput.PICKUP) {
            // Unlike CLONE, vanilla really ran this one and took the stack into the carried slot. Left there, the next
            // click would be a place rather than a pick; the server corrects it a round trip later anyway.
            menu.setCarried(ItemStack.EMPTY);
        }
    }

    /** Tint a slot the solve is about to click; called before the slot draws its item. */
    public void paintSlot(GuiGraphicsExtractor graphics, Slot slot) {
        if (!highlighted.isEmpty() && highlighted.contains(slot.index)) {
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, HIGHLIGHT);
        }
    }

    /** Whether a hand click on this screen should be refused: one landing between two of ours moves the board. */
    public boolean blocksManualClick(AbstractContainerScreen<?> screen) {
        return terminal.open() && terminal.type() != null && screen.getMenu().containerId == terminal.containerId();
    }

    /**
     * How much of the open terminal is left (clicks, or Melody's rows), or -1 before its board has loaded or with none
     * open. No-gui's progress line, read once a tick while it hides a terminal.
     */
    public int remaining() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !terminal.loaded() || player.containerMenu.containerId != terminal.containerId()) {
            return -1;
        }
        return TerminalSolve.remaining(terminal.type(), terminal.board(), terminal.capture(), terminal.rubixTarget());
    }

    /** The board this solves against, for the gametests. */
    public TerminalBoard terminal() {
        return terminal;
    }

    /** The connection ended: nothing of this terminal or this server carries over. */
    public void reset() {
        terminal.reset();
        highlighted.clear();
        lastMelodySlot = -1;
        lastMelodyAt = 0L;
        clicksSent = 0;
        tokens = 0;
        drainedAt = 0L;
        lastClickAt = 0L;
        sessionStartAt = 0L;
        sessionStartTicks = 0L;
        lastSessionType = null;
        sessionRubixTarget = -1;
        reopenRegime = false;
        lastTerminalOpenedAt = 0L;
    }
}
