package de.schildbach.wallet.data;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.NonNull;
import javax.annotation.Nullable;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.api.ApiSessionWallet;
import de.schildbach.wallet.data.api.OutgoingTxJournal;
import de.schildbach.wallet.data.api.SessionUtxo;

/**
 * Handles sending coins when operating in API_SESSION (Route B) mode.
 * Uses ApiSessionWallet for UTXO selection and transaction building,
 * but uses the canonical Wallet only for key retrieval (signing).
 * 
 * Route A: Broadcasts via P2P using BroadcastOnlyPeerManager (RAM-only, no
 * SPV).
 * 
 * Broadcast State Model:
 * - CREATED_SIGNED: Transaction built and signed, ready to broadcast
 * - BROADCASTING: Broadcast in progress
 * - BROADCASTED: At least one peer accepted/relayed
 * - BROADCAST_PENDING: Timeout/no peers/IO failure; will retry later
 * - REJECTED: Peer explicitly rejected; capture reason
 */
public class SessionSendManager {
    private static final Logger log = LoggerFactory.getLogger(SessionSendManager.class);

    /**
     * Broadcast state for outgoing Session transactions.
     */
    public enum BroadcastState {
        CREATED_SIGNED,
        BROADCASTING,
        BROADCASTED,
        BROADCAST_PENDING,
        REJECTED
    }

    /**
     * Pending transaction info for retry.
     */
    public static class PendingTx {
        public final Transaction tx;
        public final String txId;
        public final long createdAtMs;
        public BroadcastState state;
        @Nullable
        public String reason;
        public int retryCount;

        public PendingTx(Transaction tx) {
            this.tx = tx;
            this.txId = tx.getTxId().toString();
            this.createdAtMs = System.currentTimeMillis();
            this.state = BroadcastState.CREATED_SIGNED;
            this.retryCount = 0;
        }
    }

    private final ApiSessionWallet sessionWallet;
    private final Wallet canonicalWallet;
    private final BroadcastOnlyPeerManager broadcastManager;
    private final Handler callbackHandler;
    private final String sessionId;

    // Application context for persistence operations
    @Nullable
    private android.content.Context appContext;

    // Track pending transactions for retry
    private final Map<String, PendingTx> pendingTxMap = new ConcurrentHashMap<>();

    // Keep the old interface for backward compatibility, but mark as deprecated
    @Deprecated
    public interface TransactionBroadcaster {
        java.util.concurrent.Future<Transaction> broadcastTransaction(Transaction tx);
    }

    /**
     * Creates a SessionSendManager with P2P broadcast-only capability.
     *
     * @param sessionWallet    The session wallet for UTXO selection and signing
     * @param canonicalWallet  The canonical wallet for key retrieval only
     * @param broadcastManager The P2P broadcast manager (RAM-only, no SPV)
     * @param sessionId        The FASTBOOT_SESSION_ID for logging
     */
    public SessionSendManager(ApiSessionWallet sessionWallet, Wallet canonicalWallet,
            BroadcastOnlyPeerManager broadcastManager, String sessionId) {
        this.sessionWallet = sessionWallet;
        this.canonicalWallet = canonicalWallet;
        this.broadcastManager = broadcastManager;
        this.sessionId = sessionId;
        this.callbackHandler = new Handler(Looper.getMainLooper());
        log.info("SESSION-SEND[sid={}] SessionSendManager created with P2P broadcast-only", sessionId);
    }

    /**
     * Set application context for persistence operations.
     */
    public void setContext(android.content.Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Legacy constructor for backward compatibility.
     * 
     * @deprecated Use the constructor with BroadcastOnlyPeerManager instead
     */
    @Deprecated
    public SessionSendManager(ApiSessionWallet sessionWallet, Wallet canonicalWallet,
            TransactionBroadcaster broadcaster) {
        this.sessionWallet = sessionWallet;
        this.canonicalWallet = canonicalWallet;
        this.broadcastManager = null; // Will fail on broadcast
        this.sessionId = "FASTBOOT";
        this.callbackHandler = new Handler(Looper.getMainLooper());
        log.warn("SESSION-SEND[sid={}] SessionSendManager created with DEPRECATED broadcaster interface", sessionId);
    }

    public interface SendCallback {
        void onSuccess(Transaction transaction);

        void onFailure(Exception exception);

        void onInsufficientMoney(Coin missing);

        /**
         * Called when broadcast is pending (network failure, timeout).
         * Transaction is queued for retry.
         */
        default void onBroadcastPending(Transaction transaction, String reason) {
            // Default: treat as success (tx is signed and will be retried)
            onSuccess(transaction);
        }
    }

    /**
     * Extended callback with user-visible result messages.
     */
    public interface SendCallbackWithMessage extends SendCallback {
        /**
         * Returns user-visible result message.
         */
        void onResult(Transaction transaction, String userMessage);
    }

    public void sendCoins(final SendRequest sendRequest, final SendCallback callback) {
        // Run on background thread
        new Thread(() -> {
            Context.propagate(Constants.CONTEXT);
            try {
                log.info("SESSION-SEND[sid={}] START amount={}", sessionId,
                        sendRequest.tx.getOutput(0).getValue());

                // 1. Build and Sign using SessionWallet
                if (sendRequest.tx.getOutputs().isEmpty()) {
                    throw new IllegalArgumentException("No outputs in send request");
                }

                Coin amount = sendRequest.tx.getOutput(0).getValue();
                Address destination = sendRequest.tx.getOutput(0).getScriptPubKey()
                        .getToAddress(Constants.NETWORK_PARAMETERS);

                long buildStart = System.currentTimeMillis();
                final Transaction tx = sessionWallet.createSignedTransaction(canonicalWallet, destination, amount,
                        sendRequest.aesKey);
                long buildEnd = System.currentTimeMillis();

                log.info("SESSION-SEND[sid={}] SIGNED txid={} size={} inputs={} outputs={} time={}ms",
                        sessionId, tx.getTxId(), tx.unsafeBitcoinSerialize().length, tx.getInputs().size(),
                        tx.getOutputs().size(), (buildEnd - buildStart));

                // Create pending tx entry
                PendingTx pendingTx = new PendingTx(tx);
                pendingTx.state = BroadcastState.BROADCASTING;
                pendingTxMap.put(pendingTx.txId, pendingTx);

                // 2. Broadcast via P2P BroadcastOnlyPeerManager
                BroadcastResult result = doBroadcast(tx, pendingTx);

                // 3. Handle result
                switch (result.getStatus()) {
                    case BROADCASTED:
                        // Success - commit to session wallet
                        pendingTx.state = BroadcastState.BROADCASTED;
                        ApiSessionWallet.CommitResult commitResult = sessionWallet.commitTransaction(tx);
                        recordToJournal(tx, destination, amount, commitResult.localChangeOutpoints);
                        pendingTxMap.remove(pendingTx.txId); // Remove from pending
                        log.info("SESSION-SEND[sid={}] broadcast_state=BROADCASTED lockedInputsAdded={} localChangeAdded={}",
                                sessionId, commitResult.lockedInputsAdded, commitResult.localChangeOutpoints.size());

                        final String successMsg = "Sent (broadcasted)";
                        log.info("SESSION-SEND[sid={}] SUCCESS txid={} message=\"{}\"",
                                sessionId, tx.getTxId(), successMsg);

                        callbackHandler.post(() -> {
                            if (callback instanceof SendCallbackWithMessage) {
                                ((SendCallbackWithMessage) callback).onResult(tx, successMsg);
                            }
                            callback.onSuccess(tx);
                        });
                        break;

                    case BROADCAST_PENDING:
                        // Network failure - keep in pending, inputs stay locked
                        pendingTx.state = BroadcastState.BROADCAST_PENDING;
                        pendingTx.reason = result.getReason();
                        // Commit to session wallet to lock inputs (prevents double-spend attempts)
                        ApiSessionWallet.CommitResult pendingCommit = sessionWallet.commitTransaction(tx);
                        recordToJournal(tx, destination, amount, pendingCommit.localChangeOutpoints);
                        // Keep in pendingTxMap for retry
                        log.info("SESSION-SEND[sid={}] broadcast_state=BROADCAST_PENDING lockedInputsAdded={} localChangeAdded={}",
                                sessionId, pendingCommit.lockedInputsAdded, pendingCommit.localChangeOutpoints.size());

                        final String pendingMsg = "Queued (will retry when online)";
                        log.info("SESSION-SEND[sid={}] PENDING txid={} reason=\"{}\" message=\"{}\"",
                                sessionId, tx.getTxId(), result.getReason(), pendingMsg);

                        callbackHandler.post(() -> {
                            if (callback instanceof SendCallbackWithMessage) {
                                ((SendCallbackWithMessage) callback).onResult(tx, pendingMsg);
                            }
                            callback.onBroadcastPending(tx, result.getReason());
                        });
                        break;

                    case REJECTED:
                        // Peer rejected - this is a hard failure
                        pendingTx.state = BroadcastState.REJECTED;
                        pendingTx.reason = result.getReason();
                        pendingTxMap.remove(pendingTx.txId);
                        // Don't commit - inputs should be unlocked for retry with different tx
                        sessionWallet.rollbackTransaction(tx);
                        if (appContext != null) {
                            OutgoingTxJournal.remove(appContext, tx.getTxId().toString());
                        }
                        log.info("SESSION-SEND[sid={}] broadcast_state=REJECTED txid={} reason={}",
                                sessionId, tx.getTxId(), result.getReason());

                        final String rejectMsg = "Rejected: " + result.getReason();
                        log.error("SESSION-SEND[sid={}] REJECTED txid={} reason=\"{}\" message=\"{}\"",
                                sessionId, tx.getTxId(), result.getReason(), rejectMsg);

                        callbackHandler.post(() -> {
                            if (callback instanceof SendCallbackWithMessage) {
                                ((SendCallbackWithMessage) callback).onResult(tx, rejectMsg);
                            }
                            callback.onFailure(new RuntimeException(rejectMsg));
                        });
                        break;
                }

            } catch (final InsufficientMoneyException e) {
                log.warn("SESSION-SEND[sid={}] INSUFFICIENT MONEY: {}", sessionId, e.missing);
                callbackHandler.post(() -> callback.onInsufficientMoney(e.missing));
            } catch (final Exception e) {
                log.error("SESSION-SEND[sid={}] FAILURE: {} \nStack: ", sessionId, e.getMessage(), e);
                callbackHandler.post(() -> callback.onFailure(e));
            }
        }).start();
    }

    /**
     * Performs the actual broadcast using BroadcastOnlyPeerManager.
     */
    private BroadcastResult doBroadcast(Transaction tx, PendingTx pendingTx) {
        if (broadcastManager == null) {
            log.error("SESSION-SEND[sid={}] broadcastManager is null, cannot broadcast", sessionId);
            return BroadcastResult.pending(tx.getTxId().toString(), "no_broadcaster", 0);
        }

        log.info("SESSION-SEND[sid={}] BROADCASTING via P2P txid={}", sessionId, tx.getTxId());
        return broadcastManager.broadcastTransaction(tx);
    }

    /**
     * Record outgoing transaction to persistent journal for restart survival.
     * Extracts spent inputs and stores metadata for history reconstruction.
     */
    private void recordToJournal(Transaction tx, Address destination, Coin amount,
            @Nullable List<OutgoingTxJournal.ChangeOutpoint> changeOutpoints) {
        if (appContext == null) {
            log.warn("SESSION-SEND[sid={}] cannot record journal: no appContext", sessionId);
            return;
        }

        try {
            List<OutgoingTxJournal.SpentOutpoint> spentOutpoints = new ArrayList<>();

            // Collect spent outpoints from transaction inputs
            for (TransactionInput input : tx.getInputs()) {
                String prevTxid = input.getOutpoint().getHash().toString();
                int vout = (int) input.getOutpoint().getIndex();

                // Try to get value from session wallet's UTXO set
                long valueSat = 0;
                String outpointKey = prevTxid + ":" + vout;
                List<SessionUtxo> utxos = sessionWallet.getUtxos();
                for (SessionUtxo utxo : utxos) {
                    if (utxo.getKey().equals(outpointKey)) {
                        valueSat = utxo.value.value;
                        break;
                    }
                }

                spentOutpoints.add(new OutgoingTxJournal.SpentOutpoint(prevTxid, vout, valueSat));
            }

            OutgoingTxJournal.JournalEntry entry = new OutgoingTxJournal.JournalEntry(
                    tx.getTxId().toString(),
                    System.currentTimeMillis(),
                    spentOutpoints,
                    changeOutpoints,
                    destination.toString(),
                    amount.value);

            OutgoingTxJournal.record(appContext, entry);
            int changeCount = changeOutpoints != null ? changeOutpoints.size() : 0;
            log.info("SESSION-SEND[sid={}] journal recorded txid={} spentInputs={} localChangeAdded={}",
                    sessionId, tx.getTxId(), spentOutpoints.size(), changeCount);
        } catch (Exception e) {
            // Journal failure is non-fatal - tx still broadcasts
            log.warn("SESSION-SEND[sid={}] journal record failed ex={} msg={}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Retries broadcasting pending transactions.
     * Call this on app resume or when network becomes available.
     */
    public void retryPendingBroadcasts() {
        if (broadcastManager == null) {
            log.warn("SESSION-SEND[sid={}] retryPendingBroadcasts: no broadcastManager", sessionId);
            return;
        }

        List<PendingTx> toRetry = new ArrayList<>();
        for (PendingTx pending : pendingTxMap.values()) {
            if (pending.state == BroadcastState.BROADCAST_PENDING) {
                toRetry.add(pending);
            }
        }

        if (toRetry.isEmpty()) {
            log.debug("SESSION-SEND[sid={}] retryPendingBroadcasts: no pending txs", sessionId);
            return;
        }

        log.info("SESSION-SEND[sid={}] retryPendingBroadcasts: {} pending txs", sessionId, toRetry.size());

        // Run retries on background thread
        new Thread(() -> {
            Context.propagate(Constants.CONTEXT);
            for (PendingTx pending : toRetry) {
                pending.retryCount++;
                pending.state = BroadcastState.BROADCASTING;

                log.info("SESSION-SEND[sid={}] RETRY #{} txid={}",
                        sessionId, pending.retryCount, pending.txId);

                BroadcastResult result = broadcastManager.broadcastTransaction(pending.tx);

                switch (result.getStatus()) {
                    case BROADCASTED:
                        pending.state = BroadcastState.BROADCASTED;
                        pendingTxMap.remove(pending.txId);
                        log.info("SESSION-SEND[sid={}] RETRY SUCCESS txid={} broadcast_state=BROADCASTED",
                                sessionId, pending.txId);
                        break;

                    case BROADCAST_PENDING:
                        pending.state = BroadcastState.BROADCAST_PENDING;
                        pending.reason = result.getReason();
                        log.warn("SESSION-SEND[sid={}] RETRY STILL PENDING txid={} broadcast_state=BROADCAST_PENDING reason={}",
                                sessionId, pending.txId, result.getReason());
                        break;

                    case REJECTED:
                        pending.state = BroadcastState.REJECTED;
                        pending.reason = result.getReason();
                        pendingTxMap.remove(pending.txId);
                        sessionWallet.rollbackTransaction(pending.tx);
                        if (appContext != null) {
                            OutgoingTxJournal.remove(appContext, pending.txId);
                        }
                        log.error("SESSION-SEND[sid={}] RETRY REJECTED txid={} broadcast_state=REJECTED reason={}",
                                sessionId, pending.txId, result.getReason());
                        break;
                }
            }
        }).start();
    }

    /**
     * Gets the list of pending transactions.
     */
    public List<PendingTx> getPendingTransactions() {
        return new ArrayList<>(pendingTxMap.values());
    }

    /**
     * Gets count of pending transactions.
     */
    public int getPendingCount() {
        return pendingTxMap.size();
    }

    /**
     * Checks if there are any transactions pending broadcast.
     */
    public boolean hasPendingBroadcasts() {
        for (PendingTx pending : pendingTxMap.values()) {
            if (pending.state == BroadcastState.BROADCAST_PENDING) {
                return true;
            }
        }
        return false;
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * Called by BroadcastOnlyPeerManager when peers become available.
     * Implements BroadcastOnlyPeerManager.PeerConnectListener interface.
     * Triggers retry of pending broadcasts.
     */
    public void onPeersAvailable() {
        if (hasPendingBroadcasts()) {
            log.info("SESSION-SEND[sid={}] peers available, retrying {} pending broadcasts",
                    sessionId, getPendingCount());
            retryPendingBroadcasts();
        }
    }
}
