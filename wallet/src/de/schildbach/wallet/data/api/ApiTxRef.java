package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Coin;

import javax.annotation.Nullable;

/**
 * Lightweight reference to a transaction returned by the explorer API.
 */
public class ApiTxRef {
    public final String txId;
    public final int blockHeight;
    public final long blockTimeSeconds;
    @Nullable
    public final Coin value;
    @Nullable
    public final Boolean incoming;

    public ApiTxRef(String txId) {
        this(txId, -1, 0L, null, null);
    }

    public ApiTxRef(String txId, int blockHeight, long blockTimeSeconds, @Nullable Coin value,
            @Nullable Boolean incoming) {
        this.txId = txId;
        this.blockHeight = blockHeight;
        this.blockTimeSeconds = blockTimeSeconds;
        this.value = value;
        this.incoming = incoming;
    }

    public ApiTxRef merge(ApiTxRef other) {
        if (other == null) {
            return this;
        }
        int mergedHeight = this.blockHeight > 0 ? this.blockHeight : other.blockHeight;
        long mergedTime = this.blockTimeSeconds > 0 ? this.blockTimeSeconds : other.blockTimeSeconds;
        Coin mergedValue = this.value != null ? this.value : other.value;
        Boolean mergedIncoming = this.incoming != null ? this.incoming : other.incoming;
        return new ApiTxRef(this.txId != null ? this.txId : other.txId, mergedHeight, mergedTime, mergedValue,
                mergedIncoming);
    }
}
