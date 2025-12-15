package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Coin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated address data returned by the explorer.
 */
public class ApiAddressInfo {
    public String address;
    public Coin balance = Coin.ZERO;
    public int txCount = 0;
    private final List<ApiTxRef> transactions = new ArrayList<>();

    public ApiAddressInfo(String address) {
        this.address = address;
    }

    public List<ApiTxRef> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }

    public void addOrMerge(ApiTxRef ref) {
        if (ref == null || ref.txId == null) {
            return;
        }
        for (int i = 0; i < transactions.size(); i++) {
            ApiTxRef existing = transactions.get(i);
            if (ref.txId.equals(existing.txId)) {
                transactions.set(i, existing.merge(ref));
                return;
            }
        }
        transactions.add(ref);
    }
}
