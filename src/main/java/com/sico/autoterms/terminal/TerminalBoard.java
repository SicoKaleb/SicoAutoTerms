package com.sico.autoterms.terminal;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.item.ItemStack;

/**
 * What the open terminal currently looks like, assembled from the server's own packets.
 *
 * <h2>Why this is not the reference client's design</h2>
 *
 * <p>A terminal used to hand out a <b>new window id on every click</b> — the server threw the chest
 * away and opened a fresh one — and every solver in the wild, the reference included, used that
 * re-open as its acknowledgement. Its whole rate limit was one boolean set when a click went out and
 * cleared only by the next {@code ClientboundOpenScreenPacket}, and each scraped item carried the
 * window id it arrived under so stale ones could be told apart.
 *
 * <p>Hypixel now <b>keeps one window id for the whole terminal</b> and pushes slot updates into it.
 * No re-open ever arrives, so that boolean latches on the first click and the solver stalls. Copying
 * the design across would reproduce the bug.
 *
 * <p>The staleness problem does not disappear, though — it moves. "Which window did this slot come
 * from" becomes "has the server acted on my click yet", and the answer is right there in the wire:
 * <b>the server changing a slot is the acknowledgement</b>. So a clicked slot goes into
 * {@link #pending} and comes out when a {@code SetSlot} for it arrives. Throughput is no longer tied
 * to a round trip; the only thing pacing clicks is the delay the module chooses.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #onPacket} runs on the netty thread and the solve runs on the client thread. The board
 * array is therefore replaced rather than mutated — a fifty-four-reference copy per packet is
 * nothing next to a torn read — so a reader always sees one whole consistent board.
 */
public final class TerminalBoard {

    /** Nothing is open. Also what {@code containerId} reads as after a reset. */
    private static final int NONE = -1;

    private volatile TerminalType type;
    private volatile String capture;
    private volatile int containerId = NONE;
    private volatile int stateId;
    private volatile ItemStack[] slots = new ItemStack[0];
    private volatile long openedAt;
    private volatile long lastUpdateAt;

    /** Clicked, but the server has not shown us the result yet. */
    private final Set<Integer> pending = ConcurrentHashMap.newKeySet();

    /**
     * What was in each pending slot at the moment we clicked it.
     *
     * <p><b>This is the difference between "the server said something about that slot" and "the server
     * acted on my click", and the first one is not good enough.</b> SkyBlock re-pushes inventory
     * constantly — an ambient two container events every three seconds with nothing sent, measured —
     * so releasing a click because a {@code SetSlot} arrived releases most of them before they have
     * landed, and the next solve sends them again.
     *
     * <p>Measured on alpha, 25 terminals: with a click every tick this cost <b>58%</b> extra clicks,
     * and in batches of five or more <b>150-180%</b> — while the same solves at one click per 150 ms
     * wasted nothing at all, because by then the change had genuinely arrived. Comparing the content
     * is what makes the acknowledgement mean what it says.
     */
    private final Map<Integer, ItemStack> clickedAs = new ConcurrentHashMap<>();

    /** When each outstanding click left, so unrelated slot traffic cannot keep a rejected click pending. */
    private final Map<Integer, Long> clickedAt = new ConcurrentHashMap<>();

    /**
     * Rubix's chosen colour, latched for the terminal.
     *
     * <p>The cheapest target is a property of the board we started from. Recomputing it every solve
     * lets it move while clicks are in flight — the board is half-turned, a different colour is
     * momentarily cheaper, and the solve turns round and chases it. That is the second half of the
     * waste the alpha ladder measured: seven clicks of work sent as sixteen and as nineteen.
     */
    private volatile int rubixTarget = -1;

    /**
     * Which slots the server has described since the terminal opened.
     *
     * <p>Hypixel fills a menu with <b>one {@code SetSlot} per slot</b>, each bumping {@code stateId} —
     * observed running 1, 2, 3 … 63 on the SkyBlock Menu. So "the board is complete" is a countable
     * fact rather than a guess, and it is a far better thing to wait for than a stopwatch: an empty
     * slot still gets its packet, so this reaches the window size on every terminal.
     */
    private final Set<Integer> described = ConcurrentHashMap.newKeySet();

    private volatile boolean loaded;
    private volatile long loadedAt;

    public TerminalBoard() {}

    /** Which terminal is open, or null. */
    public TerminalType type() {
        return type;
    }

    /** The letter or colour word from the title, for the two terminals that carry one. */
    public String capture() {
        return capture;
    }

    /** The window every click must be addressed to, or -1. */
    public int containerId() {
        return containerId;
    }

    /** The board as the server last described it. Safe to read from any thread. */
    public ItemStack[] board() {
        return slots;
    }

    /** Slots clicked whose result has not come back. */
    public Set<Integer> pending() {
        return pending;
    }

    /**
     * How many clicks the server has acted on, and how many it has thrown away, since this connection
     * started. Compare, never interpret: what a caller wants is whether either moved since its last burst.
     *
     * <p>This is the signal an adaptive cadence needs and the only honest one available. The server never
     * says "slow down"; it says it by not acting on a click, which shows up here as a drop — either in a
     * whole-board reply that acted on some of the batch and not the rest, or in the resync that fires when
     * no reply comes at all.
     */
    public int ackedTotal() {
        return ackedTotal.get();
    }

    public int droppedTotal() {
        return droppedTotal.get();
    }

    /**
     * Atomic because two threads write them: the acknowledgements and a whole-board reply's drops are
     * counted on the netty thread, and {@link #resync}'s drops on the client thread. A plain
     * {@code +=} from both could lose one, and a lost drop is the one signal an adaptive cadence reads.
     */
    private final AtomicInteger ackedTotal = new AtomicInteger();

    private final AtomicInteger droppedTotal = new AtomicInteger();

    /** When the terminal opened, for the first-click delay. */
    public long openedAt() {
        return openedAt;
    }

    public boolean open() {
        return type != null && containerId != NONE;
    }

    /**
     * Has the server finished describing the board.
     *
     * <p>The point of asking: the first click cannot be trusted until the whole grid is known, and
     * the usual way to handle that is a fixed wait long enough to cover it. This is the same
     * guarantee taken from the data instead, so the wait is as long as the server actually took.
     */
    public boolean loaded() {
        return loaded;
    }

    /** When the board became complete, or 0. */
    public long loadedAt() {
        return loadedAt;
    }

    /** How many of the terminal's slots the server has described so far. */
    public int described() {
        return described.size();
    }

    /** Rubix's latched colour for this terminal, or -1 before the first solve. */
    public int rubixTarget() {
        return rubixTarget;
    }

    public void rubixTarget(int target) {
        rubixTarget = target;
    }

    /** Feed the container channel. Called on the netty thread. */
    public void onPacket(Packet<?> packet) {
        // The receive hook sits on Connection.channelRead0, after vanilla's bundler has folded a bundle into
        // one ClientboundBundlePacket, so whatever a server sends inside a bundle reaches here as that and
        // nothing else. A SetSlot lost that way is an acknowledgement the board never sees: the click reads
        // as refused, is re-sent after the resync, and on Panes a re-sent click turns a green pane back to
        // red. Whether Hypixel bundles container packets has not been observed; unwrapping costs nothing.
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> inner : bundle.subPackets()) {
                onPacket(inner);
            }
            return;
        }
        if (packet instanceof ClientboundOpenScreenPacket open) {
            onOpen(open);
        } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            onSetSlot(slot);
        } else if (packet instanceof ClientboundContainerSetContentPacket content) {
            onSetContent(content);
        } else if (packet instanceof ClientboundContainerClosePacket close) {
            if (close.getContainerId() == containerId) {
                reset();
            }
        }
    }

    private void onOpen(ClientboundOpenScreenPacket open) {
        TerminalType opened = TerminalType.identify(open.getTitle().getString());
        if (opened == null) {
            // Any other menu means we are not in a terminal any more. Leaving the old board armed is
            // how a solve ends up clicking a bank GUI.
            reset();
            return;
        }
        type = opened;
        capture = opened.capture(open.getTitle().getString());
        containerId = open.getContainerId();
        ItemStack[] fresh = new ItemStack[opened.windowSize()];
        Arrays.fill(fresh, ItemStack.EMPTY);
        slots = fresh;
        pending.clear();
        clickedAs.clear();
        clickedAt.clear();
        rubixTarget = -1;
        described.clear();
        loaded = false;
        loadedAt = 0L;
        stateId = 0;
        openedAt = System.currentTimeMillis();
        lastUpdateAt = openedAt;
    }

    private void onSetSlot(ClientboundContainerSetSlotPacket packet) {
        ItemStack[] current = slots;
        if (packet.getContainerId() != containerId || containerId == NONE) {
            return;
        }
        int slot = packet.getSlot();
        if (slot < 0 || slot >= current.length) {
            // At or past the window size is the player's own inventory, which no terminal wants.
            return;
        }
        ItemStack[] next = current.clone();
        next[slot] = packet.getItem();
        slots = next;
        stateId = packet.getStateId();
        acknowledge(slot, packet.getItem());
        lastUpdateAt = System.currentTimeMillis();
        if (described.add(slot) && !loaded && described.size() >= current.length) {
            loaded = true;
            loadedAt = lastUpdateAt;
        }
    }

    private void onSetContent(ClientboundContainerSetContentPacket packet) {
        if (packet.containerId() != containerId || containerId == NONE) {
            return;
        }
        List<ItemStack> items = packet.items();
        ItemStack[] next = new ItemStack[slots.length];
        for (int slot = 0; slot < next.length; slot++) {
            next[slot] = slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
        }
        slots = next;
        stateId = packet.stateId();
        // Per slot, NOT a blanket clear. A whole-board push is the most common ambient packet on
        // SkyBlock, and treating it as "every outstanding click has landed" is what made a twelve-click
        // Panes cost thirty-eight clicks on alpha.
        int before = pending.size();
        for (int slot = 0; slot < next.length; slot++) {
            acknowledge(slot, next[slot]);
        }
        int acked = before - pending.size();
        int dropped = 0;
        // A whole-board push that acted on SOME of our outstanding clicks is the server answering the
        // batch. Whatever it did not act on, it is not going to: those clicks were dropped, not still
        // in flight, and keeping them pending only makes the solve sit on its hands.
        //
        // This is worth the whole of the terminal's remaining time. Measured on alpha: ten clicks went
        // out at +4ms, the server took five and said so at +103ms, and the module then idled for
        // SIX HUNDRED AND NINETY-SIX MILLISECONDS waiting for a 650ms resync before re-sending the
        // five it had already been told about. The server's own answer is the signal; the timer is
        // only there for when no answer comes at all.
        //
        // Gated on acked > 0 deliberately. SkyBlock pushes whole boards ambiently, and one of those
        // arriving in the round trip before the server had processed anything would otherwise release
        // clicks that really were still in flight. Having acted on at least one of them is what makes
        // this push a reply rather than background noise.
        if (acked > 0 && !pending.isEmpty()) {
            dropped = pending.size();
            droppedTotal.addAndGet(dropped);
            pending.clear();
            clickedAs.clear();
            clickedAt.clear();
        }
        lastUpdateAt = System.currentTimeMillis();
        if (!loaded) {
            loaded = true;
            loadedAt = lastUpdateAt;
        }
    }

    /**
     * Adopt a terminal that was already open, from the menu the client is holding.
     *
     * <p>The board is built from packets, and a module only sees packets while it is switched on —
     * so enabling this in the middle of a terminal would otherwise leave it inert until the chest was
     * closed and re-opened, because the {@code OpenScreen} that arms everything has already gone by.
     * The client's own menu carries the same contents, so it can stand in for the packets missed.
     *
     * <p>The board is taken as complete: {@link #openedAt} is stamped now, so the first-click delay
     * still runs from the moment this is adopted rather than firing instantly.
     */
    public void adopt(String title, int menuContainerId, List<ItemStack> items) {
        TerminalType opened = TerminalType.identify(title);
        if (opened == null) {
            reset();
            return;
        }
        type = opened;
        capture = opened.capture(title);
        containerId = menuContainerId;
        ItemStack[] fresh = new ItemStack[opened.windowSize()];
        for (int slot = 0; slot < fresh.length; slot++) {
            fresh[slot] = slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
        }
        slots = fresh;
        pending.clear();
        clickedAs.clear();
        clickedAt.clear();
        rubixTarget = -1;
        described.clear();
        openedAt = System.currentTimeMillis();
        lastUpdateAt = openedAt;
        // Taken from the client's own menu, so it is whole by construction.
        loaded = true;
        loadedAt = openedAt;
    }

    /** Record that we have clicked this slot, so the next solve does not pick it again. */
    public void clicked(int slot) {
        ItemStack[] current = slots;
        pending.add(slot);
        clickedAs.put(slot, slot >= 0 && slot < current.length ? current[slot] : ItemStack.EMPTY);
        clickedAt.put(slot, System.currentTimeMillis());
    }

    /**
     * Release a click only once the slot it was aimed at genuinely changed.
     *
     * <p>An unchanged slot means the server has re-sent what was already there, which says nothing
     * about our click. Keeping it pending costs one more solve; releasing it early costs a duplicate
     * click, and those were the great majority of every packet this module wasted.
     */
    private void acknowledge(int slot, ItemStack now) {
        if (!pending.contains(slot)) {
            return;
        }
        ItemStack before = clickedAs.get(slot);
        if (before == null || !ItemStack.matches(before, now == null ? ItemStack.EMPTY : now)) {
            pending.remove(slot);
            clickedAs.remove(slot);
            clickedAt.remove(slot);
            ackedTotal.incrementAndGet();
        }
    }

    /**
     * Drop only clicks the server has not acknowledged within {@code thresholdMs}.
     *
     * <p>Without this a click the server ignored — a mis-timed one, or one lost to a lag spike —
     * would keep its slot excluded forever and the solve would stop short of finishing. Each click
     * gets its own deadline, so unrelated container traffic cannot prolong a refused final click.
     */
    public void resync(long thresholdMs) {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int dropped = 0;
        for (int slot : Set.copyOf(pending)) {
            Long sentAt = clickedAt.get(slot);
            if (sentAt == null || now - sentAt > thresholdMs) {
                pending.remove(slot);
                clickedAs.remove(slot);
                clickedAt.remove(slot);
                dropped++;
            }
        }
        if (dropped == 0) {
            return;
        }
        droppedTotal.addAndGet(dropped);
    }

    public void reset() {
        type = null;
        capture = null;
        containerId = NONE;
        stateId = 0;
        slots = new ItemStack[0];
        pending.clear();
        clickedAs.clear();
        clickedAt.clear();
        rubixTarget = -1;
        described.clear();
        loaded = false;
        loadedAt = 0L;
        openedAt = 0L;
        lastUpdateAt = 0L;
    }
}
