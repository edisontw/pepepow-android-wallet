package de.schildbach.wallet.data.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent journal for outgoing transactions.
 * Stores minimal facts that cannot be reconstructed from explorer API:
 * - Which tx we sent
 * - Which inputs we consumed (for spent tracking)
 * 
 * Survives app restart to ensure:
 * - Sent history persists
 * - Spent UTXOs are not double-counted
 * 
 * Backed by SharedPreferences with JSON storage.
 * Thread-safe.
 */
public class OutgoingTxJournal {
    private static final Logger log = LoggerFactory.getLogger(OutgoingTxJournal.class);

    private static final String PREFS_NAME = "outgoing_tx_journal";
    private static final String KEY_ENTRIES = "entries";

    /**
     * Represents a spent outpoint (input consumed by outgoing tx).
     */
    public static class SpentOutpoint {
        public final String prevTxid;
        public final int vout;
        public final long valueSat;

        public SpentOutpoint(String prevTxid, int vout, long valueSat) {
            this.prevTxid = prevTxid;
            this.vout = vout;
            this.valueSat = valueSat;
        }

        public String getKey() {
            return prevTxid + ":" + vout;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("prevTxid", prevTxid);
            obj.put("vout", vout);
            obj.put("valueSat", valueSat);
            return obj;
        }

        public static SpentOutpoint fromJson(JSONObject obj) throws JSONException {
            return new SpentOutpoint(
                    obj.getString("prevTxid"),
                    obj.getInt("vout"),
                    obj.optLong("valueSat", 0));
        }
    }

    /**
     * Journal entry for one outgoing transaction.
     */
    public static class JournalEntry {
        public final String txid;
        public final long timestampMs;
        public final List<SpentOutpoint> spentOutpoints;
        public final String toAddress; // UI display only
        public final long amountSat; // UI display only

        public JournalEntry(String txid, long timestampMs, List<SpentOutpoint> spentOutpoints,
                String toAddress, long amountSat) {
            this.txid = txid;
            this.timestampMs = timestampMs;
            this.spentOutpoints = spentOutpoints != null ? spentOutpoints : new ArrayList<>();
            this.toAddress = toAddress;
            this.amountSat = amountSat;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("txid", txid);
            obj.put("timestampMs", timestampMs);
            obj.put("toAddress", toAddress);
            obj.put("amountSat", amountSat);

            JSONArray spentArr = new JSONArray();
            for (SpentOutpoint sp : spentOutpoints) {
                spentArr.put(sp.toJson());
            }
            obj.put("spentOutpoints", spentArr);

            return obj;
        }

        public static JournalEntry fromJson(JSONObject obj) throws JSONException {
            String txid = obj.getString("txid");
            long timestampMs = obj.optLong("timestampMs", System.currentTimeMillis());
            String toAddress = obj.optString("toAddress", "");
            long amountSat = obj.optLong("amountSat", 0);

            List<SpentOutpoint> spentOutpoints = new ArrayList<>();
            JSONArray spentArr = obj.optJSONArray("spentOutpoints");
            if (spentArr != null) {
                for (int i = 0; i < spentArr.length(); i++) {
                    spentOutpoints.add(SpentOutpoint.fromJson(spentArr.getJSONObject(i)));
                }
            }

            return new JournalEntry(txid, timestampMs, spentOutpoints, toAddress, amountSat);
        }
    }

    private OutgoingTxJournal() {
        // Static utility class
    }

    /**
     * Record an outgoing transaction to the journal.
     * Call this after successful sign, before or after broadcast.
     */
    public static synchronized void record(Context context, JournalEntry entry) {
        if (context == null || entry == null || entry.txid == null) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            List<JournalEntry> current = loadEntries(prefs);

            // Check for duplicate txid
            for (JournalEntry existing : current) {
                if (existing.txid.equals(entry.txid)) {
                    log.debug("OUTGOING_TX_JOURNAL duplicate txid={}, skipping", entry.txid);
                    return;
                }
            }

            current.add(entry);
            saveEntries(prefs, current);

            log.info("OUTGOING_TX_JOURNAL record txid={} spentInputs={} toAddr={} amount={} total={}",
                    entry.txid, entry.spentOutpoints.size(), entry.toAddress,
                    entry.amountSat, current.size());
        } catch (Exception e) {
            log.warn("OUTGOING_TX_JOURNAL record failed: {}", e.getMessage());
        }
    }

    /**
     * Load all journal entries.
     * 
     * @return List of entries (never null)
     */
    public static synchronized List<JournalEntry> loadAll(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            List<JournalEntry> entries = loadEntries(prefs);
            log.info("OUTGOING_TX_JOURNAL loadAll count={}", entries.size());
            return entries;
        } catch (Exception e) {
            log.warn("OUTGOING_TX_JOURNAL loadAll failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Remove a specific entry by txid.
     * Call when tx is confirmed and no longer needs local tracking.
     */
    public static synchronized void remove(Context context, String txid) {
        if (context == null || txid == null) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            List<JournalEntry> current = loadEntries(prefs);

            int beforeSize = current.size();
            current.removeIf(e -> e.txid.equals(txid));

            if (current.size() < beforeSize) {
                saveEntries(prefs, current);
                log.info("OUTGOING_TX_JOURNAL remove txid={} remaining={}", txid, current.size());
            }
        } catch (Exception e) {
            log.warn("OUTGOING_TX_JOURNAL remove failed: {}", e.getMessage());
        }
    }

    /**
     * Clear all journal entries.
     * Use with caution - typically only for wallet reset/import.
     */
    public static synchronized void clear(Context context) {
        if (context == null) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_ENTRIES).apply();
            log.info("OUTGOING_TX_JOURNAL cleared");
        } catch (Exception e) {
            log.warn("OUTGOING_TX_JOURNAL clear failed: {}", e.getMessage());
        }
    }

    private static List<JournalEntry> loadEntries(SharedPreferences prefs) {
        List<JournalEntry> result = new ArrayList<>();
        String json = prefs.getString(KEY_ENTRIES, "[]");

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(JournalEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            log.warn("OUTGOING_TX_JOURNAL parse failed: {}", e.getMessage());
        }

        return result;
    }

    private static void saveEntries(SharedPreferences prefs, List<JournalEntry> entries) throws JSONException {
        JSONArray arr = new JSONArray();
        for (JournalEntry entry : entries) {
            arr.put(entry.toJson());
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply();
    }
}
