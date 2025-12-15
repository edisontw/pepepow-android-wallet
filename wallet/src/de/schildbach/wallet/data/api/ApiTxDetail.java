package de.schildbach.wallet.data.api;

import javax.annotation.Nullable;

/**
 * Detailed transaction information (raw hex + placement in chain).
 */
public class ApiTxDetail {
    public final String txId;
    public final String rawHex;
    public final int blockHeight;
    public final long blockTimeSeconds;
    @Nullable
    public final String blockHash;

    public ApiTxDetail(String txId, String rawHex, int blockHeight, long blockTimeSeconds,
            @Nullable String blockHash) {
        this.txId = txId;
        this.rawHex = rawHex;
        this.blockHeight = blockHeight;
        this.blockTimeSeconds = blockTimeSeconds;
        this.blockHash = blockHash;
    }
}
