package de.schildbach.wallet.data;

import javax.annotation.Nullable;

/**
 * Result of a P2P broadcast attempt for Session Wallet transactions.
 * Used by BroadcastOnlyPeerManager to communicate outcomes to
 * SessionSendManager.
 */
public final class BroadcastResult {

    public enum Status {
        /** At least one peer accepted/relayed the transaction. */
        BROADCASTED,
        /** Network failure, timeout, or no peers available. Will retry later. */
        BROADCAST_PENDING,
        /** Peer explicitly rejected the transaction. */
        REJECTED
    }

    private final Status status;
    private final String txId;
    @Nullable
    private final String reason;
    private final long durationMs;
    private final int peerCount;

    private BroadcastResult(Status status, String txId, @Nullable String reason, long durationMs, int peerCount) {
        this.status = status;
        this.txId = txId;
        this.reason = reason;
        this.durationMs = durationMs;
        this.peerCount = peerCount;
    }

    public static BroadcastResult broadcasted(String txId, long durationMs, int peerCount) {
        return new BroadcastResult(Status.BROADCASTED, txId, null, durationMs, peerCount);
    }

    public static BroadcastResult pending(String txId, String reason, long durationMs) {
        return new BroadcastResult(Status.BROADCAST_PENDING, txId, reason, durationMs, 0);
    }

    public static BroadcastResult rejected(String txId, String reason, long durationMs) {
        return new BroadcastResult(Status.REJECTED, txId, reason, durationMs, 0);
    }

    public Status getStatus() {
        return status;
    }

    public String getTxId() {
        return txId;
    }

    @Nullable
    public String getReason() {
        return reason;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getPeerCount() {
        return peerCount;
    }

    public boolean isSuccess() {
        return status == Status.BROADCASTED;
    }

    public boolean isPending() {
        return status == Status.BROADCAST_PENDING;
    }

    public boolean isRejected() {
        return status == Status.REJECTED;
    }

    @Override
    public String toString() {
        return "BroadcastResult{" +
                "status=" + status +
                ", txId='" + txId + '\'' +
                ", reason='" + reason + '\'' +
                ", durationMs=" + durationMs +
                ", peerCount=" + peerCount +
                '}';
    }
}
