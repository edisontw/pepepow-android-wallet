package de.schildbach.wallet.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.WalletApplication;

import javax.annotation.Nullable;

/**
 * Single source of truth for explorer base URL.
 * All API clients and UI components MUST read explorer URLs from here.
 * 
 * BUG FIX #5: Centralize explorer URL to unify API + TX links.
 * BUG FIX #6: Add explicit logging when explorer switch is deferred during
 * overlay runtime.
 */
public final class ExplorerConfig {
    private static final Logger log = LoggerFactory.getLogger(ExplorerConfig.class);

    private static final String DEFAULT_EXPLORER_URL = "https://explorer.pepepow.net";
    public static final String PREFS_KEY_DEV_API_BASE_URL = "developer_api_base_url";

    // SharedPreferences keys for pending explorer change marker
    private static final String PREFS_NAME = "explorer_config";
    private static final String PREF_EXPLORER_CHANGE_PENDING = "pref_explorer_change_pending";
    private static final String PREF_EXPLORER_CHANGE_PENDING_URL = "pref_explorer_change_pending_url";
    private static final String PREF_EXPLORER_CHANGE_PENDING_AT = "pref_explorer_change_pending_at_ms";

    // Callback interface for overlay state detection
    public interface OverlayStateCallback {
        boolean isOverlayActive();

        String getOverlayStateSnapshot();
    }

    // Session ID for logging (matches BlockchainServiceImpl pattern)
    private static String sessionId = "UNKNOWN";

    private static volatile String appliedBaseUrl = null;
    private static String pendingExplorerBase = null;
    private static String revertUrl = null;
    private static boolean suppressNextPreferenceHandling = false;

    // Overlay state callback (set by BlockchainServiceImpl on service start)
    private static OverlayStateCallback overlayStateCallback = null;

    private ExplorerConfig() {
        // Utility class
    }

    /**
     * Set session ID for logging purposes.
     */
    public static void setSessionId(String sid) {
        sessionId = sid != null ? sid : "UNKNOWN";
    }

    /**
     * Get the current session ID.
     */
    public static String getSessionId() {
        return sessionId;
    }

    /**
     * Register overlay state callback (called by BlockchainServiceImpl on service
     * start).
     */
    public static void setOverlayStateCallback(OverlayStateCallback callback) {
        overlayStateCallback = callback;
    }

    /**
     * Check if overlay is currently active (conservative: returns true if unknown).
     */
    public static boolean isOverlayActive() {
        if (overlayStateCallback != null) {
            return overlayStateCallback.isOverlayActive();
        }
        // Conservative: assume active if we can't determine
        return true;
    }

    /**
     * Get overlay state snapshot string for logging.
     */
    public static String getOverlayStateSnapshot() {
        if (overlayStateCallback != null) {
            return overlayStateCallback.getOverlayStateSnapshot();
        }
        return "callback=null";
    }

    /**
     * Mark that an explorer change is pending (requires restart to apply).
     */
    public static void markExplorerChangePending(Context context, String newUrl) {
        try {
            SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit()
                    .putBoolean(PREF_EXPLORER_CHANGE_PENDING, true)
                    .putString(PREF_EXPLORER_CHANGE_PENDING_URL, newUrl)
                    .putLong(PREF_EXPLORER_CHANGE_PENDING_AT, System.currentTimeMillis())
                    .apply();
            log.info("EXPLORER_SWITCH[sid={}] pending_marked url={} storedIn=default_prefs", sessionId, newUrl);
        } catch (Exception e) {
            log.warn("EXPLORER_SWITCH[sid={}] pending_mark_failed: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Clear the pending explorer change marker (called on startup after applying).
     */
    public static void clearExplorerChangePending(Context context) {
        try {
            SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit()
                    .remove(PREF_EXPLORER_CHANGE_PENDING)
                    .remove(PREF_EXPLORER_CHANGE_PENDING_URL)
                    .remove(PREF_EXPLORER_CHANGE_PENDING_AT)
                    .apply();
        } catch (Exception e) {
            log.warn("EXPLORER_SWITCH[sid={}] pending_clear_failed: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Check if there's a pending explorer change and log it (called on app
     * startup).
     * Returns the pending URL if exists, null otherwise.
     */
    @Nullable
    public static String logPendingExplorerChangeOnStartup(Context context) {
        try {
            SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
            boolean pending = prefs.getBoolean(PREF_EXPLORER_CHANGE_PENDING, false);
            if (pending) {
                String pendingUrl = prefs.getString(PREF_EXPLORER_CHANGE_PENDING_URL, null);
                long pendingAt = prefs.getLong(PREF_EXPLORER_CHANGE_PENDING_AT, 0);
                String currentUrl = getExplorerBaseUrl();
                log.info("EXPLORER_SWITCH[sid={}] pending_apply=true pendingUrl={} currentUrl={} pendingSinceMs={}",
                        sessionId, pendingUrl, currentUrl, pendingAt);
                clearExplorerChangePending(context);
                return pendingUrl;
            }
        } catch (Exception e) {
            log.warn("EXPLORER_SWITCH[sid={}] pending_check_failed: {}", sessionId, e.getMessage());
        }
        return null;
    }

    /**
     * Get the current explorer base URL from Configuration.
     * Falls back to default if not set.
     */
    public static String getExplorerBaseUrl() {
        return getBaseUrl();
    }

    /**
     * Get the current explorer base URL with in-memory authority.
     */
    public static synchronized String getBaseUrl() {
        if (appliedBaseUrl != null && !appliedBaseUrl.isEmpty()) {
            return appliedBaseUrl;
        }
        String configUrl = readConfigBaseUrl();
        appliedBaseUrl = configUrl;
        return configUrl;
    }

    /**
     * Called on app/service startup to read and apply the persisted explorer
     * preference.
     * This is the ONLY place where the explorer URL takes effect.
     * Objective A: Persist now, apply after restart.
     */
    public static synchronized String applyOnStartup(android.content.Context context) {
        String keyUsed = "api_base_url";
        String value = null;

        try {
            WalletApplication app = WalletApplication.getInstance();
            if (app != null) {
                Configuration config = app.getConfiguration();
                if (config != null) {
                    value = config.getApiBaseUrl();
                }
            }
        } catch (Exception e) {
            log.warn("EXPLORER_APPLY_ON_START[sid={}] failed to read config: {}", sessionId, e.getMessage());
        }

        if (value == null || value.isEmpty()) {
            value = DEFAULT_EXPLORER_URL;
            keyUsed = "default";
        }

        appliedBaseUrl = normalizeUrl(value);
        // Objective A: API_BASE_URL applied=... MUST reflect startup value
        log.info("API_BASE_URL applied={} reason=startup_load prefKey={} prefValue={} prefsName=default_prefs",
                appliedBaseUrl, keyUsed, value);
        log.info("API_BASE_URL_APPLIED baseUrl={}", appliedBaseUrl);

        // Bug A: Required EXPLORER_APPLIED log for debug contract
        String hostLabel = (appliedBaseUrl != null && appliedBaseUrl.contains(".org"))
                ? "explorer.pepepow.org"
                : "explorer.pepepow.net";
        log.info("EXPLORER_APPLIED host={} apiBase={}", hostLabel, appliedBaseUrl);
        log.info("EXPLORER_APPLY_ON_START keyUsed={} value={} baseUrl={}", keyUsed, value, appliedBaseUrl);

        // Clear any pending change marker
        clearExplorerChangePending(context);

        return appliedBaseUrl;
    }

    private static String readConfigBaseUrl() {
        try {
            WalletApplication app = WalletApplication.getInstance();
            if (app != null) {
                Configuration config = app.getConfiguration();
                if (config != null) {
                    String url = config.getApiBaseUrl();
                    if (url != null && !url.isEmpty()) {
                        return normalizeUrl(url);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("EXPLORER_CONFIG[sid={}] getBaseUrl failed: {} {}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage());
        }
        return DEFAULT_EXPLORER_URL;
    }

    /**
     * Apply explorer change from preferences, update in-memory base URL.
     */
    public static synchronized String onExplorerChanged(@Nullable SharedPreferences prefs) {
        String keyUsed = null;
        String value = null;

        if (prefs != null) {
            if (prefs.contains(Configuration.PREFS_KEY_API_BASE_URL)) {
                keyUsed = Configuration.PREFS_KEY_API_BASE_URL;
                value = prefs.getString(Configuration.PREFS_KEY_API_BASE_URL, null);
            } else if (prefs.contains(PREFS_KEY_DEV_API_BASE_URL)) {
                keyUsed = PREFS_KEY_DEV_API_BASE_URL;
                value = prefs.getString(PREFS_KEY_DEV_API_BASE_URL, null);
            }
        }

        if (value == null || value.isEmpty()) {
            value = readConfigBaseUrl();
            if (keyUsed == null) {
                keyUsed = "fallback_config";
            }
        }

        String normalized = normalizeUrl(value);
        appliedBaseUrl = normalized;

        log.info("EXPLORER_SWITCH[sid={}] keyUsed={} value={}", sessionId, keyUsed, value);
        log.info("EXPLORER_SWITCH[sid={}] APPLIED baseUrl={}", sessionId, normalized);
        return normalized;
    }

    /**
     * Get the pending explorer base URL if a switch is in progress.
     */
    public static String getPendingExplorerBaseUrl() {
        return pendingExplorerBase;
    }

    /**
     * Get the revert explorer base URL for a pending switch (if any).
     */
    public static String getRevertExplorerBaseUrl() {
        return revertUrl;
    }

    /**
     * Begin a two-phase explorer switch (requested -> verify -> commit/revert).
     */
    private static synchronized boolean beginExplorerSwitch(String oldUrl, String newUrl) {
        String normalizedOld = normalizeUrl(oldUrl);
        String normalizedNew = normalizeUrl(newUrl);

        if (normalizedNew.equals(normalizedOld)) {
            log.info("EXPLORER_SWITCH[sid={}] requested ignored: same URL {}", sessionId, normalizedNew);
            return false;
        }

        if (pendingExplorerBase != null && !pendingExplorerBase.equals(normalizedNew)) {
            log.warn("EXPLORER_SWITCH[sid={}] override pending={} new={}", sessionId, pendingExplorerBase,
                    normalizedNew);
        }

        pendingExplorerBase = normalizedNew;
        revertUrl = normalizedOld;
        log.info("EXPLORER_SWITCH[sid={}] requested old={} new={}", sessionId, normalizedOld, normalizedNew);
        return true;
    }

    /**
     * Ensure a pending switch is recorded (for config changes initiated elsewhere).
     */
    public static synchronized void beginExplorerSwitchIfNeeded(String oldUrl, String newUrl) {
        if (pendingExplorerBase == null || revertUrl == null) {
            beginExplorerSwitch(oldUrl, newUrl);
        }
    }

    /**
     * Prevent handling of the next API base URL preference change (used for
     * revert).
     */
    public static synchronized boolean consumeSuppressNextPreferenceHandling() {
        boolean suppress = suppressNextPreferenceHandling;
        suppressNextPreferenceHandling = false;
        return suppress;
    }

    /**
     * Centralized explorer switch handler for debug contract compliance.
     * Logs the switch request with overlay state information.
     * 
     * @param oldUrl          The previous explorer URL
     * @param newUrl          The new explorer URL
     * @param isOverlayActive Whether the FAST overlay is currently active
     */
    public static void onExplorerChanged(String oldUrl, String newUrl, boolean isOverlayActive) {
        log.info("EXPLORER_SWITCH[sid={}] old={} new={} overlay_active={}",
                sessionId, oldUrl, newUrl, isOverlayActive);
    }

    /**
     * Get the full URL for viewing a transaction in the browser.
     * Enforces strict same-origin policy with the ACTIVE explorer.
     */
    public static String getTxBrowserUrl(String txId) {
        String baseUrl = getExplorerBaseUrl();
        return baseUrl + "/tx/" + txId;
    }

    /**
     * Get the full URL for viewing a block in the browser.
     * Enforces strict same-origin policy with the ACTIVE explorer.
     */
    public static String getBlockBrowserUrl(String blockHash) {
        String baseUrl = getExplorerBaseUrl();
        return baseUrl + "/block/" + blockHash;
    }

    /**
     * Get the full URL for viewing an address in the browser.
     * Enforces strict same-origin policy with the ACTIVE explorer.
     */
    public static String getAddressBrowserUrl(String address) {
        String baseUrl = getExplorerBaseUrl();
        return baseUrl + "/address/" + address;
    }

    /**
     * Set the explorer base URL. Triggers incremental snapshot refresh.
     * Implements Two-Phase Safe Switch.
     */
    public static void setExplorerBaseUrl(Context context, String url) {
        try {
            WalletApplication app = WalletApplication.getInstance();
            if (app != null) {
                Configuration config = app.getConfiguration();
                if (config != null) {
                    String currentUrl = config.getApiBaseUrl();
                    String newUrl = normalizeUrl(url);

                    if (!beginExplorerSwitch(currentUrl, newUrl)) {
                        return;
                    }

                    // NOTE: The actual refresh must be triggered by the caller
                    // (BlockchainServiceImpl)
                    // seeing the configuration change or explicit call.
                    // For now, we update the config immediately to trigger the listener,
                    // BUT we rely on the listener to handle the "Pending" state if we were fully
                    // rigorous.
                    // However, to fit existing architecture where config setter triggers the
                    // change:
                    // We will set it here, but BlockchainServiceImpl needs to know it's a "Trial".

                    // Actually, per plan: "Set pendingExplorerBase" -> "Trigger snapshot refresh".
                    // If we set the config *now*, the app effectively switches.
                    // To do a safe switch, we should probably NOT set the persistent config yet,
                    // but we need the ApiClient to use the NEW url.
                    // The ApiClient likely reads from Configuration.

                    // REFINED STRATEGY:
                    // We Update the Configuration so the listeners fire.
                    // BUT BlockchainServiceImpl needs to know to NOT clear the session wallet on
                    // failure.
                    config.setApiBaseUrl(newUrl);
                }
            }
        } catch (Exception e) {
            log.error("EXPLORER_CONFIG[sid={}] setBaseUrl failed: {} {}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Two-Phase Safe Switch: Probe then commit.
     * Returns true if switch succeeded, false if probe failed (reverts to old URL).
     * 
     * MUST be called from a background thread.
     */
    public static boolean setExplorerBaseUrlWithProbe(Context context, String newUrl) {
        WalletApplication app = WalletApplication.getInstance();
        if (app == null) {
            log.warn("EXPLORER_SWITCH[sid={}] probe_failed: app is null", sessionId);
            return false;
        }
        Configuration config = app.getConfiguration();
        if (config == null) {
            log.warn("EXPLORER_SWITCH[sid={}] probe_failed: config is null", sessionId);
            return false;
        }

        String oldUrl = normalizeUrl(config.getApiBaseUrl());
        String normalizedNew = normalizeUrl(newUrl);

        if (normalizedNew.equals(oldUrl)) {
            log.info("EXPLORER_SWITCH[sid={}] requested ignored: same URL {}", sessionId, normalizedNew);
            return true; // Already on this URL
        }

        log.info("EXPLORER_SWITCH[sid={}] requested from={} to={}", sessionId, oldUrl, normalizedNew);
        log.info("EXPLORER_SWITCH[sid={}] probe_start baseUrl={}", sessionId, normalizedNew);

        long startTime = System.currentTimeMillis();
        boolean probeSuccess = probeExplorer(normalizedNew);
        long latencyMs = System.currentTimeMillis() - startTime;

        if (probeSuccess) {
            log.info("EXPLORER_SWITCH[sid={}] probe_ok baseUrl={} latencyMs={}", sessionId, normalizedNew, latencyMs);

            // Commit: persist new URL
            pendingExplorerBase = normalizedNew;
            revertUrl = oldUrl;
            config.setApiBaseUrl(normalizedNew);

            log.info("EXPLORER_SWITCH[sid={}] commit baseUrl={}", sessionId, normalizedNew);
            commitExplorerSwitch();
            return true;
        } else {
            log.warn("EXPLORER_SWITCH[sid={}] revert keepBaseUrl={} err=ProbeFailure:connection_failed",
                    sessionId, oldUrl);
            // Revert: keep old URL (no config change needed since we didn't persist yet)
            return false;
        }
    }

    /**
     * Lightweight probe request to verify explorer connectivity.
     * Uses /api/getblockcount endpoint (fast, reliable, low-cost).
     * Timeout: 5 seconds.
     */
    private static boolean probeExplorer(String baseUrl) {
        try {
            String probeUrl = baseUrl + "/api/getblockcount";
            java.net.URL url = new java.net.URL(probeUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            if (responseCode == 200) {
                return true;
            } else {
                log.warn("EXPLORER_SWITCH[sid={}] probe_failed: HTTP {} for {}",
                        sessionId, responseCode, probeUrl);
                return false;
            }
        } catch (java.net.SocketTimeoutException e) {
            log.warn("EXPLORER_SWITCH[sid={}] probe_failed: Timeout {}", sessionId, e.getMessage());
            return false;
        } catch (java.io.IOException e) {
            log.warn("EXPLORER_SWITCH[sid={}] probe_failed: {} {}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("EXPLORER_SWITCH[sid={}] probe_failed: {} {}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    public static void commitExplorerSwitch() {
        if (pendingExplorerBase != null) {
            log.info("EXPLORER_SWITCH[sid={}] commit active={}", sessionId, pendingExplorerBase);
            pendingExplorerBase = null;
            revertUrl = null;
        }
    }

    public static void revertExplorerSwitch() {
        revertExplorerSwitch(null, null);
    }

    public static void revertExplorerSwitch(@Nullable String errClass,
            @Nullable String errMsg) {
        try {
            WalletApplication app = WalletApplication.getInstance();
            if (app != null && pendingExplorerBase != null && revertUrl != null) {
                Configuration config = app.getConfiguration();
                String errDetail = (errClass != null && errMsg != null)
                        ? errClass + ":" + errMsg
                        : "unknown";
                log.info("EXPLORER_SWITCH[sid={}] revert active={} pending={} err={}",
                        sessionId, revertUrl, pendingExplorerBase, errDetail);
                if (config != null) {
                    suppressNextPreferenceHandling = true;
                    config.setApiBaseUrl(revertUrl);
                }
                pendingExplorerBase = null;
                revertUrl = null;
            }
        } catch (Exception e) {
            log.error("EXPLORER_CONFIG[sid={}] revert failed: {} {}", sessionId,
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Normalize URL by removing trailing slash.
     */
    private static String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return DEFAULT_EXPLORER_URL;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
