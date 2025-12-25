package de.schildbach.wallet.data.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.core.Coin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists last known session wallet state for fast UI rendering on startup.
 * Helper for Task C: Available balance show fast.
 */
public class LastKnownSessionCache {
    private static final Logger log = LoggerFactory.getLogger(LastKnownSessionCache.class);
    private static final String PREF_NAME = "last_known_session_cache";
    private static final String KEY_AVAILABLE = "cached_available";
    private static final String KEY_SPENDABLE = "cached_spendable";
    private static final String KEY_TX_COUNT = "cached_tx_count";
    private static final String KEY_UPDATED_MS = "cached_updated_ms";

    public static class CachedBalance {
        public final Coin available;
        public final Coin spendable;
        public final Coin pending;
        public final int txCount;
        public final long timestamp;

        public CachedBalance(Coin available, Coin spendable, Coin pending, int txCount, long timestamp) {
            this.available = available;
            this.spendable = spendable;
            this.pending = pending;
            this.txCount = txCount;
            this.timestamp = timestamp;
        }

        public boolean isValid() {
            return available != null && spendable != null;
        }
    }

    public static synchronized void save(Context context, Coin available, Coin spendable, Coin pending, int txCount) {
        if (context == null)
            return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_AVAILABLE, available != null ? available.toPlainString() : "0");
            editor.putString(KEY_SPENDABLE, spendable != null ? spendable.toPlainString() : "0");
            editor.putString("cached_pending", pending != null ? pending.toPlainString() : "0");
            editor.putInt(KEY_TX_COUNT, txCount);
            editor.putLong(KEY_UPDATED_MS, System.currentTimeMillis());
            editor.apply();
            log.info("LAST_KNOWN_CACHE save available={} spendable={} pending={} txCount={}",
                    available != null ? available.toPlainString() : "null",
                    spendable != null ? spendable.toPlainString() : "null",
                    pending != null ? pending.toPlainString() : "null",
                    txCount);
        } catch (Exception e) {
            log.error("LAST_KNOWN_CACHE save failed", e);
        }
    }

    public static synchronized CachedBalance load(Context context) {
        if (context == null)
            return null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (!prefs.contains(KEY_AVAILABLE)) {
                log.info("LAST_KNOWN_CACHE load: no cache exists");
                return null;
            }

            String availStr = prefs.getString(KEY_AVAILABLE, "0");
            String spendStr = prefs.getString(KEY_SPENDABLE, "0");
            String pendingStr = prefs.getString("cached_pending", "0");
            int txCount = prefs.getInt(KEY_TX_COUNT, 0);
            long updatedMs = prefs.getLong(KEY_UPDATED_MS, 0);

            Coin available = Coin.parseCoin(availStr);
            Coin spendable = Coin.parseCoin(spendStr);
            Coin pending = Coin.parseCoin(pendingStr);

            log.info("LAST_KNOWN_CACHE load available={} spendable={} pending={} txCount={}",
                    availStr, spendStr, pendingStr, txCount);
            return new CachedBalance(available, spendable, pending, txCount, updatedMs);
        } catch (Exception e) {
            log.error("LAST_KNOWN_CACHE load failed", e);
            return null;
        }
    }
}
