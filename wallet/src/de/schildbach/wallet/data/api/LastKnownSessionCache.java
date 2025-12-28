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

    private static final String KEY_HISTORY = "cached_history";

    public static class CachedBalance {
        public final Coin available;
        public final Coin spendable;
        public final Coin pending;
        public final int txCount;
        public final long timestamp;
        public final java.util.List<ApiSessionWallet.SessionTxItem> history;

        public CachedBalance(Coin available, Coin spendable, Coin pending, int txCount, long timestamp,
                java.util.List<ApiSessionWallet.SessionTxItem> history) {
            this.available = available;
            this.spendable = spendable;
            this.pending = pending;
            this.txCount = txCount;
            this.timestamp = timestamp;
            this.history = history != null ? history : new java.util.ArrayList<>();
        }

        public boolean isValid() {
            return available != null && spendable != null;
        }
    }

    public static synchronized void save(Context context, Coin available, Coin spendable, Coin pending, int txCount,
            java.util.List<ApiSessionWallet.SessionTxItem> history) {
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

            if (history != null && !history.isEmpty()) {
                editor.putString(KEY_HISTORY, serializeHistory(history));
            } else {
                editor.remove(KEY_HISTORY);
            }

            editor.apply();
            log.info("LAST_KNOWN_CACHE save available={} spendable={} pending={} txCount={} history={}",
                    available != null ? available.toPlainString() : "null",
                    spendable != null ? spendable.toPlainString() : "null",
                    pending != null ? pending.toPlainString() : "null",
                    txCount, history != null ? history.size() : 0);
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
            String historyJson = prefs.getString(KEY_HISTORY, null);

            Coin available = Coin.parseCoin(availStr);
            Coin spendable = Coin.parseCoin(spendStr);
            Coin pending = Coin.parseCoin(pendingStr);
            java.util.List<ApiSessionWallet.SessionTxItem> history = deserializeHistory(historyJson);

            log.info("LAST_KNOWN_CACHE load available={} spendable={} pending={} txCount={} history={}",
                    availStr, spendStr, pendingStr, txCount, history.size());
            return new CachedBalance(available, spendable, pending, txCount, updatedMs, history);
        } catch (Exception e) {
            log.error("LAST_KNOWN_CACHE load failed", e);
            return null;
        }
    }

    private static String serializeHistory(java.util.List<ApiSessionWallet.SessionTxItem> history) {
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            for (ApiSessionWallet.SessionTxItem item : history) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("txId", item.txId);
                obj.put("timeMs", item.timeMs);
                obj.put("valueDelta", item.valueDelta.toPlainString());
                obj.put("confirmations", item.confirmations);
                obj.put("direction", item.direction.name());
                obj.put("pending", item.pending);
                obj.put("isSelfSend", item.isSelfSend);
                array.put(obj);
            }
            return array.toString();
        } catch (org.json.JSONException e) {
            log.error("serializeHistory failed", e);
            return null;
        }
    }

    private static java.util.List<ApiSessionWallet.SessionTxItem> deserializeHistory(String json) {
        java.util.List<ApiSessionWallet.SessionTxItem> list = new java.util.ArrayList<>();
        if (json == null || json.isEmpty()) {
            return list;
        }
        try {
            org.json.JSONArray array = new org.json.JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.getJSONObject(i);
                String txId = obj.getString("txId");
                long timeMs = obj.getLong("timeMs");
                Coin valueDelta = Coin.parseCoin(obj.getString("valueDelta"));
                int confirmations = obj.getInt("confirmations");
                ApiSessionWallet.TxDirection direction = ApiSessionWallet.TxDirection
                        .valueOf(obj.getString("direction"));
                boolean pending = obj.getBoolean("pending");
                boolean isSelfSend = obj.getBoolean("isSelfSend");

                list.add(new ApiSessionWallet.SessionTxItem(txId, timeMs, valueDelta, confirmations, direction, pending,
                        isSelfSend));
            }
        } catch (Exception e) {
            log.error("deserializeHistory failed", e);
        }
        return list;
    }
}
