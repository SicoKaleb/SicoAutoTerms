package com.sico.autoterms;

/**
 * The server's own tick as the client sees it: Hypixel sends one {@code ClientboundPingPacket} per server tick.
 *
 * <p>The part of AshFall's {@code ServerClock} the terminal opening gate reads, and nothing more: how many pings have
 * arrived, and whether they are still arriving. Fed from the connection's read hook on the netty thread at the
 * moment the packet is decoded, read on the client thread, so both sides hold one lock.
 */
public final class PingClock {

    /** How long the clock trusts itself after the last pings; past this the server has stalled or never pinged. */
    private static final long HEALTHY_WINDOW_NANOS = 500_000_000L;

    /** Pings needed inside the window before the clock is believed at all. */
    private static final int HEALTHY_MIN = 3;

    private final long[] arrivals = new long[HEALTHY_MIN];
    private int head;
    private int filled;
    private long ticks;

    /** Netty thread: one ping arrived. */
    public synchronized void onPing(long nanos) {
        arrivals[head] = nanos;
        head = (head + 1) % arrivals.length;
        filled = Math.min(filled + 1, arrivals.length);
        ticks++;
    }

    /** Server ticks counted since the connection started. */
    public synchronized long ticks() {
        return ticks;
    }

    /** Whether the last few pings all arrived recently enough for anything to be counted against them. */
    public synchronized boolean healthy() {
        if (filled < HEALTHY_MIN) {
            return false;
        }
        long floor = System.nanoTime() - HEALTHY_WINDOW_NANOS;
        for (long at : arrivals) {
            if (at < floor) {
                return false;
            }
        }
        return true;
    }

    public synchronized void reset() {
        head = 0;
        filled = 0;
        ticks = 0L;
    }
}
