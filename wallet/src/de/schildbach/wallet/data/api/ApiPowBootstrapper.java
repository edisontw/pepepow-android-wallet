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

    private static final int HEADER_WINDOW = 30;
    private static final int POW_SAMPLE_COUNT = 10;

    private final ApiHeaderClient apiClient;
    private final PowVerifier powVerifier;
    private final NetworkParameters params;
    private final Context context;

    public ApiPowBootstrapper(Context context, ApiHeaderClient apiClient, PowVerifier powVerifier,
            NetworkParameters params) {
        this.context = context;
        this.apiClient = apiClient;
        this.powVerifier = powVerifier;
        this.params = params;
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

        private BootstrapResult(boolean success, int spvHeadHeight, int explorerTipHeight,
                String failureReason,
                int chainHeadHeight, @Nullable Sha256Hash chainHeadHash, long chainHeadTimeSeconds) {
            this.success = success;
            this.spvHeadHeight = spvHeadHeight;
            this.explorerTipHeight = explorerTipHeight;
            this.failureReason = failureReason;
            this.chainHeadHeight = chainHeadHeight;
            this.chainHeadHash = chainHeadHash;
            this.chainHeadTimeSeconds = chainHeadTimeSeconds;
        }

        public static BootstrapResult failure(String reason, int spvHeadHeight, int explorerTipHeight,
                long chainHeadTimeSeconds) {
            return new BootstrapResult(false, spvHeadHeight, explorerTipHeight, reason, 0, null,
                    chainHeadTimeSeconds);
        }

        public static BootstrapResult skipped(int localHeight, int apiTipHeight, long chainHeadTimeSeconds) {
            return new BootstrapResult(false, localHeight, apiTipHeight, "skipped", localHeight, null,
                    chainHeadTimeSeconds);
        }

        public static BootstrapResult success(int spvHeadHeight, int explorerTipHeight, int chainHeadHeight,
                @Nullable Sha256Hash chainHeadHash, long chainHeadTimeSeconds) {
            return new BootstrapResult(true, spvHeadHeight, explorerTipHeight, null, chainHeadHeight,
                    chainHeadHash,
                    chainHeadTimeSeconds);
        }

        @Override
        public String toString() {
            return "BootstrapResult{success=" + success + ", spvHead=" + spvHeadHeight +
                    ", apiTip=" + explorerTipHeight + ", chainHeadHeight=" + chainHeadHeight +
                    ", chainHeadHash=" + (chainHeadHash != null ? chainHeadHash.toString() : "null") +
                    ", reason=" + failureReason + ", chainHeadTimeSeconds=" + chainHeadTimeSeconds + "}";
        }
    }

    public BootstrapResult runBootstrapIfNeeded(NetworkParameters params, SyncMode mode) {
        Log.i(TAG, "[FAST-BOOT][API-SNAPSHOT] starting bootstrap (mode=" + mode + ")");
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

            Log.i(TAG, "FAST-BOOT: Fetching header window " + startHeight + "-" + apiTipHeight);
            List<HeaderDto> window = fetchHeaderWindow(startHeight, apiTipHeight);

            if (window == null || window.isEmpty()) {
                return BootstrapResult.failure("window-fetch-failed", localSpvHeight, apiTipHeight, 0);
            }
            if (window.size() < (Math.min(windowSize, apiTipHeight))) {
                // partial window might be okay if very early in chain, but unlikely for
                // production
                Log.w(TAG, "FAST-BOOT: fetched partial window size=" + window.size());
            }

            if (!isContiguous(window, startHeight)) {
                return BootstrapResult.failure("window-not-contiguous", localSpvHeight, apiTipHeight, 0);
            }

            // Capture tip header for soft-fail fallback
            apiTipHeader = window.get(window.size() - 1);
            apiTipTime = apiTipHeader.time;

            // Pick samples
            List<HeaderDto> samples = pickPowSamples(window, POW_SAMPLE_COUNT);
            Log.i(TAG, "FAST-BOOT: Verifying " + samples.size() + " PoW samples...");

            List<Block> blocks = fetchPowBlocks(samples, activeParams);

            for (Block block : blocks) {
                // Verify PoW is valid for the block's difficulty target
                try {
                    block.verifyHeader();
                } catch (VerificationException e) {
                    Log.w(TAG, "FAST-BOOT: FAILED PoW check for block " + block.getHashAsString());
                    throw e;
                }
            }
            Log.i(TAG, "FAST-BOOT: PoW verification PASSED.");

            // Success - Validate Tip but DO NOT PERSIST to store aggressively yet (Overlay
            // logic)
            // apiTipHeader is already set above

            Log.i(TAG, "[API-SNAPSHOT] API tip height=" + apiTipHeight + " hash=" + apiTipHeader.hash);

            // Create StoredBlock
            StoredBlock apiTipStored = createStoredBlockOnly(apiTipHeader, activeParams);

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean("bootstrap_done", true).apply();

            // Overlay-only persistence: record API tip + local offset, but never mutate SPV
            // core.
            saveBootstrapSuccess(localSpvHeight, apiTipHeight);
            updateOffset(apiTipHeight, localSpvHeight);

            return BootstrapResult.success(localSpvHeight, apiTipHeight, apiTipHeight,
                    apiTipStored.getHeader().getHash(), apiTipStored.getHeader().getTimeSeconds());

        } catch (VerificationException e) {
            Log.w(TAG, "[FAST-BOOT] PoW verification failed: " + e.getMessage());
            return BootstrapResult.failure("pow-failed", localSpvHeight, apiTipHeight, apiTipTime);
        } catch (Exception e) {
            Log.e(TAG, "[FAST-BOOT] Exception", e);
            // FAIL HARD: Any exception during window fetch, sample fetch, or verification
            // is a hard failure.
            return BootstrapResult.failure("exception-" + e.getMessage(), localSpvHeight, apiTipHeight, apiTipTime);
        }
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
                Log.w(TAG, "FAST-BOOT ULTRA: Height mismatch at index " + i + " expected=" + expectedHeight
                        + " got=" + current.height);
                return false;
            }
            if (i == 0) {
                continue;
            }
            HeaderDto previous = headers.get(i - 1);
            if (current.previousBlockHash == null || previous.hash == null
                    || !current.previousBlockHash.equals(previous.hash)) {
                Log.w(TAG, "FAST-BOOT ULTRA: Prev-hash mismatch at height " + current.height);
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
            if (!block.getHashAsString().equals(header.hash)) {
                throw new VerificationException("Block hash mismatch at height " + header.height);
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
                    "FAST-BOOT ULTRA: failed to fetch tip header at height " + apiTipHeight + ": " + e.getMessage());
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
}
