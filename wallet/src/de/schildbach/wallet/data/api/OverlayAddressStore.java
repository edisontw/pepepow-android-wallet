package de.schildbach.wallet.data.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Persistent store for overlay addresses (change addresses from outgoing tx).
 * These addresses must survive app restart to ensure snapshot scanning includes
 * them.
 * 
 * Backed by SharedPreferences with JSON array storage.
 * Thread-safe.
 */
public class OverlayAddressStore {
    private static final Logger log = LoggerFactory.getLogger(OverlayAddressStore.class);

    private static final String PREFS_NAME = "overlay_address_store";
    private static final String KEY_ADDRESSES = "addresses";

    private OverlayAddressStore() {
        // Static utility class
    }

    /**
     * Add an address to the persistent overlay store.
     * 
     * @param context Android context
     * @param address Base58 address string
     */
    public static synchronized void addAddress(Context context, String address) {
        if (context == null || address == null || address.isEmpty()) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> current = loadAddressSet(prefs);

            if (current.add(address)) {
                saveAddressSet(prefs, current);
                log.info("OVERLAY_ADDR_STORE add={} total={}", address, current.size());
            }
        } catch (Exception e) {
            log.warn("OVERLAY_ADDR_STORE addAddress failed: {}", e.getMessage());
        }
    }

    /**
     * Get all persisted overlay addresses.
     * 
     * @param context Android context
     * @return Set of Base58 address strings (never null)
     */
    public static synchronized Set<String> getAllAddresses(Context context) {
        if (context == null) {
            return new HashSet<>();
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> result = loadAddressSet(prefs);
            log.info("OVERLAY_ADDR_STORE load count={}", result.size());
            return result;
        } catch (Exception e) {
            log.warn("OVERLAY_ADDR_STORE getAllAddresses failed: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * Clear all persisted overlay addresses.
     * Use with caution - typically only for wallet reset/import.
     */
    public static synchronized void clear(Context context) {
        if (context == null) {
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_ADDRESSES).apply();
            log.info("OVERLAY_ADDR_STORE cleared");
        } catch (Exception e) {
            log.warn("OVERLAY_ADDR_STORE clear failed: {}", e.getMessage());
        }
    }

    private static Set<String> loadAddressSet(SharedPreferences prefs) {
        Set<String> result = new HashSet<>();
        String json = prefs.getString(KEY_ADDRESSES, "[]");

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String addr = arr.optString(i);
                if (addr != null && !addr.isEmpty()) {
                    result.add(addr);
                }
            }
        } catch (JSONException e) {
            log.warn("OVERLAY_ADDR_STORE parse failed: {}", e.getMessage());
        }

        return result;
    }

    private static void saveAddressSet(SharedPreferences prefs, Set<String> addresses) {
        JSONArray arr = new JSONArray();
        for (String addr : addresses) {
            arr.put(addr);
        }
        prefs.edit().putString(KEY_ADDRESSES, arr.toString()).apply();
    }
}
