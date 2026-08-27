package com.maximpolyakov.quicklink.forge.network;

import java.util.HashSet;
import java.util.Set;

/**
 * Stack-scoped re-entrancy guard for QuickLink network traversals.
 *
 * <p>Every plug side is exposed to the world as a plain capability handler. Nothing stops another
 * plug -- or any pipe/mod that happens to sit between two plugs -- from routing a transfer straight
 * back into a network that is already being walked further down the same call stack, which ends in
 * a {@code StackOverflowError}. No single participant can see that cycle; only the traversal can,
 * by remembering which networks the current thread is already inside.
 *
 * <p>The guard is scoped to the call stack, never to a tick: {@link #enter} is always paired with
 * {@link #exit} in a {@code finally}, so a simulated pass and the executing pass that follows it run
 * at the same nesting depth and get the same answer.
 *
 * <p>Item, fluid and energy networks are separate graphs that happen to share the same key layout,
 * so keys are qualified by {@link Domain} and a busy fluid network never blocks an item transfer.
 */
public final class NetworkTransferGuard {

    private NetworkTransferGuard() {}

    public enum Domain { ITEM, FLUID, ENERGY }

    private static final ThreadLocal<Set<Long>> ACTIVE = ThreadLocal.withInitial(HashSet::new);

    private static long token(Domain domain, int networkKey) {
        return ((long) domain.ordinal() << 32) | (networkKey & 0xFFFFFFFFL);
    }

    /** @return {@code true} if the caller may traverse the network; {@code false} on re-entry. */
    public static boolean enter(Domain domain, int networkKey) {
        return ACTIVE.get().add(token(domain, networkKey));
    }

    /** Releases a key claimed by a successful {@link #enter}. */
    public static void exit(Domain domain, int networkKey) {
        Set<Long> active = ACTIVE.get();
        active.remove(token(domain, networkKey));
        if (active.isEmpty()) ACTIVE.remove(); // don't keep a map entry alive on pooled threads
    }

    public static boolean isActive(Domain domain, int networkKey) {
        return ACTIVE.get().contains(token(domain, networkKey));
    }

    /** Visible for testing. */
    public static int activeCount() {
        return ACTIVE.get().size();
    }
}
