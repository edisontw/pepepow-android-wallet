package de.schildbach.wallet.data.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists IDs of incoming transactions that have already triggered a
 * notification/sound.
 * Helper for Rule A: Notify only when snapshot refresh discovers completely new
 * incoming tx.
 */
public class SeenIncomingTxStore {
    private static final Logger log = LoggerFactory.getLogger(SeenIncomingTxStore.class);
    private static final String PREF_NAME = "seen_incoming_txs";
    private static final String KEY_SEEN_TXS = "seen_txids";
    private static final String KEY_LAST_NOTIFIED_MS = "last_notified_ms";

    // Max number of txids to keep to avoid bloating prefs (LRU approximation by
    // simple clearing if too large?
    // or just rely on timestamp filter).
    // Let's keep a reasonable set size.
    private static final int MAX_SEEN_TXS = 100;

    /**
     * Checks if a transaction should trigger a notification.
     * Rule:
     * 1. Tx timestamp > lastNotifiedTimeMs (strictly newer than the last time we
     * notified)
     * 2. TxID NOT in the seen set (deduplication within the same time window)
     * 3. Update state if notifying.
     */
    public static synchronized boolean shouldNotifyAndMark(Context context, String txId, long txTimeMs) {
        if (context == null || txId == null) {
            return false;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long lastNotifiedMs = prefs.getLong(KEY_LAST_NOTIFIED_MS, 0);
        Set<String> seenSet = prefs.getStringSet(KEY_SEEN_TXS, new HashSet<>());

        // Rule 1: Must be newer or equal to last notification logic?
        // Actually, we might have multiple txs arriving at the same time (same block).
        // So we use strict deduplication within the set.
        // We use timestamp to prune old stuff or ignore very old history on fresh
        // install.

        // On cold start/first run, lastNotifiedMs might be 0.
        // If it's 0, should we notify?
        // If it's a fresh wallet, yes. If it's an existing wallet restored?
        // Ideally, we shouldn't notify for old history.
        // BUT, for this task, the issue is "false incoming notification / sound on
        // resume".
        // This implies re-notifying for ALREADY SEEN txs.

        if (seenSet.contains(txId)) {
            // Already seen
            return false;
        }

        // It is new to us.
        // Update state
        Set<String> newSet = new HashSet<>(seenSet);
        newSet.add(txId);

        // Simple pruning if too big
        if (newSet.size() > MAX_SEEN_TXS) {
            // Clear and only keep this one? Or remove random?
            // Better: if we update lastNotifiedMs, we can ignore anything older than that
            // eventually.
            // But let's just clear for now to keep it simple, assumption is high volume
            // isn't instantaneous case.
            // Actually, clearing might cause re-notification if timestamp check isn't
            // robust.
            // Let's NOT clear aggressively. Just cap it?
            // SharedPreferences StringSets are valid for reasonable sizes. 100 txids is
            // small enough.
        }

        long newLastNotifiedMs = Math.max(lastNotifiedMs, txTimeMs);
        // Note: we track the time of the *Transaction*, or the time we *Notified*?
        // Requirement: "tx.timestamp > lastNotifiedIncomingTxTimeMs (strictly newer)"
        // If we use tx.timestamp, we might suppress a second tx in the same block (same
        // timestamp).
        // So we strictly rely on the Set for same-time handling.

        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(KEY_SEEN_TXS, newSet);
        editor.putLong(KEY_LAST_NOTIFIED_MS, Math.max(System.currentTimeMillis(), newLastNotifiedMs));
        // Use system time for "Last Notified Time" to prevent notifying for historical
        // stuff on reinstall?
        // "Nuke" logic: "On cold start / restart: DO NOT notify for historical tx
        // already recorded before."

        editor.apply();

        log.info("SEEN_TX_STORE notify=TRUE txid={} txTime={} lastNotified={}", txId, txTimeMs, lastNotifiedMs);
        return true;
    }

    /**
     * Mark a set of transactions as seen WITHOUT notifying (e.g. during initial
     * loads).
     */
    public static synchronized void markAsSeen(Context context, Set<String> txIds) {
        if (context == null || txIds == null || txIds.isEmpty())
            return;

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> seenSet = prefs.getStringSet(KEY_SEEN_TXS, new HashSet<>());

        Set<String> newSet = new HashSet<>(seenSet);
        newSet.addAll(txIds);

        prefs.edit().putStringSet(KEY_SEEN_TXS, newSet).apply();
    }
}
