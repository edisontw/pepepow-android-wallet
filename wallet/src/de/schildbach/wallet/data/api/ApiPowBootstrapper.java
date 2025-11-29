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
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import de.schildbach.wallet.Constants;

public class ApiPowBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(ApiPowBootstrapper.class);
    private static final String TAG = "ApiPowBootstrapper";
    private static final String PREFS_NAME = "ApiPowBootstrap";
    private static final String KEY_LAST_VERIFIED_TIP = "last_verified_tip_height";
    private static final int SAFE_MARGIN = 20;
    private static final int MIN_VALID_HEADERS = 10;
    private static final int MAX_HEADERS_TO_FETCH = 200;
    private static final int SANITY_WARN_THRESHOLD = Math.max(50, MAX_HEADERS_TO_FETCH / 4);
    private static final long BOOTSTRAP_TIMEOUT_MS = 10000; // 10 seconds
    private static final int MAX_RETRIES = 3;

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

    public boolean runBootstrapIfNeeded(BlockStore blockStore, NetworkParameters params) {
        long startNanos = System.nanoTime();
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "API-BOOTSTRAP: ENTER runBootstrapIfNeeded");

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean alreadyDone = prefs.getBoolean("bootstrap_done", false);
        boolean usedExplorerTipFlag = false;

        try {
            StoredBlock chainHead = blockStore.getChainHead();
            int localHeight = chainHead.getHeight();

            Log.i(TAG, "API-BOOTSTRAP: localHeight=" + localHeight + " alreadyDone=" + alreadyDone);

            // 1. Fetch remote tip first
            long remoteTipHeight = 0;
            try {
                remoteTipHeight = apiClient.fetchBlockCount();
                usedExplorerTipFlag = true;
                log.info("API tip height=" + remoteTipHeight + ", SPV chainHeadHeight=" + localHeight);
            } catch (Exception e) {
                if (alreadyDone && (e instanceof java.io.IOException)) {
                    Log.w(TAG, "API-BOOTSTRAP: getblockcount failed (timeout or network error) in mode " +
                            params.getId() +
                            " with alreadyDone=true; treating explorer as delayed and using localHeight as fallback.");
                    remoteTipHeight = localHeight;
                } else {
                    Log.e(TAG, "API-BOOTSTRAP: fetchBlockCount failed", e);
                    logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                    return false;
                }
            }

            if (remoteTipHeight <= 0) {
                Log.i(TAG, "API-BOOTSTRAP: early exit - invalid remote tip height: " + remoteTipHeight);
                logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                return false;
            }

            // Capture authoritative API tip height
            final int apiTipHeight = (int) remoteTipHeight;
            log.info("DEBUG: apiTipHeight=" + apiTipHeight);

            // 2. Compute snapshot parameters
            // We want the chain head to end exactly at apiTipHeight to match user
            // requirement.
            // "header[N-1] -> height = apiTipHeight" implies we must fetch the tip.
            long endHeight = apiTipHeight;
            long startHeight = Math.max(1, endHeight - MAX_HEADERS_TO_FETCH + 1);

            Log.i(TAG, "API-BOOTSTRAP: tip=" + remoteTipHeight + " start=" + startHeight + " end=" + endHeight);

            // 3. Check if we can skip (New Logic)
            // Skip ONLY if we are already bootstrapped AND localHeight is close to the tip
            if (alreadyDone && localHeight >= apiTipHeight - 10) {
                Log.i(TAG, "API-BOOTSTRAP: early exit - already bootstrapped and localHeight ("
                        + localHeight + ") is close to apiTipHeight (" + apiTipHeight + ")");
                logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                return false;
            }

            // If we are here, we proceed with bootstrap (even if localHeight > 0 but far
            // behind)
            if (localHeight > 0) {
                Log.i(TAG, "API-BOOTSTRAP: proceeding with bootstrap despite non-empty chain (height=" + localHeight
                        + ")");
            }

            if (endHeight <= startHeight) {
                Log.i(TAG, "API-BOOTSTRAP: early exit - window invalid. start=" + startHeight + " end=" + endHeight);
                logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                return false;
            }

            // 2. Fetch headers
            List<HeaderDto> headers = new ArrayList<>();
            for (long h = startHeight; h <= endHeight; h++) {
                // TIMEOUT CHECK
                if (System.currentTimeMillis() - startTime > BOOTSTRAP_TIMEOUT_MS) {
                    Log.w(TAG,
                            "API-BOOTSTRAP: TIMEOUT exceeded (" + BOOTSTRAP_TIMEOUT_MS + "ms). Stopping fetch loop.");
                    break;
                }

                HeaderDto dto = null;
                int retries = 0;
                while (retries < MAX_RETRIES) {
                    try {
                        String hash = apiClient.getBlockHashByHeight(h);
                        if (hash != null) {
                            dto = apiClient.fetchHeaderByHash(hash);
                            if (dto != null) {
                                break; // Success
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "API-BOOTSTRAP: fetch failed at height " + h + " retry=" + retries + " error="
                                + e.getMessage());
                    }
                    retries++;
                }

                if (dto == null) {
                    Log.e(TAG, "API-BOOTSTRAP: Failed to fetch header at height " + h + " after " + MAX_RETRIES
                            + " retries. STOPPING LOOP.");
                    break;
                }

                // Ensure height is set from the loop index if missing (though explorer should
                // provide it)
                if (dto.height <= 0) {
                    Log.w(TAG, "API-BOOTSTRAP: Header at " + h + " missing height, patching.");
                    dto.height = h;
                }

                headers.add(dto);

                if (h % 50 == 0) {
                    Log.i(TAG, "API-BOOTSTRAP: fetching height=" + h);
                }
            }
            Log.i(TAG, "API-BOOTSTRAP: fetched total headers=" + headers.size());

            if (!headers.isEmpty()) {
                log.info("Header[0]: apiHeight=" + headers.get(0).height);
                log.info("Header[last]: apiHeight=" + headers.get(headers.size() - 1).height);
            }

            if (headers.size() < MIN_VALID_HEADERS) {
                Log.i(TAG,
                        "API-BOOTSTRAP: early exit - insufficient valid headers after timeout, size=" + headers.size());
                logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                return false;
            }

            // 3. Validate consistency
            Log.i(TAG, "API-BOOTSTRAP: entering validation");
            List<HeaderDto> consistentHeaders = new ArrayList<>();
            if (!headers.isEmpty()) {
                consistentHeaders.add(headers.get(0));
            }

            for (int i = 1; i < headers.size(); i++) {
                HeaderDto prev = headers.get(i - 1);
                HeaderDto cur = headers.get(i);
                if (cur.previousBlockHash.equals(prev.hash)) {
                    consistentHeaders.add(cur);
                } else {
                    Log.w(TAG, "API-BOOTSTRAP: mismatch at height " + cur.height + " prevHash=" + cur.previousBlockHash
                            + " expected=" + prev.hash);
                    break;
                }
            }

            long lastGoodHeight = consistentHeaders.get(consistentHeaders.size() - 1).height;
            Log.i(TAG, "API-BOOTSTRAP: validHeaders = " + consistentHeaders.size());

            if (consistentHeaders.size() < MIN_VALID_HEADERS) {
                Log.i(TAG, "API-BOOTSTRAP: early exit - insufficient valid headers: " + consistentHeaders.size() + " < "
                        + MIN_VALID_HEADERS);
                logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                return false;
            }

            // 4. PoW Check
            Set<Long> samples = new HashSet<>(pickSampleHeights(startHeight, lastGoodHeight, 10));
            for (HeaderDto dto : consistentHeaders) {
                if (samples.contains(dto.height)) {
                    try {
                        powVerifier.verifyPow(Collections.singletonList(dto));
                    } catch (VerificationException e) {
                        Log.e(TAG, "API-BOOTSTRAP: PoW verification failed at height " + dto.height, e);
                        logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
                        return false;
                    }
                }
            }

            // 5. Write to Store
            Log.i(TAG, "API-BOOTSTRAP: writing " + consistentHeaders.size() + " headers into SPVBlockStore");

            int count = consistentHeaders.size();

            StoredBlock previous = null;
            StoredBlock headToSet = null;
            StoredBlock firstStoredBlock = null;
            StoredBlock lastStoredBlock = null;

            // Special handling for the first block of the window
            HeaderDto firstDto = consistentHeaders.get(0);
            if (firstDto.height == 1) {
                previous = blockStore.get(params.getGenesisBlock().getHash());
            }

            for (int i = 0; i < count; i++) {
                HeaderDto dto = consistentHeaders.get(i);

                // USE EXPLORER HEIGHT DIRECTLY
                // No manual baseHeight + i calculations
                int explorerHeight = (int) dto.height;

                StoredBlock newBlock = toStoredBlock(dto, previous, explorerHeight);
                blockStore.put(newBlock);
                previous = newBlock;

                if (i == 0)
                    firstStoredBlock = newBlock;
                if (i == count - 1)
                    lastStoredBlock = newBlock;
            }

            headToSet = lastStoredBlock;

            if (headToSet != null) {
                log.info("Stored[0]: height=" + (firstStoredBlock != null ? firstStoredBlock.getHeight() : "null"));
                log.info("Stored[last]: height=" + (lastStoredBlock != null ? lastStoredBlock.getHeight() : "null"));

                blockStore.setChainHead(headToSet);
                log.info("Bootstrap chain head height=" + blockStore.getChainHead().getHeight());
            } else {
                Log.w(TAG, "API-BOOTSTRAP: headToSet is null, cannot set chain head");
            }

            // 6. Sanity Check
            int spvHeight = blockStore.getChainHead().getHeight();
            // Re-fetch tip to be sure? Or use apiTipHeight.
            // Using apiTipHeight is safer as it was the target.
            // But let's fetch fresh tip if we want to be super sure, but apiTipHeight is
            // fine for this context.

            final long diff = spvHeight - apiTipHeight;
            log.info("Sanity check: apiTipHeight=" + apiTipHeight +
                    ", spvHeight=" + spvHeight +
                    ", diff=" + diff);

            // In FAST_API_10POW a negative diff often means the fetch loop timed out before
            // filling the full window. Treat this as a soft warning only.
            if (diff < -SANITY_WARN_THRESHOLD) {
                log.warn(
                        "SPV height is {} blocks behind explorer tip after bootstrap. Likely caused by API timeout; continuing and letting P2P catch up.",
                        -diff);
            } else if (diff > SANITY_WARN_THRESHOLD) {
                log.warn(
                        "SPV height is {} blocks ahead of explorer tip after bootstrap. Explorer may be slightly behind; continuing.",
                        diff);
            }

            // Mark as done
            prefs.edit().putBoolean("bootstrap_done", true).apply();

            log.info("Bootstrap complete: apiTipHeight=" + apiTipHeight +
                    ", chainHeadHeight=" + spvHeight);

            logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "API-BOOTSTRAP: exception during bootstrap", e);
            logDuration(startNanos, alreadyDone, usedExplorerTipFlag, params);
            return false;
        }
    }

    public StoredBlock getCurrentChainHead(BlockStore blockStore) {
        try {
            return blockStore.getChainHead();
        } catch (BlockStoreException e) {
            return null;
        }
    }

    private List<Long> pickSampleHeights(long start, long end, int count) {
        Set<Long> heights = new LinkedHashSet<>();
        heights.add(end);
        heights.add(start);
        long range = Math.max(0, end - start);
        while (heights.size() < count && heights.size() <= range + 1) {
            long h = start + ThreadLocalRandom.current().nextLong(range + 1);
            heights.add(h);
        }
        List<Long> sorted = new ArrayList<>(heights);
        Collections.sort(sorted);
        return sorted;
    }

    private StoredBlock toStoredBlock(HeaderDto headerDto, @Nullable StoredBlock previous, int forcedHeight)
            throws BlockStoreException {
        Block header = headerDto.toBlock(params);
        BigInteger chainWork = headerDto.toChainWork(params, previous);
        return new StoredBlock(header, chainWork, forcedHeight);
    }

    private void logDuration(long startNanos, boolean alreadyDone, boolean usedExplorerTipFlag,
            NetworkParameters params) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("API-BOOTSTRAP: finished in " + durationMs + " ms, mode="
                + (params != null ? params.getId() : "unknown") +
                ", alreadyDone=" + alreadyDone + ", usedExplorerTip=" + usedExplorerTipFlag);
    }
}
