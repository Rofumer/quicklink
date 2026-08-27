package com.maximpolyakov.quicklink.forge.network;

import com.maximpolyakov.quicklink.forge.network.NetworkTransferGuard.Domain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.maximpolyakov.quicklink.forge.network.NetworkTransferGuard.Domain.FLUID;

/**
 * The guard is the half of the plug fix that runs without a server. {@code FakeNetwork} below
 * has the shape of {@code FluidPlugBlockEntity.fillIntoNetwork} -- and of its item and energy twins:
 * a transfer arriving on one endpoint is offered to every other endpoint of the same network, and an
 * endpoint's neighbour may itself be a plug side that walks a network again, which is exactly how the
 * reported StackOverflowError was produced.
 */
class NetworkTransferGuardTest {

    private static final int NET_A = 0x00010001;
    private static final int NET_B = 0x00020002;

    @AfterEach
    void guardIsClean() {
        assertEquals(0, NetworkTransferGuard.activeCount(), "a traversal leaked a key");
    }

    @Test
    void reEntryIsRefusedUntilTheTraversalUnwinds() {
        assertTrue(NetworkTransferGuard.enter(FLUID, NET_A));
        assertFalse(NetworkTransferGuard.enter(FLUID, NET_A), "re-entering the same network must be refused");
        assertTrue(NetworkTransferGuard.enter(FLUID, NET_B), "a different network is still reachable");
        NetworkTransferGuard.exit(FLUID, NET_B);
        NetworkTransferGuard.exit(FLUID, NET_A);
        assertTrue(NetworkTransferGuard.enter(FLUID, NET_A), "the key must be reusable after unwinding");
        NetworkTransferGuard.exit(FLUID, NET_A);
    }

    @Test
    void keysDoNotLeakWhenATraversalThrows() {
        assertThrows(IllegalStateException.class, () -> {
            if (!NetworkTransferGuard.enter(FLUID, NET_A)) return;
            try {
                throw new IllegalStateException("boom");
            } finally {
                NetworkTransferGuard.exit(FLUID, NET_A);
            }
        });
        assertTrue(NetworkTransferGuard.enter(FLUID, NET_A), "the key must not be stuck after an exception");
        NetworkTransferGuard.exit(FLUID, NET_A);
    }

    @Test
    void guardIsPerThread() throws Exception {
        assertTrue(NetworkTransferGuard.enter(FLUID, NET_A));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(pool.submit(() -> {
                boolean got = NetworkTransferGuard.enter(FLUID, NET_A);
                if (got) NetworkTransferGuard.exit(FLUID, NET_A);
                return got;
            }).get(5, TimeUnit.SECONDS), "another thread's traversal must not be blocked");
        } finally {
            pool.shutdownNow();
            NetworkTransferGuard.exit(FLUID, NET_A);
        }
    }

    @Test
    void domainsAreIndependent() {
        assertTrue(NetworkTransferGuard.enter(FLUID, NET_A));
        for (Domain other : Domain.values()) {
            if (other == FLUID) continue;
            assertTrue(NetworkTransferGuard.enter(other, NET_A),
                    other + " must not be blocked by a busy fluid network with the same key");
            NetworkTransferGuard.exit(other, NET_A);
        }
        assertFalse(NetworkTransferGuard.enter(FLUID, NET_A));
        NetworkTransferGuard.exit(FLUID, NET_A);
    }

    /** The crash loop: the network routes into a plug side that fills the same network again. */
    @Test
    void aNetworkThatRoutesBackIntoItselfTerminates() {
        FakeNetwork net = new FakeNetwork(NET_A);
        Endpoint in = net.addEndpoint();
        Endpoint out = net.addEndpoint();
        out.neighbour = net.addEndpoint(); // the neighbour is another plug side of the same network

        assertEquals(0, in.fill(1000), "a pure cycle must move nothing instead of overflowing");
        assertEquals(1, net.depthPeak, "the network must be walked exactly once");
    }

    /** A cycle that leaves through a second network and comes back also terminates. */
    @Test
    void aCycleThroughAnotherNetworkTerminates() {
        FakeNetwork first = new FakeNetwork(NET_A);
        FakeNetwork second = new FakeNetwork(NET_B);
        Endpoint in = first.addEndpoint();
        Endpoint out = first.addEndpoint();
        out.neighbour = second.addEndpoint();          // hop into the other network ...
        second.addEndpoint().neighbour = first.addEndpoint(); // ... and straight back into this one

        assertEquals(0, in.fill(1000), "the cycle must unwind, not recurse");
        assertEquals(1, first.depthPeak);
        assertEquals(1, second.depthPeak, "the second network must still have been offered the fluid");
    }

    /** Normal routing is untouched: the guard only refuses a network already on the stack. */
    @Test
    void normalTransferStillReachesATank() {
        FakeNetwork net = new FakeNetwork(NET_A);
        Endpoint in = net.addEndpoint();
        FakeTank tank = new FakeTank(500);
        net.addEndpoint().neighbour = tank;

        assertEquals(500, in.fill(1000), "the tank must still be filled up to its capacity");
        assertEquals(500, tank.stored);
    }

    /** Two networks in a row is a legitimate chain, not a cycle, and must keep working. */
    @Test
    void chainingThroughASecondNetworkStillDelivers() {
        FakeNetwork first = new FakeNetwork(NET_A);
        FakeNetwork second = new FakeNetwork(NET_B);
        Endpoint in = first.addEndpoint();
        FakeTank tank = new FakeTank(400);
        first.addEndpoint().neighbour = second.addEndpoint();
        second.addEndpoint().neighbour = tank;

        assertEquals(400, in.fill(1000));
        assertEquals(400, tank.stored);
    }

    /** SIMULATE and the EXECUTE that follows run at the same depth, so they must agree. */
    @Test
    void simulateAndExecuteAgreeAtTheSameDepth() {
        FakeNetwork net = new FakeNetwork(NET_A);
        Endpoint in = net.addEndpoint();
        FakeTank tank = new FakeTank(300);
        net.addEndpoint().neighbour = tank;

        int simulated = in.fill(1000, true);
        assertEquals(300, simulated);
        assertEquals(0, tank.stored, "a simulation must not move fluid");
        assertEquals(simulated, in.fill(1000, false), "execute must accept what simulate promised");
    }

    // ---------------------------------------------------------------- fakes

    private interface Sink {
        int fill(int amount, boolean simulate);
    }

    private static final class FakeTank implements Sink {
        final int capacity;
        int stored;

        FakeTank(int capacity) { this.capacity = capacity; }

        @Override public int fill(int amount, boolean simulate) {
            int accepted = Math.max(0, Math.min(amount, capacity - stored));
            if (!simulate) stored += accepted;
            return accepted;
        }
    }

    /** One plug side. Filling it walks its network; its neighbour is whatever the side faces. */
    private static final class Endpoint implements Sink {
        final FakeNetwork network;
        Sink neighbour;

        Endpoint(FakeNetwork network) { this.network = network; }

        int fill(int amount) { return fill(amount, false); }

        @Override public int fill(int amount, boolean simulate) {
            return network.fillIntoNetwork(this, amount, simulate);
        }
    }

    private static final class FakeNetwork {
        final int key;
        final List<Endpoint> endpoints = new ArrayList<>();
        int depth;
        int depthPeak;

        FakeNetwork(int key) { this.key = key; }

        Endpoint addEndpoint() {
            Endpoint e = new Endpoint(this);
            endpoints.add(e);
            return e;
        }

        int fillIntoNetwork(Endpoint source, int amount, boolean simulate) {
            if (!NetworkTransferGuard.enter(FLUID, key)) return 0;
            depthPeak = Math.max(depthPeak, ++depth);
            try {
                int moved = 0;
                for (Endpoint e : endpoints) {
                    if (e == source || e.neighbour == null) continue;
                    moved += e.neighbour.fill(amount - moved, simulate);
                    if (moved >= amount) break;
                }
                return moved;
            } finally {
                depth--;
                NetworkTransferGuard.exit(FLUID, key);
            }
        }
    }
}
