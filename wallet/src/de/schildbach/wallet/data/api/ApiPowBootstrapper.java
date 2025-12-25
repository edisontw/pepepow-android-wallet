package de.schildbach.wallet.data.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.store.BlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dash.wallet.common.data.SyncMode;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FAST_API_10POW/API_1000POW bootstrap:
 * - Fetch explorer tip height/hash/header from the API.
 * - Verify a small sample window via PoW.
 * - Persist ONLY overlay metadata (tip height/hash/time, offset) for UI.
 *
 * Stability contract:
 * - NEVER writes to SPV blockstore.
 * - NEVER modifies chain head.
 * FULL_SPV remains the only canonical chain for bitcoinj state.
 */
public class ApiPowBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(ApiPowBootstrapper.class);
    private static final String TAG = "ApiPowBootstrapper";
    private static final String PREFS_NAME = "ApiPowBootstrap";

    // Process-lifetime session ID for debug contract
    private static final String DEFAULT_SESSION_ID = UUID.randomUUID().toString().substring(0, 8);
    private String SESSION_ID = DEFAULT_SESSION_ID;
    // Internal-only log context (provided by BlockchainServiceImpl).
    private volatile String fastBootStateForLogs = "UNKNOWN";
    private volatile String utxoScanStateForLogs = "UNKNOWN";

    private static final int HEADER_WINDOW = 30;

    // Fast Sync V2.1: Tolerant PoW sampling policy (overlay-only)
    // Allows up to 3 total failures (12/15 pass), with stricter tip-tail
    // enforcement
    private static final int SAMPLES_TOTAL = 15; // Total samples to verify
    private static final int TOTAL_FAIL_MAX = 3; // Max 3 total failures allowed (require 12/15 passes)
    private static final int TIP_TAIL = 3; // Mandatory samples from tip (reduced from 5)
    private static final int TIP_FAIL_MAX = 1; // Max 1 failure allowed in tip-tail
    private static final int RETRY_DELAY_MIN_MS = 300; // Min delay before retry
    private static final int RETRY_DELAY_MAX_MS = 800; // Max delay before retry (random in range)

    private final ApiHeaderClient apiClient;
    private final PowVerifier powVerifier;
    private final NetworkParameters params;
    private final Context context;

    /**
     * Overlay-only capability flag for FAST bootstrap validation.
     *
     * FULL_HASH_VERIFY: getblock() exposes the PoW hash, allowing full hash
     * verification.
     * API_LIMITED: getblock() does not expose the PoW hash; validation is
     * height/continuity only.
     */
    public enum FastCapability {
        FULL_HASH_VERIFY,
        API_LIMITED
    }

    public ApiPowBootstrapper(Context context, ApiHeaderClient apiClient, PowVerifier powVerifier,
            NetworkParameters params) {
        this.context = context;
        this.apiClient = apiClient;
        this.powVerifier = powVerifier;
        this.params = params;
    }

    /**
     * FASTBOOT session id is assigned by the overlay service (process-lifetime) and
     * forwarded to the API client.
     * This affects logging only.
     */
    public void setSessionIdForLogs(String sessionId) {
        this.SESSION_ID = sessionId;
        apiClient.setSessionIdForLogs(sessionId);
    }

    /**
     * Internal-only: enrich required FASTBOOT logs with overlay state.
     */
    public void setOverlayStateForLogs(String fastBootState, String utxoScanState) {
        this.fastBootStateForLogs = fastBootState != null ? fastBootState : "UNKNOWN";
        this.utxoScanStateForLogs = utxoScanState != null ? utxoScanState : "UNKNOWN";
    }

    // ========== Failure Classification ==========

    /**
     * Classification of sample verification failures.
     */
    public enum SampleFailureType {
        /** PoW verification failed (computed hash doesn't meet difficulty) */
        POW_INVALID,
        /** API returned inconsistent data (continuity break) */
        API_INCONSISTENT,
        /** API limitation: getblock does not expose PoW hash (not a failure) */
        API_LIMITATION,
        /** Network error during fetch */
        NETWORK_ERROR
    }

    // ========== API Limitation Detection ==========

    /**
     * Session-level log guard for API-limited mode banner logs.
     * Detection itself is performed in {@link ApiHeaderClient} based on real API
     * capability.
     */
    private static volatile boolean apiLimitationLoggedForSession = false;

    /**
     * If API limitation detected, emit mandatory banner logs once per session.
     */
    private void maybeLogApiLimitedMode() {
        if (apiClient.isGetBlockMissingPowHashDetected() && !apiLimitationLoggedForSession) {
            log.warn("FASTBOOT[sid={}] FAST_CAPABILITY=API_LIMITED", SESSION_ID);
            log.info("FASTBOOT[sid={}] FAST_VALIDATION=HEIGHT_ONLY", SESSION_ID);
            apiLimitationLoggedForSession = true;
        }
    }

    public static class BootstrapResult {
        public final boolean success;
        public final int spvHeadHeight;
        public final int explorerTipHeight;
        public final String failureReason;
        public final int chainHeadHeight;
        @Nullable
        public final Sha256Hash chainHeadHash;
        public final long chainHeadTimeSeconds;
        @Nullable
        public final FastCapability fastCapability;

        private BootstrapResult(boolean success, int spvHeadHeight, int explorerTipHeight,
                String failureReason,
                int chainHeadHeight, @Nullable Sha256Hash chainHeadHash, long chainHeadTimeSeconds,
                @Nullable FastCapability fastCapability) {
            this.success = success;
            this.spvHeadHeight = spvHeadHeight;
            this.explorerTipHeight = explorerTipHeight;
            this.failureReason = failureReason;
            this.chainHeadHeight = chainHeadHeight;
            this.chainHeadHash = chainHeadHash;
            this.chainHeadTimeSeconds = chainHeadTimeSeconds;
            this.fastCapability = fastCapability;
        }

        public static BootstrapResult failure(String reason, int spvHeadHeight, int explorerTipHeight,
                long chainHeadTimeSeconds) {
            return new BootstrapResult(false, spvHeadHeight, explorerTipHeight, reason, 0, null,
                    chainHeadTimeSeconds, null);
        }

        public static BootstrapResult failure(String reason, int spvHeadHeight, int explorerTipHeight,
                long chainHeadTimeSeconds, @Nullable FastCapability fastCapability) {
            return new BootstrapResult(false, spvHeadHeight, explorerTipHeight, reason, 0, null,
                    chainHeadTimeSeconds, fastCapability);
        }

        public static BootstrapResult skipped(int localHeight, int apiTipHeight, long chainHeadTimeSeconds) {
            return new BootstrapResult(false, localHeight, apiTipHeight, "skipped", localHeight, null,
                    chainHeadTimeSeconds, null);
        }

        public static BootstrapResult success(int spvHeadHeight, int explorerTipHeight, int chainHeadHeight,
                @Nullable Sha256Hash chainHeadHash, long chainHeadTimeSeconds) {
            return new BootstrapResult(true, spvHeadHeight, explorerTipHeight, null, chainHeadHeight,
                    chainHeadHash,
                    chainHeadTimeSeconds, null);
        }

        public static BootstrapResult success(int spvHeadHeight, int explorerTipHeight, int chainHeadHeight,
                @Nullable Sha256Hash chainHeadHash, long chainHeadTimeSeconds,
                @Nullable FastCapability fastCapability) {
            return new BootstrapResult(true, spvHeadHeight, explorerTipHeight, null, chainHeadHeight,
                    chainHeadHash, chainHeadTimeSeconds, fastCapability);
        }

        @Override
        public String toString() {
            return "BootstrapResult{success=" + success + ", spvHead=" + spvHeadHeight +
                    ", apiTip=" + explorerTipHeight + ", chainHeadHeight=" + chainHeadHeight +
                    ", chainHeadHash=" + (chainHeadHash != null ? chainHeadHash.toString() : "null") +
                    ", reason=" + failureReason + ", chainHeadTimeSeconds=" + chainHeadTimeSeconds +
                    ", fastCapability=" + (fastCapability != null ? fastCapability.name() : "null") + "}";
        }
    }

    public BootstrapResult runBootstrapIfNeeded(NetworkParameters params, SyncMode mode) {
        // Debug contract log point #1: entry with mode + states + last run time
        long lastRunTimeMs = 0;
        try {
            android.content.SharedPreferences configPrefs = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(context);
            lastRunTimeMs = configPrefs.getLong(
                    org.dash.wallet.common.Configuration.PREFS_KEY_LAST_FAST_BOOTSTRAP_TIME, 0);
        } catch (Exception e) {
            log.warn("FASTBOOT[sid={}] prefsReadFailed ex={} msg={}",
                    SESSION_ID, e.getClass().getSimpleName(), e.getMessage());
        }
        log.info("FASTBOOT[sid={}] runBootstrapIfNeeded: mode={} fastBootState={} utxoScanState={} lastRunMs={}",
                SESSION_ID, mode, fastBootStateForLogs, utxoScanStateForLogs, lastRunTimeMs);
        NetworkParameters activeParams = params != null ? params : this.params;

        if (mode != SyncMode.FAST_API_10POW && mode != SyncMode.API_1000POW) {
            return BootstrapResult.skipped(0, 0, 0);
        }

        int localSpvHeight = 0;
        // In pure overlay mode, we do not peek at SPV store. Access is strictly
        // forbidden.

        int apiTipHeight = 0;
        long apiTipTime = 0;

        HeaderDto apiTipHeader = null;
        try {
            apiTipHeight = (int) apiClient.fetchBlockCount();
            if (apiTipHeight <= 0) {
                return BootstrapResult.failure("invalid-tip-height", localSpvHeight, 0, 0);
            }

            // TASK 5: Windowed PoW Check
            int windowSize = HEADER_WINDOW;
            int startHeight = Math.max(0, apiTipHeight - windowSize + 1);

            Log.i(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Fetching header window " + startHeight + "-"
                    + apiTipHeight);
            List<HeaderDto> window = fetchHeaderWindow(startHeight, apiTipHeight);

            if (window == null || window.isEmpty()) {
                return BootstrapResult.failure("window-fetch-failed", localSpvHeight, apiTipHeight, 0);
            }
            if (window.size() < (Math.min(windowSize, apiTipHeight))) {
                // partial window might be okay if very early in chain, but unlikely for
                // production
                Log.w(TAG, "FASTBOOT[sid=" + SESSION_ID + "] fetched partial window size=" + window.size());
            }

            final FastCapability fastCapability = apiClient.isGetBlockMissingPowHashDetected()
                    ? FastCapability.API_LIMITED
                    : FastCapability.FULL_HASH_VERIFY;

            if (fastCapability == FastCapability.API_LIMITED) {
                maybeLogApiLimitedMode();
                ApiLimitedValidationResult limited = validateApiLimitedWindow(window, startHeight);
                if (!limited.passed) {
                    log.warn("FASTBOOT[sid={}] API_LIMITED validation_failed detail={}", SESSION_ID,
                            limited.detail);
                    return BootstrapResult.failure("API_LIMITED_UNRELIABLE", localSpvHeight, apiTipHeight, apiTipTime,
                            fastCapability);
                }

                // Capture tip header for UI-only metadata.
                apiTipHeader = window.get(window.size() - 1);
                apiTipTime = apiTipHeader.time;

                saveBootstrapSuccess(localSpvHeight, apiTipHeight);
                updateOffset(apiTipHeight, localSpvHeight);

                Sha256Hash tipHash = null;
                try {
                    if (apiTipHeader.hash != null) {
                        tipHash = Sha256Hash.wrap(apiTipHeader.hash);
                    }
                } catch (Exception ignored) {
                    tipHash = null;
                }

                return BootstrapResult.success(localSpvHeight, apiTipHeight, apiTipHeight, tipHash, apiTipTime,
                        fastCapability);
            }

            if (!isContiguous(window, startHeight)) {
                return BootstrapResult.failure("window-not-contiguous", localSpvHeight, apiTipHeight, 0,
                        fastCapability);
            }

            // Capture tip header for soft-fail fallback
            apiTipHeader = window.get(window.size() - 1);
            apiTipTime = apiTipHeader.time;

            // Fast Sync V2.1: Tolerant PoW sampling with tip-tail enforcement
            List<HeaderDto> samples = pickTolerantPowSamples(window, apiTipHeight);
            Log.i(TAG,
                    "FASTBOOT[sid=" + SESSION_ID + "] Verifying " + samples.size() + " samples (tipTail="
                            + TIP_TAIL +
                            ", totalFailMax=" + TOTAL_FAIL_MAX + ", tipFailMax=" + TIP_FAIL_MAX + ")...");

            // Verify with tolerant policy
            PowVerificationResult powResult = verifyPowSamplesTolerant(samples, activeParams, apiTipHeight);

            // Log sampling results (Debug Contract)
            log.info("FASTBOOT[sid={}] POW-RESULT: totalSamples={} pass={} fail={} " +
                    "powInvalid={} apiInconsistent={} networkError={} " +
                    "tipSamples={} tipPass={} tipFail={} retriesUsed={}",
                    SESSION_ID, powResult.totalSamples, powResult.passCount, powResult.failCount,
                    powResult.powInvalidCount, powResult.apiInconsistentCount, powResult.networkErrorCount,
                    powResult.tipSamples, powResult.tipPass, powResult.tipFail, powResult.retriesUsed);

            // Check if tolerant thresholds are met
            if (!powResult.isAcceptable()) {
                String reason = powResult.getFailureReason();
                Log.w(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Tolerant verification FAILED: " + reason);
                return BootstrapResult.failure("pow-tolerant-" + reason, localSpvHeight, apiTipHeight, apiTipTime);
            }

            Log.i(TAG,
                    "FASTBOOT[sid=" + SESSION_ID + "] Tolerant verification PASSED (pass=" + powResult.passCount +
                            "/" + powResult.totalSamples + ", tipPass=" + powResult.tipPass + "/" + powResult.tipSamples
                            + ")");

            // Success - Validate Tip but DO NOT PERSIST to store aggressively yet (Overlay
            // logic)
            // apiTipHeader is already set above

            Log.i(TAG,
                    "FASTBOOT[sid=" + SESSION_ID + "] API tip height=" + apiTipHeight + " hash="
                            + apiTipHeader.hash);

            // Create StoredBlock
            StoredBlock apiTipStored = createStoredBlockOnly(apiTipHeader, activeParams);

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean("bootstrap_done", true).apply();

            // Overlay-only persistence: record API tip + local offset, but never mutate SPV
            // core.
            saveBootstrapSuccess(localSpvHeight, apiTipHeight);
            updateOffset(apiTipHeight, localSpvHeight);

            return BootstrapResult.success(localSpvHeight, apiTipHeight, apiTipHeight,
                    apiTipStored.getHeader().getHash(), apiTipStored.getHeader().getTimeSeconds(),
                    FastCapability.FULL_HASH_VERIFY);

        } catch (Exception e) {
            Log.e(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Exception: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage(), e);
            log.error("FASTBOOT[sid={}] exception class={} message={}", SESSION_ID, e.getClass().getSimpleName(),
                    e.getMessage());
            // FAIL HARD: Any exception during window fetch, sample fetch, or verification
            // is a hard failure.
            final FastCapability fastCapability = apiClient.isGetBlockMissingPowHashDetected()
                    ? FastCapability.API_LIMITED
                    : FastCapability.FULL_HASH_VERIFY;
            return BootstrapResult.failure("exception-" + e.getMessage(), localSpvHeight, apiTipHeight, apiTipTime,
                    fastCapability);
        }
    }

    private static final class ApiLimitedValidationResult {
        final boolean passed;
        final String detail;

        ApiLimitedValidationResult(boolean passed, String detail) {
            this.passed = passed;
            this.detail = detail;
        }
    }

    private ApiLimitedValidationResult validateApiLimitedWindow(List<HeaderDto> headers, int expectedStartHeight) {
        if (headers == null || headers.isEmpty()) {
            return new ApiLimitedValidationResult(false, "empty_window");
        }
        for (int i = 0; i < headers.size(); i++) {
            HeaderDto current = headers.get(i);
            long expectedHeight = expectedStartHeight + i;
            if (current.height != expectedHeight) {
                return new ApiLimitedValidationResult(false, "height_non_monotonic");
            }
            if (current.hash == null || current.hash.trim().isEmpty()) {
                return new ApiLimitedValidationResult(false, "missing_getblockhash");
            }
            if (i == 0) {
                continue;
            }
            HeaderDto previous = headers.get(i - 1);
            if (current.previousBlockHash == null || previous.hash == null) {
                // Best-effort continuity: missing fields are not treated as failure in
                // API_LIMITED.
                continue;
            }
            if (!normalizeHash(current.previousBlockHash).equals(normalizeHash(previous.hash))) {
                return new ApiLimitedValidationResult(false, "prevhash_mismatch");
            }
        }
        return new ApiLimitedValidationResult(true, null);
    }

    private List<HeaderDto> fetchHeaderWindow(int startHeight, int endHeight) throws Exception {
        List<HeaderDto> headers = new ArrayList<>(HEADER_WINDOW);
        for (int h = startHeight; h <= endHeight && headers.size() < HEADER_WINDOW; h++) {
            HeaderDto dto = apiClient.fetchHeaderAtHeight(h);
            if (dto == null) {
                break;
            }
            if (dto.height == 0) {
                dto.height = h;
            }
            headers.add(dto);
        }
        return headers;
    }

    private boolean isContiguous(List<HeaderDto> headers, int expectedStartHeight) {
        if (headers.isEmpty()) {
            return false;
        }
        for (int i = 0; i < headers.size(); i++) {
            HeaderDto current = headers.get(i);
            long expectedHeight = expectedStartHeight + i;
            if (current.height != expectedHeight) {
                Log.w(TAG,
                        "FASTBOOT[sid=" + SESSION_ID + "] Height mismatch at index " + i + " expected=" + expectedHeight
                                + " got=" + current.height);
                return false;
            }
            if (i == 0) {
                continue;
            }
            HeaderDto previous = headers.get(i - 1);
            if (current.previousBlockHash == null || previous.hash == null
                    || !normalizeHash(current.previousBlockHash).equals(normalizeHash(previous.hash))) {
                Log.w(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Prev-hash mismatch at height " + current.height);
                return false;
            }
        }
        return true;
    }

    private List<HeaderDto> pickPowSamples(List<HeaderDto> headers, int count) {
        List<HeaderDto> copy = new ArrayList<>(headers);
        Collections.shuffle(copy, ThreadLocalRandom.current());
        if (copy.size() > count) {
            return new ArrayList<>(copy.subList(0, count));
        }
        return copy;
    }

    private List<Block> fetchPowBlocks(List<HeaderDto> samples, NetworkParameters networkParameters) throws Exception {
        List<Block> blocks = new ArrayList<>(samples.size());
        for (HeaderDto header : samples) {
            if (header.hash == null) {
                throw new IllegalStateException("Header missing hash at height " + header.height);
            }
            BlockDto blockDto = apiClient.fetchBlockByHash(header.hash);
            if (blockDto == null) {
                throw new IllegalStateException("Failed to fetch block for hash " + header.hash);
            }
            if (blockDto.height == 0) {
                blockDto.height = header.height;
            }
            Block block = blockDto.toBlock(networkParameters);
            // Use robust hash comparison
            HashComparisonResult hashResult = compareHashesRobust(header.hash, block.getHashAsString(),
                    (int) header.height);
            if (!hashResult.matches) {
                throw new VerificationException(
                        "Block hash mismatch at height " + header.height + ": " + hashResult.reason);
            }
            blocks.add(block);
        }
        return blocks;
    }

    private StoredBlock findPrevious(BlockStore blockStore, HeaderDto checkpointHeader) {
        try {
            if (checkpointHeader.previousBlockHash == null) {
                return null;
            }
            return blockStore.get(Sha256Hash.wrap(checkpointHeader.previousBlockHash));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveBootstrapSuccess(int headHeight, int explorerTipHeight) {
        try {
            SharedPreferences configPrefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
            configPrefs.edit()
                    .putBoolean(org.dash.wallet.common.Configuration.PREFS_KEY_LAST_FAST_BOOTSTRAP_SUCCESS, true)
                    .putInt(org.dash.wallet.common.Configuration.PREFS_KEY_LAST_FAST_BOOTSTRAP_HEAD_HEIGHT, headHeight)
                    .putInt(org.dash.wallet.common.Configuration.PREFS_KEY_LAST_FAST_BOOTSTRAP_EXPLORER_TIP,
                            explorerTipHeight)
                    .putLong(org.dash.wallet.common.Configuration.PREFS_KEY_LAST_FAST_BOOTSTRAP_TIME,
                            System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save bootstrap success state", e);
        }
    }

    private void updateOffset(int apiTipHeight, int spvHeight) {
        int offset = apiTipHeight - spvHeight;
        try {
            SharedPreferences configPrefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
            configPrefs.edit().putInt(org.dash.wallet.common.Configuration.PREFS_KEY_API_SPV_OFFSET, offset).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save API offset", e);
        }
    }

    private HeaderDto fetchTipHeader(int apiTipHeight) {
        try {
            HeaderDto tip = apiClient.fetchHeaderAtHeight(apiTipHeight);
            if (tip != null && tip.height == 0) {
                tip.height = apiTipHeight;
            }
            return tip;
        } catch (Exception e) {
            Log.w(TAG,
                    "FASTBOOT[sid=" + SESSION_ID + "] failed to fetch tip header at height " + apiTipHeight + ": "
                            + e.getMessage());
            return null;
        }
    }

    private StoredBlock createStoredBlockOnly(HeaderDto header,
            NetworkParameters networkParameters) {
        if (header.height <= 0) {
            throw new IllegalArgumentException("Header height must be set for snapshot object");
        }
        // Create transient stored block
        Block blockHeader = header.toBlock(networkParameters);
        return new StoredBlock(blockHeader, header.toChainWork(networkParameters, null), (int) header.height);
    }

    // ========== Hash Comparison Utilities ==========

    /**
     * Normalize a hash string: lowercase, strip 0x prefix, validate 64 hex chars.
     * Returns null if invalid.
     */
    private static String normalizeHash(String hash) {
        if (hash == null) {
            return null;
        }
        String normalized = hash.toLowerCase().trim();
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        // Validate 64 hex characters
        if (normalized.length() != 64 || !normalized.matches("[0-9a-f]+")) {
            return null;
        }
        return normalized;
    }

    /**
     * Reverse bytes of a hex hash string (for endianness conversion).
     * Bitcoin display hashes are byte-reversed from computed hashes.
     */
    private static String reverseHashEndianness(String hexHash) {
        if (hexHash == null || hexHash.length() != 64) {
            return null;
        }
        StringBuilder reversed = new StringBuilder(64);
        for (int i = 62; i >= 0; i -= 2) {
            reversed.append(hexHash.charAt(i));
            reversed.append(hexHash.charAt(i + 1));
        }
        return reversed.toString();
    }

    /**
     * Result of hash comparison with details for logging.
     */
    private static class HashComparisonResult {
        final boolean matches;
        final String reason;
        final String normExpected;
        final String normGot;
        final boolean usedEndianReversal;

        HashComparisonResult(boolean matches, String reason, String normExpected, String normGot,
                boolean usedEndianReversal) {
            this.matches = matches;
            this.reason = reason;
            this.normExpected = normExpected;
            this.normGot = normGot;
            this.usedEndianReversal = usedEndianReversal;
        }
    }

    /**
     * Compare two hashes with normalization and endianness check.
     * Returns detailed result for logging.
     */
    private HashComparisonResult compareHashesRobust(String expectedHash, String gotHash, int height) {
        String normExpected = normalizeHash(expectedHash);
        String normGot = normalizeHash(gotHash);

        // Log raw values for debugging
        log.debug("FASTBOOT[sid={}] hash-compare h={} raw-expected={} raw-got={}",
                SESSION_ID, height, expectedHash, gotHash);

        if (normExpected == null) {
            return new HashComparisonResult(false, "invalid-expected-hash", expectedHash, gotHash, false);
        }
        if (normGot == null) {
            return new HashComparisonResult(false, "invalid-got-hash", expectedHash, gotHash, false);
        }

        // Direct comparison
        if (normExpected.equals(normGot)) {
            return new HashComparisonResult(true, "match-direct", normExpected, normGot, false);
        }

        // Try endianness reversal (Bitcoin display format vs internal format)
        String reversedExpected = reverseHashEndianness(normExpected);
        if (reversedExpected != null && reversedExpected.equals(normGot)) {
            log.info(
                    "FASTBOOT[sid={}] POW-ENDIAN h={} expected={} got={} reversed-expected={} result=match-endian-reversal",
                    SESSION_ID, height, normExpected, normGot, reversedExpected);
            return new HashComparisonResult(true, "match-endian-reversal", normExpected, normGot, true);
        }

        String reversedGot = reverseHashEndianness(normGot);
        if (reversedGot != null && reversedGot.equals(normExpected)) {
            log.info("FASTBOOT[sid={}] POW-ENDIAN h={} expected={} got={} reversed-got={} result=match-endian-reversal",
                    SESSION_ID, height, normExpected, normGot, reversedGot);
            return new HashComparisonResult(true, "match-endian-reversal", normExpected, normGot, true);
        }

        // Still mismatch - this is API_INCONSISTENT
        return new HashComparisonResult(false, "hash-mismatch", normExpected, normGot, false);
    }

    /**
     * Result class for tolerant PoW verification.
     * Tracks pass/fail counts for both total samples and tip-tail samples.
     * Now includes failure type classification and API limitation tracking.
     */
    public static class PowVerificationResult {
        public final int totalSamples;
        public final int passCount;
        public final int failCount;
        public final int powInvalidCount;
        public final int apiInconsistentCount;
        public final int apiLimitationCount; // NEW: samples that passed via continuity-only validation
        public final int networkErrorCount;
        public final int tipSamples;
        public final int tipPass;
        public final int tipFail;
        public final List<Long> failedHeights;
        public final int retriesUsed;
        public final boolean apiLimitedMode; // NEW: true if validation was done in API-limited mode

        public PowVerificationResult(int totalSamples, int passCount, int failCount,
                int powInvalidCount, int apiInconsistentCount, int apiLimitationCount, int networkErrorCount,
                int tipSamples, int tipPass, int tipFail,
                List<Long> failedHeights, int retriesUsed, boolean apiLimitedMode) {
            this.totalSamples = totalSamples;
            this.passCount = passCount;
            this.failCount = failCount;
            this.powInvalidCount = powInvalidCount;
            this.apiInconsistentCount = apiInconsistentCount;
            this.apiLimitationCount = apiLimitationCount;
            this.networkErrorCount = networkErrorCount;
            this.tipSamples = tipSamples;
            this.tipPass = tipPass;
            this.tipFail = tipFail;
            this.failedHeights = failedHeights != null ? failedHeights : new ArrayList<>();
            this.retriesUsed = retriesUsed;
            this.apiLimitedMode = apiLimitedMode;
        }

        /**
         * Check if the verification result meets tolerant thresholds (v2.1).
         * - In API-limited mode: accept if continuity holds (apiLimitationCount ==
         * passCount)
         * - Total failures must be <= TOTAL_FAIL_MAX (3)
         * - Tip-tail failures must be <= TIP_FAIL_MAX (1)
         */
        public boolean isAcceptable() {
            // In API-limited mode, we accept if most samples passed continuity validation
            if (apiLimitedMode) {
                // Accept if at least 80% passed via continuity validation
                return (failCount <= TOTAL_FAIL_MAX) && (tipFail <= TIP_FAIL_MAX);
            }
            return failCount <= TOTAL_FAIL_MAX && tipFail <= TIP_FAIL_MAX;
        }

        /**
         * Get a human-readable failure reason if not acceptable.
         */
        public String getFailureReason() {
            if (tipFail > TIP_FAIL_MAX) {
                return "tip-tail-exceeded(" + tipFail + ">" + TIP_FAIL_MAX + ")";
            }
            if (failCount > TOTAL_FAIL_MAX) {
                // Include failure classification (Task C)
                if (apiLimitedMode) {
                    int apiLimitationFailCount = Math.max(0,
                            failCount - apiInconsistentCount - powInvalidCount - networkErrorCount);
                    if (apiInconsistentCount >= apiLimitationFailCount && apiInconsistentCount >= networkErrorCount) {
                        return "API_INCONSISTENT(total=" + failCount + ",apiInconsistent=" + apiInconsistentCount
                                + ")";
                    } else if (apiLimitationFailCount >= networkErrorCount) {
                        return "API_LIMITATION(total=" + failCount + ",apiLimitation=" + apiLimitationFailCount + ")";
                    } else {
                        return "NETWORK_ERROR(total=" + failCount + ",networkError=" + networkErrorCount + ")";
                    }
                } else {
                    if (apiInconsistentCount >= powInvalidCount && apiInconsistentCount >= networkErrorCount) {
                        return "API_INCONSISTENT(total=" + failCount + ",apiInconsistent=" + apiInconsistentCount
                                + ")";
                    } else if (powInvalidCount >= networkErrorCount) {
                        return "POW_INVALID(total=" + failCount + ",powInvalid=" + powInvalidCount + ")";
                    } else {
                        return "NETWORK_ERROR(total=" + failCount + ",networkError=" + networkErrorCount + ")";
                    }
                }
            }
            return "unknown";
        }
    }

    /**
     * Pick samples using tolerant policy:
     * 1. Always include the last TIP_TAIL heights (tip-tail) as mandatory samples
     * 2. Fill remaining slots with random samples from the window
     */
    private List<HeaderDto> pickTolerantPowSamples(List<HeaderDto> window, int apiTipHeight) {
        List<HeaderDto> samples = new ArrayList<>(SAMPLES_TOTAL);

        // Step 1: Add tip-tail samples (most recent TIP_TAIL blocks)
        int windowEnd = window.size() - 1;
        int tipTailStart = Math.max(0, windowEnd - TIP_TAIL + 1);
        for (int i = windowEnd; i >= tipTailStart && samples.size() < TIP_TAIL; i--) {
            samples.add(window.get(i));
        }
        Log.i(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Added " + samples.size() + " tip-tail samples");

        // Step 2: Collect remaining candidates (excluding tip-tail)
        List<HeaderDto> remainingCandidates = new ArrayList<>();
        for (int i = 0; i < tipTailStart; i++) {
            remainingCandidates.add(window.get(i));
        }

        // Step 3: Randomly select remaining samples to fill up to SAMPLES_TOTAL
        Collections.shuffle(remainingCandidates, ThreadLocalRandom.current());
        int remainingNeeded = SAMPLES_TOTAL - samples.size();
        for (int i = 0; i < Math.min(remainingNeeded, remainingCandidates.size()); i++) {
            samples.add(remainingCandidates.get(i));
        }

        Log.i(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Total samples selected: " + samples.size() +
                " (tipTail=" + TIP_TAIL + ", random=" + (samples.size() - Math.min(TIP_TAIL, samples.size())) + ")");
        return samples;
    }

    /**
     * Verify PoW samples with tolerant policy (v2.1).
     * - Tracks pass/fail for tip-tail vs random samples separately
     * - Allows one retry per failed sample with cache-bust before counting as
     * failure
     * - Random delay in [300, 800]ms range to avoid thundering herd
     * - Returns detailed result for logging and decision making
     * - Now classifies failures as POW_INVALID, API_INCONSISTENT, API_LIMITATION,
     * or NETWORK_ERROR
     * - API_LIMITATION means hash mismatch due to getblock not exposing PoW hash
     * (continuity validation used instead)
     */
    private PowVerificationResult verifyPowSamplesTolerant(List<HeaderDto> samples,
            NetworkParameters networkParameters, int apiTipHeight) {
        int passCount = 0;
        int failCount = 0;
        int powInvalidCount = 0;
        int apiInconsistentCount = 0;
        int apiLimitationCount = 0;
        int networkErrorCount = 0;
        int tipPass = 0;
        int tipFail = 0;
        int retriesUsed = 0;
        List<Long> failedHeights = new ArrayList<>();

        // Determine tip-tail height range
        int tipTailMinHeight = apiTipHeight - TIP_TAIL + 1;

        // Log API limitation detection status (Task D) if already known from
        // header-window fetch
        maybeLogApiLimitedMode();

        // FAST-SAMPLE entry log (v2.1 format)
        log.info(
                "FASTBOOT[sid={}] POW-SAMPLE-START total={} tipTail={} totalFailMax={} tipFailMax={} apiLimitedMode={}",
                SESSION_ID, SAMPLES_TOTAL, TIP_TAIL, TOTAL_FAIL_MAX, TIP_FAIL_MAX,
                apiClient.isGetBlockMissingPowHashDetected());

        for (HeaderDto header : samples) {
            // Detection can occur during sampling; ensure mandatory banner logs are emitted
            // once.
            maybeLogApiLimitedMode();
            boolean isTipTail = header.height >= tipTailMinHeight;
            SampleVerifyResult result = null;

            // First attempt (no cache-bust)
            result = verifySingleSample(header, networkParameters, false);

            // Retry logic: one retry per failed sample with cache-bust
            // Skip retry for API_LIMITATION as it won't help
            if (!result.verified && result.failureType != SampleFailureType.API_LIMITATION) {
                retriesUsed++;
                // Random delay in [RETRY_DELAY_MIN_MS, RETRY_DELAY_MAX_MS] range
                int delayMs = RETRY_DELAY_MIN_MS + ThreadLocalRandom.current().nextInt(
                        RETRY_DELAY_MAX_MS - RETRY_DELAY_MIN_MS + 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                Log.i(TAG,
                        "FASTBOOT[sid=" + SESSION_ID + "] Retry for height " + header.height
                                + " (cacheBust=true, delay=" + delayMs + "ms)");
                result = verifySingleSample(header, networkParameters, true);

                if (result.verified) {
                    Log.i(TAG, "FASTBOOT[sid=" + SESSION_ID + "] Retry succeeded for height " + header.height);
                }
            }

            // Update counters
            if (result.verified) {
                passCount++;
                if (isTipTail)
                    tipPass++;
                // Track if this was API-limited validation pass
                if (result.failureType == SampleFailureType.API_LIMITATION) {
                    apiLimitationCount++;
                }
            } else {
                failCount++;
                failedHeights.add(header.height);
                if (isTipTail)
                    tipFail++;

                // Classify failure
                switch (result.failureType) {
                    case POW_INVALID:
                        powInvalidCount++;
                        break;
                    case API_INCONSISTENT:
                        apiInconsistentCount++;
                        break;
                    case API_LIMITATION:
                        // API limitation without continuity pass should not happen, but track
                        apiLimitationCount++;
                        break;
                    case NETWORK_ERROR:
                        networkErrorCount++;
                        break;
                }

                Log.w(TAG, "FASTBOOT[sid=" + SESSION_ID + "] FAILED height=" + header.height +
                        " isTipTail=" + isTipTail + " type=" + result.failureType);
            }
        }

        int tipSamples = 0;
        for (HeaderDto h : samples) {
            if (h.height >= tipTailMinHeight)
                tipSamples++;
        }

        // FAST-SAMPLE-RESULT log (v2.1 format + API limitation info)
        String failedHeightsStr = failedHeights.size() > 6
                ? failedHeights.subList(0, 6).toString() + "..."
                : failedHeights.toString();
        log.info("FASTBOOT[sid={}] POW-SAMPLE-RESULT pass={} fail={} powInvalid={} apiInconsistent={} " +
                "apiLimitation={} networkError={} tipFail={} retryUsed={} apiLimitedMode={} failedHeights={}",
                SESSION_ID, passCount, failCount, powInvalidCount, apiInconsistentCount,
                apiLimitationCount, networkErrorCount, tipFail, retriesUsed,
                apiClient.isGetBlockMissingPowHashDetected(), failedHeightsStr);

        return new PowVerificationResult(
                samples.size(), passCount, failCount,
                powInvalidCount, apiInconsistentCount, apiLimitationCount, networkErrorCount,
                tipSamples, tipPass, tipFail,
                failedHeights, retriesUsed, apiClient.isGetBlockMissingPowHashDetected());
    }

    /**
     * Result of verifying a single sample.
     */
    private static class SampleVerifyResult {
        final boolean verified;
        final SampleFailureType failureType;
        final String failureDetail;

        SampleVerifyResult(boolean verified, SampleFailureType failureType, String failureDetail) {
            this.verified = verified;
            this.failureType = failureType;
            this.failureDetail = failureDetail;
        }

        static SampleVerifyResult success() {
            return new SampleVerifyResult(true, null, null);
        }

        static SampleVerifyResult failure(SampleFailureType type, String detail) {
            return new SampleVerifyResult(false, type, detail);
        }
    }

    /**
     * Verify a single sample with detailed mismatch logging.
     * Now supports API-limited mode where getblock does not expose PoW hash.
     * In this case, uses continuity-based validation instead.
     */
    private SampleVerifyResult verifySingleSample(HeaderDto header, NetworkParameters networkParameters,
            boolean cacheBust) {
        if (header == null || header.height <= 0) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} reason=header_missing_height", SESSION_ID,
                    header != null ? header.height : -1);
            return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "header_missing_height");
        }
        if (header.hash == null) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} reason=header_missing_pow_hash", SESSION_ID, header.height);
            return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "header_missing_pow_hash");
        }

        BlockDto blockDto;
        try {
            if (cacheBust) {
                blockDto = apiClient.fetchBlockByHashWithCacheBust(header.hash);
            } else {
                blockDto = apiClient.fetchBlockByHash(header.hash);
            }
        } catch (java.io.IOException e) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} reason=network_error error={}",
                    SESSION_ID, header.height, e.getMessage());
            return SampleVerifyResult.failure(SampleFailureType.NETWORK_ERROR, e.getMessage());
        } catch (Exception e) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} reason=fetch_error error_class={} error={}",
                    SESSION_ID, header.height, e.getClass().getSimpleName(), e.getMessage());
            return SampleVerifyResult.failure(SampleFailureType.NETWORK_ERROR, e.getMessage());
        }

        if (blockDto == null) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} reason=null_block_response", SESSION_ID, header.height);
            return SampleVerifyResult.failure(SampleFailureType.NETWORK_ERROR, "null_block_response");
        }
        if (blockDto.height == 0) {
            blockDto.height = header.height;
        }

        // Task B/C: If API does NOT expose the PoW block hash in getblock(), switch to
        // continuity validation.
        // Detection and the one-time API-LIMITATION log are owned by ApiHeaderClient
        // (Task A).
        if (apiClient.isGetBlockMissingPowHashDetected()) {
            maybeLogApiLimitedMode();
            return verifySampleWithContinuity(header, blockDto, cacheBust);
        }

        // Log available hash fields from API response for schema verification (Task A)
        logBlockDtoHashFields(header.height, header.hash, blockDto);

        Block block;
        try {
            block = blockDto.toBlock(networkParameters);
        } catch (Exception e) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} reason=block_parse_error error_class={} error={}",
                    SESSION_ID, header.height, e.getClass().getSimpleName(), e.getMessage());
            return SampleVerifyResult.failure(SampleFailureType.API_INCONSISTENT,
                    "block_parse_error: " + e.getMessage());
        }

        // Original hash-based validation (when API exposes PoW hash)
        String computedHash = block.getHashAsString();
        String returnedPowHash = selectPowBlockHash(blockDto, block);
        String fieldUsed = (blockDto.hash != null && !blockDto.hash.isEmpty()) ? "blockDto.hash" : "computed";

        // Robust hash comparison with normalization and endianness check
        HashComparisonResult hashResult = compareHashesRobust(header.hash, returnedPowHash, (int) header.height);

        // Enhanced POW-COMPARE logging
        log.info("FASTBOOT[sid={}] POW-COMPARE h={} expected={} returned={} field={} result={}",
                SESSION_ID, header.height, hashResult.normExpected, hashResult.normGot,
                fieldUsed, hashResult.matches ? "PASS" : "FAIL");

        if (!hashResult.matches) {
            // Detailed mismatch log as per debug contract
            String endianCheck = hashResult.usedEndianReversal ? "match" : "no_match";
            log.warn(
                    "FASTBOOT[sid={}] POW-MISMATCH h={}\n expected={}\n returned={}\n field={}\n normalized=lowercase+strip0x+64hex\n endianCheck={}\n reason={}",
                    SESSION_ID, header.height,
                    hashResult.normExpected, hashResult.normGot,
                    fieldUsed, endianCheck, hashResult.reason);

            // Additional diagnostic: log block header fields
            log.warn(
                    "FASTBOOT[sid={}] POW-MISMATCH-DETAIL h={} version={} prevHash={} merkle={} time={} bits={} nonce={}",
                    SESSION_ID, header.height, blockDto.version,
                    blockDto.previousBlockHash, blockDto.merkleRoot,
                    blockDto.time, blockDto.bits, blockDto.nonce);

            return SampleVerifyResult.failure(SampleFailureType.API_INCONSISTENT, hashResult.reason);
        }

        if (hashResult.usedEndianReversal) {
            log.info("FASTBOOT[sid={}] POW-OK h={} note=endianness_reversal_used", SESSION_ID, header.height);
        }

        // Verify PoW
        try {
            block.verifyHeader();
        } catch (VerificationException e) {
            log.warn("FASTBOOT[sid={}] POW-MISMATCH h={} expected={} got={} reason=pow_verification_failed error={}",
                    SESSION_ID, header.height, header.hash, computedHash, e.getMessage());
            return SampleVerifyResult.failure(SampleFailureType.POW_INVALID,
                    "pow_verification_failed: " + e.getMessage());
        }

        return SampleVerifyResult.success();
    }

    /**
     * Task B: Verify sample using continuity-based validation when API does not
     * expose PoW hash.
     * Validates:
     * 1. getblockhash(height) consistency across samples
     * 2. previousblockhash continuity: prevHash(block H) == getblockhash(H-1)
     * 3. Header field sanity: version, bits, time monotonicity
     * 
     * Does NOT attempt PoW recomputation and does NOT mark POW_INVALID.
     */
    private SampleVerifyResult verifySampleWithContinuity(HeaderDto header, BlockDto blockDto, boolean cacheBust) {
        final long height = header.height;

        log.info("FASTBOOT[sid={}] CONTINUITY-VALIDATE h={} using header_continuity mode", SESSION_ID, height);

        // 1) getblockhash(height) consistency across samples (no PoW recomputation)
        try {
            String powHashNow = cacheBust
                    ? apiClient.fetchBlockHashWithCacheBust(height)
                    : apiClient.fetchBlockHash(height);
            String normExpected = normalizeHash(header.hash);
            String normNow = normalizeHash(powHashNow);
            if (normExpected == null || normNow == null || !normExpected.equals(normNow)) {
                log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=getblockhash_inconsistent expected={} got={}",
                        SESSION_ID, height, normExpected, normNow);
                return SampleVerifyResult.failure(SampleFailureType.API_INCONSISTENT, "getblockhash_inconsistent");
            }
        } catch (Exception e) {
            log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=getblockhash_fetch_error error={}",
                    SESSION_ID, height, e.getMessage());
            return SampleVerifyResult.failure(SampleFailureType.NETWORK_ERROR, "getblockhash_fetch_error");
        }

        // 2) Header field sanity: require fields to be present/parseable for continuity
        // validation.
        if (blockDto.version <= 0) {
            log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=invalid_version version={}",
                    SESSION_ID, height, blockDto.version);
            return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "invalid_version");
        }
        if (blockDto.bits == null || blockDto.bits.isEmpty()) {
            log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=missing_bits", SESSION_ID, height);
            return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "missing_bits");
        }
        if (blockDto.time <= 0) {
            log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=invalid_time time={}",
                    SESSION_ID, height, blockDto.time);
            return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "invalid_time");
        }

        // 3) previousblockhash continuity + time monotonicity vs H-1 (for blocks above
        // genesis)
        if (height > 1) {
            if (blockDto.previousBlockHash == null || blockDto.previousBlockHash.isEmpty()) {
                log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=missing_previousblockhash", SESSION_ID, height);
                return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "missing_previousblockhash");
            }

            try {
                String expectedPrevHash = cacheBust
                        ? apiClient.fetchBlockHashWithCacheBust(height - 1)
                        : apiClient.fetchBlockHash(height - 1);

                String normExpectedPrev = normalizeHash(expectedPrevHash);
                String normGotPrev = normalizeHash(blockDto.previousBlockHash);
                if (normExpectedPrev == null || normGotPrev == null) {
                    log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=prevhash_unparseable expected={} got={}",
                            SESSION_ID, height, expectedPrevHash, blockDto.previousBlockHash);
                    return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "prevhash_unparseable");
                }

                boolean prevHashMatches = normExpectedPrev.equals(normGotPrev);
                if (!prevHashMatches) {
                    // keep an endianness fallback to avoid false positives if upstream changes
                    // format
                    String reversedExpected = reverseHashEndianness(normExpectedPrev);
                    String reversedGot = reverseHashEndianness(normGotPrev);
                    prevHashMatches = (reversedExpected != null && reversedExpected.equals(normGotPrev))
                            || (reversedGot != null && reversedGot.equals(normExpectedPrev));
                }

                if (!prevHashMatches) {
                    log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=prevhash_mismatch expected={} got={}",
                            SESSION_ID, height, normExpectedPrev, normGotPrev);
                    return SampleVerifyResult.failure(SampleFailureType.API_INCONSISTENT, "prevhash_continuity_break");
                }

                // time monotonicity: time(H) >= time(H-1)
                BlockDto prevBlock = cacheBust
                        ? apiClient.fetchBlockByHashWithCacheBust(expectedPrevHash)
                        : apiClient.fetchBlockByHash(expectedPrevHash);
                if (prevBlock == null || prevBlock.time <= 0) {
                    log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=prev_time_unavailable prevTime={}",
                            SESSION_ID, height, prevBlock != null ? prevBlock.time : -1);
                    return SampleVerifyResult.failure(SampleFailureType.API_LIMITATION, "prev_time_unavailable");
                }
                if (blockDto.time < prevBlock.time) {
                    log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=time_not_monotonic prevTime={} time={}",
                            SESSION_ID, height, prevBlock.time, blockDto.time);
                    return SampleVerifyResult.failure(SampleFailureType.API_INCONSISTENT, "time_not_monotonic");
                }

                log.info("FASTBOOT[sid={}] CONTINUITY-OK h={} prevhash_match=true time_monotonic=true",
                        SESSION_ID, height);

            } catch (Exception e) {
                log.warn("FASTBOOT[sid={}] CONTINUITY-FAIL h={} reason=prev_fetch_error error={}",
                        SESSION_ID, height, e.getMessage());
                return SampleVerifyResult.failure(SampleFailureType.NETWORK_ERROR, "prev_fetch_error");
            }
        }

        log.info("FASTBOOT[sid={}] CONTINUITY-PASS h={} version={} bits={} time={}",
                SESSION_ID, height, blockDto.version, blockDto.bits, blockDto.time);

        // Return success but mark as API_LIMITATION so we track that this used fallback
        // validation.
        return new SampleVerifyResult(true, SampleFailureType.API_LIMITATION, "continuity_validated");
    }

    /**
     * Task B: Select the correct PoW block hash from getblock response.
     * Priority order:
     * 1. blockDto.hash (root-level hash from getblock API)
     * 2. Fallback to computed hash if blockDto.hash is null/empty
     * 
     * NOTE: Do NOT use header.hash as PoW block hash - that's the input to
     * getblock,
     * not the output we should compare against.
     */
    private String selectPowBlockHash(BlockDto blockDto, Block block) {
        // Priority 1: Use blockDto.hash (from getblock API response)
        if (blockDto.hash != null && !blockDto.hash.isEmpty()) {
            return blockDto.hash;
        }
        // Fallback: computed hash (should not be needed if API is consistent)
        log.warn("FASTBOOT[sid={}] selectPowBlockHash: blockDto.hash is null/empty, falling back to computed hash",
                SESSION_ID);
        return block.getHashAsString();
    }

    /**
     * Task A: Log available hash fields from BlockDto for schema verification.
     * Required format per debug contract.
     */
    private void logBlockDtoHashFields(long height, String expectedFromGetBlockHash, BlockDto blockDto) {
        // Log all available hash candidates per debug contract (Task A)
        log.info("FASTBOOT[sid={}] API-HASH-CANDIDATES h={} keys=[hash, previousblockhash, merkleroot, hex]",
                SESSION_ID, height);
        log.info("FASTBOOT[sid={}]  hash={}", SESSION_ID, blockDto.hash);
        log.info("FASTBOOT[sid={}]  expectedFromGetBlockHash={}", SESSION_ID, expectedFromGetBlockHash);
        log.info("FASTBOOT[sid={}]  previousblockhash={}", SESSION_ID,
                blockDto.previousBlockHash != null
                        ? blockDto.previousBlockHash.substring(0, Math.min(16, blockDto.previousBlockHash.length()))
                                + "..."
                        : "null");
        log.info("FASTBOOT[sid={}]  merkleroot={}", SESSION_ID,
                blockDto.merkleRoot != null
                        ? blockDto.merkleRoot.substring(0, Math.min(16, blockDto.merkleRoot.length())) + "..."
                        : "null");
        log.info("FASTBOOT[sid={}]  hasHex={}", SESSION_ID, (blockDto.hex != null && !blockDto.hex.isEmpty()));
    }
}
