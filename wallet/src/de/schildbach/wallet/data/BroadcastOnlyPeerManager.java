package de.schildbach.wallet.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.core.listeners.PeerConnectedEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.pepepow.wallet.BuildConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;

/**
 * RAM-only P2P broadcast manager for Session Wallet transactions.
 * 
 * CRITICAL RULES:
 * - Uses MemoryBlockStore only (NEVER touches disk blockstore)
 * - Does NOT attach any Wallet to PeerGroup
 * - Does NOT modify chainHead or persist anything
 * - Does NOT restart SPV or trigger FULL_SPV mode
 * - Keeps connections minimal and short-lived (battery saving)
 * 
 * This is a "broadcast-only" lane that is completely isolated from the
 * canonical FULL_SPV mode.
 */
public class BroadcastOnlyPeerManager {
    private static final Logger log = LoggerFactory.getLogger(BroadcastOnlyPeerManager.class);

    private static final int MAX_CONNECTIONS = 3;
    private static final long DEFAULT_BROADCAST_TIMEOUT_MS = 8000;
    private static final long PEER_DISCOVERY_TIMEOUT_MS = 5000;
    private static final long IDLE_SHUTDOWN_DELAY_MS = 30000;
    private static final String USER_AGENT_NAME = "pepepow-android-bcast";

    private final Context appContext;
    private final NetworkParameters params;
    private final String sid; // FASTBOOT_SESSION_ID

    private final Object lock = new Object();
    private BlockStore memoryStore;
    private BlockChain chain;
    private PeerGroup peerGroup;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> shutdownFuture;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Listener for peer connect events (to trigger broadcast retry)
    public interface PeerConnectListener {
        void onPeersAvailable();
    }

    @Nullable
    private PeerConnectListener peerConnectListener;

    public BroadcastOnlyPeerManager(Context appContext, NetworkParameters params, String sid) {
        this.appContext = appContext;
        this.params = params;
        this.sid = sid;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BroadcastOnlyPeerManager-scheduler");
            t.setDaemon(true);
            return t;
        });
        log.info("BCAST[sid={}] BroadcastOnlyPeerManager created", sid);
    }

    /**
     * Set listener for peer connect events (to trigger broadcast retry).
     */
    public void setPeerConnectListener(PeerConnectListener listener) {
        this.peerConnectListener = listener;
    }

    /**
     * Get real connected peer count directly from PeerGroup.
     * NEVER use manual counters - they can drift.
     */
    private int getRealPeerCount() {
        if (peerGroup == null || !peerGroup.isRunning()) {
            return 0;
        }
        return peerGroup.getConnectedPeers().size();
    }

    /**
     * Starts the PeerGroup if not already running, waiting for at least one peer
     * connection.
     * Uses RAM-only objects. NEVER touches disk.
     *
     * @param timeoutMs Maximum time to wait for peer connections
     * @return true if at least one peer connected within timeout
     */
    public boolean startIfNeeded(long timeoutMs) {
        synchronized (lock) {
            cancelPendingShutdown();

            if (running.get() && peerGroup != null && peerGroup.isRunning()) {
                int realPeers = getRealPeerCount();
                log.info("BCAST[sid={}] peerGroup already running, peers={}", sid, realPeers);
                return realPeers > 0;
            }

            try {
                // Log seeds info before startup
                String[] seeds = params.getDnsSeeds();
                int seedsCount = seeds != null ? seeds.length : 0;
                log.info("BCAST[sid={}] start seeds={} peersTarget={}", sid, seedsCount, MAX_CONNECTIONS);

                log.info("BCAST[sid={}] peerGroup start: initializing RAM-only P2P broadcast lane", sid);

                // Create RAM-only objects - NEVER touch disk
                memoryStore = new MemoryBlockStore(params);
                chain = new BlockChain(params, memoryStore);
                peerGroup = new PeerGroup(params, chain);

                // Configure PeerGroup
                peerGroup.setMaxConnections(MAX_CONNECTIONS);
                peerGroup.addPeerDiscovery(new DnsDiscovery(params));
                peerGroup.setUserAgent(USER_AGENT_NAME, BuildConfig.VERSION_NAME);

                // DO NOT attach any Wallet to peerGroup - this is broadcast-only

                // Add peer connection listeners for tracking
                final CountDownLatch firstPeerLatch = new CountDownLatch(1);

                peerGroup.addConnectedEventListener((peer, peerCount) -> {
                    // Query real peer count - NEVER use manual counters
                    int realCount = getRealPeerCount();
                    log.info("BCAST[sid={}] peer connected: {} total={}", sid, peer.getAddress(), realCount);
                    firstPeerLatch.countDown();

                    // Notify listener to trigger pending broadcast retry
                    if (peerConnectListener != null && realCount > 0) {
                        peerConnectListener.onPeersAvailable();
                    }
                });

                peerGroup.addDisconnectedEventListener((peer, peerCount) -> {
                    // Query real peer count - NEVER use manual counters
                    int realCount = getRealPeerCount();
                    log.info("BCAST[sid={}] peer disconnected: {} total={}", sid, peer.getAddress(), realCount);
                });

                // Start PeerGroup
                peerGroup.start();
                running.set(true);
                log.info("BCAST[sid={}] peerGroup started, waiting for peers (timeout={}ms)", sid, timeoutMs);

                // Wait for at least one peer
                boolean connected = firstPeerLatch.await(
                        Math.min(timeoutMs, PEER_DISCOVERY_TIMEOUT_MS), TimeUnit.MILLISECONDS);

                if (connected) {
                    log.info("BCAST[sid={}] peerGroup ready, peers={}", sid, getRealPeerCount());
                } else {
                    log.warn("BCAST[sid={}] peerGroup no peers connected within timeout", sid);
                }

                return connected;

            } catch (BlockStoreException e) {
                log.error("BCAST[sid={}] peerGroup start FAILED: BlockStoreException: {}", sid, e.getMessage(), e);
                cleanup();
                return false;
            } catch (InterruptedException e) {
                log.warn("BCAST[sid={}] peerGroup start interrupted", sid);
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.error("BCAST[sid={}] peerGroup start FAILED: {}: {}", sid, e.getClass().getSimpleName(),
                        e.getMessage(), e);
                cleanup();
                return false;
            }
        }
    }

    /**
     * Broadcasts a transaction via P2P.
     *
     * @param tx        The signed transaction to broadcast
     * @param timeoutMs Maximum time to wait for broadcast completion
     * @return BroadcastResult indicating success, pending, or rejection
     */
    public BroadcastResult broadcastTransaction(Transaction tx, long timeoutMs) {
        final String txId = tx.getTxId().toString();
        final long startTime = System.currentTimeMillis();

        log.info("BCAST[sid={}] start: mode=API_SESSION snapshotState=READY powState=BROADCAST txid={}",
                sid, txId);

        synchronized (lock) {
            cancelPendingShutdown();
        }

        // Ensure PeerGroup is running
        if (!startIfNeeded(timeoutMs / 2)) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("BCAST[sid={}] broadcast PENDING reason=no_peers txid={} ms={}", sid, txId, duration);
            scheduleIdleShutdown();
            return BroadcastResult.pending(txId, "no_peers", duration);
        }

        try {
            // Broadcast the transaction
            TransactionBroadcast broadcast = peerGroup.broadcastTransaction(tx);

            log.info("BCAST[sid={}] broadcasting txid={} to peers...", sid, txId);

            // Wait for broadcast completion with timeout
            Transaction result = broadcast.future().get(
                    Math.max(timeoutMs / 2, 3000), TimeUnit.MILLISECONDS);

            long duration = System.currentTimeMillis() - startTime;
            int peers = getRealPeerCount();

            log.info("BCAST[sid={}] broadcast OK txid={} peers={} ms={}", sid, txId, peers, duration);
            scheduleIdleShutdown();
            return BroadcastResult.broadcasted(txId, duration, peers);

        } catch (TimeoutException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("BCAST[sid={}] broadcast PENDING reason=timeout txid={} ms={}", sid, txId, duration);
            scheduleIdleShutdown();
            return BroadcastResult.pending(txId, "timeout", duration);

        } catch (InterruptedException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("BCAST[sid={}] broadcast PENDING reason=interrupted txid={} ms={}", sid, txId, duration);
            Thread.currentThread().interrupt();
            scheduleIdleShutdown();
            return BroadcastResult.pending(txId, "interrupted", duration);

        } catch (ExecutionException e) {
            long duration = System.currentTimeMillis() - startTime;
            Throwable cause = e.getCause();
            String reason = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    : e.getMessage();

            // Check if this is an explicit rejection
            if (cause instanceof org.bitcoinj.core.RejectedTransactionException) {
                log.error("BCAST[sid={}] broadcast REJECTED reason={} txid={} ms={}", sid, reason, txId, duration);
                scheduleIdleShutdown();
                return BroadcastResult.rejected(txId, reason, duration);
            }

            // Treat other execution exceptions as pending (IO errors, etc.)
            log.warn("BCAST[sid={}] broadcast PENDING reason={} txid={} ms={}", sid, reason, txId, duration);
            scheduleIdleShutdown();
            return BroadcastResult.pending(txId, reason, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("BCAST[sid={}] broadcast PENDING reason={} txid={} ms={}", sid, reason, txId, duration, e);
            scheduleIdleShutdown();
            return BroadcastResult.pending(txId, reason, duration);
        }
    }

    /**
     * Broadcasts with default timeout.
     */
    public BroadcastResult broadcastTransaction(Transaction tx) {
        return broadcastTransaction(tx, DEFAULT_BROADCAST_TIMEOUT_MS);
    }

    /**
     * Schedules shutdown after idle period to save battery.
     */
    private void scheduleIdleShutdown() {
        synchronized (lock) {
            cancelPendingShutdown();
            shutdownFuture = scheduler.schedule(() -> {
                log.info("BCAST[sid={}] peerGroup idle shutdown triggered", sid);
                shutdown();
            }, IDLE_SHUTDOWN_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelPendingShutdown() {
        if (shutdownFuture != null && !shutdownFuture.isDone()) {
            shutdownFuture.cancel(false);
            shutdownFuture = null;
        }
    }

    /**
     * Immediately shuts down the PeerGroup. Safe and idempotent.
     */
    public void shutdown() {
        synchronized (lock) {
            cancelPendingShutdown();
            if (peerGroup != null && running.get()) {
                log.info("BCAST[sid={}] peerGroup stop: shutting down", sid);
                try {
                    peerGroup.stop();
                } catch (Exception e) {
                    log.warn("BCAST[sid={}] peerGroup stop exception: {}: {}",
                            sid, e.getClass().getSimpleName(), e.getMessage());
                }
            }
            cleanup();
            log.info("BCAST[sid={}] peerGroup stopped", sid);
        }
    }

    private void cleanup() {
        running.set(false);
        peerGroup = null;
        chain = null;
        if (memoryStore != null) {
            try {
                memoryStore.close();
            } catch (Exception e) {
                // Ignore close errors on memory store
            }
            memoryStore = null;
        }
    }

    /**
     * Schedules shutdown soon (used for graceful cleanup).
     */
    public void shutdownSoon() {
        scheduleIdleShutdown();
    }

    /**
     * Force shutdown and release all resources.
     */
    public void destroy() {
        shutdown();
        scheduler.shutdownNow();
        log.info("BCAST[sid={}] BroadcastOnlyPeerManager destroyed", sid);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getConnectedPeerCount() {
        return getRealPeerCount();
    }

    public String getSessionId() {
        return sid;
    }
}
