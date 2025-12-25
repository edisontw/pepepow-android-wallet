package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;

/**
 * Minimal DTO for UTXOs retrieved from the API for the Session Wallet.
 */
public class SessionUtxo {
    public final Sha256Hash txId;
    public final int index;
    public final Coin value;
    public final Address address;
    public final String scriptPubKey;
    public final long confirmations;
    public final int height;

    public SessionUtxo(Sha256Hash txId, int index, Coin value, Address address, String scriptPubKey, long confirmations,
            int height) {
        this.txId = txId;
        this.index = index;
        this.value = value;
        this.address = address;
        this.scriptPubKey = scriptPubKey;
        this.confirmations = confirmations;
        this.height = height;
    }

    public String getKey() {
        return txId.toString() + ":" + index;
    }
}
