package de.schildbach.wallet.data.api;

import javax.annotation.Nullable;
import org.json.JSONObject;

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
    @Nullable
    public final JSONObject sourceJson;

    public ApiTxDetail(String txId, String rawHex, int blockHeight, long blockTimeSeconds,
            @Nullable String blockHash) {
        this(txId, rawHex, blockHeight, blockTimeSeconds, blockHash, null);
    }

    public ApiTxDetail(String txId, String rawHex, int blockHeight, long blockTimeSeconds,
            @Nullable String blockHash, @Nullable JSONObject sourceJson) {
        this.txId = txId;
        this.rawHex = rawHex;
        this.blockHeight = blockHeight;
        this.blockTimeSeconds = blockTimeSeconds;
        this.blockHash = blockHash;
        this.sourceJson = sourceJson;
    }
}
