package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.Utils;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;

/**
 * Imports wallet transactions/UTXOs from the explorer API after FAST_API_10POW
 * header bootstrap.
 */
public class ApiWalletSnapshotBootstrapper {
    private static final Logger log = LoggerFactory.getLogger("FAST-BOOT-TX-SNAPSHOT");
    private static final int LOOKAHEAD_RECEIVE = 32;
    private static final int LOOKAHEAD_CHANGE = 16;
    private static final int MAX_SNAPSHOT_ADDRESSES = 200;
    private static final long MAX_SNAPSHOT_TIME_MS = 5000;
    private static final long ADDRESS_QUERY_DELAY_MS = 10;
    // Conservative per-run scan budget (framework refinement; avoids UI gating on
    // partial scans).
    private static final int DEFAULT_BATCH_ADDRESSES = 40;
    private static final long DEFAULT_TIME_BUDGET_MS = 4000;

    private String SESSION_ID = "UNKNOWN";

    private final ApiWalletClient walletClient;
    private final ApiHeaderClient headerClient;
    private final Configuration config;
    private final NetworkParameters params;

    public void setSessionIdForLogs(String sessionId) {
        this.SESSION_ID = sessionId;
        walletClient.setSessionIdForLogs(sessionId);
        headerClient.setSessionIdForLogs(sessionId);
    }

    public ApiWalletSnapshotBootstrapper(ApiWalletClient walletClient, ApiHeaderClient headerClient,
            Configuration config, NetworkParameters params) {
        this.walletClient = walletClient;
        this.headerClient = headerClient;
        this.config = config;
        this.params = params;
    }

    public Result runWalletSnapshot(Wallet wallet, int apiTipHeight) {
        return runWalletSnapshot(wallet, apiTipHeight, deriveAddresses(wallet));
    }

    public Result runWalletSnapshot(Wallet wallet, int apiTipHeight, List<Address> addresses) {
        return runWalletSnapshot(wallet, apiTipHeight, addresses, 0, DEFAULT_BATCH_ADDRESSES, DEFAULT_TIME_BUDGET_MS);
    }

    /**
     * Cursor-based scan for wallet snapshot import.
     * Core rule: INCOMPLETE != EMPTY (caller must treat INCOMPLETE_RESUMABLE as
     * scanning/in-progress).
     */
    public Result runWalletSnapshot(Wallet wallet, int apiTipHeight, List<Address> addresses, int startIndex,
            int maxAddressesPerRun, long timeBudgetMs) {
        final int totalToScan = addresses != null ? addresses.size() : 0;
        int safeStartIndex = Math.max(0, startIndex);
        if (totalToScan > 0 && safeStartIndex >= totalToScan) {
            safeStartIndex = 0;
        }
        final int safeMaxPerRun = Math.max(1, Math.min(maxAddressesPerRun, MAX_SNAPSHOT_ADDRESSES));
        final long safeBudgetMs = Math.max(500, Math.min(timeBudgetMs, MAX_SNAPSHOT_TIME_MS));

        log.info("SNAPSHOT[sid={}] start addresses={} apiTipHeight={} startIndex={} maxPerRun={} budgetMs={}",
                SESSION_ID, totalToScan, apiTipHeight, safeStartIndex, safeMaxPerRun, safeBudgetMs);

        final long now = System.currentTimeMillis();
        final long lastSnapshotTime = config.getLastWalletSnapshotTime();
        final long elapsedSnapshotMs = now - lastSnapshotTime;
        final long TTL_MS = 5 * 60 * 1000; // 5 minutes

        // If we succeeded at this height or very recently, skip.
        // But if we have a cursor > 0, we might want to continue.
        if (config.getLastWalletSnapshotSuccess() && config.getLastWalletSnapshotHeight() >= apiTipHeight
                && elapsedSnapshotMs < TTL_MS && safeStartIndex == 0) {
            log.info("SNAPSHOT[sid={}] wallet snapshot already fresh ({}s ago), skipping.",
                    SESSION_ID, elapsedSnapshotMs / 1000);
            return Result.skipped(apiTipHeight, 0, wallet.getBalance(BalanceType.AVAILABLE));
        }

        final Map<String, ApiTxRef> mergedRefs = new LinkedHashMap<>();
        final AtomicLong apiBalanceSats = new AtomicLong(0);
        int scannedCount = 0;
        int nextCursor = safeStartIndex;
        boolean reachedTimeBudget = false;
        boolean foundAnyActivity = false;

        final ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            final long startTime = System.currentTimeMillis();
            while (nextCursor < totalToScan && scannedCount < safeMaxPerRun) {
                if (System.currentTimeMillis() - startTime > safeBudgetMs) {
                    reachedTimeBudget = true;
                    log.info(
                            "SNAPSHOT[sid={}] budget hit; scannedBatch={} nextIndex={} status=INCOMPLETE_RESUMABLE",
                            SESSION_ID, scannedCount, nextCursor);
                    break;
                }

                final int remainingBudget = safeMaxPerRun - scannedCount;
                final int batchSize = Math.min(4, Math.min(remainingBudget, totalToScan - nextCursor));
                final List<Future<ApiAddressInfo>> futures = new ArrayList<>(batchSize);
                final List<Integer> batchIndices = new ArrayList<>(batchSize);

                for (int b = 0; b < batchSize; b++) {
                    final int index = nextCursor + b;
                    final Address address = addresses.get(index);
                    batchIndices.add(index);
                    futures.add(executor.submit(() -> walletClient.fetchAddressInfo(address.toString())));
                }

                for (int b = 0; b < futures.size(); b++) {
                    final int index = batchIndices.get(b);
                    try {
                        ApiAddressInfo info = futures.get(b).get(5, TimeUnit.SECONDS);
                        if (info != null && info.balance != null) {
                            apiBalanceSats.addAndGet(info.balance.value);
                            if (info.balance.isGreaterThan(Coin.ZERO)) {
                                foundAnyActivity = true;
                            }
                        }
                        if (info != null && info.txCount > 0) {
                            foundAnyActivity = true;
                            log.info("SNAPSHOT[sid={}] foundTx addr={} txCount={} importing...",
                                    SESSION_ID, info.address != null ? info.address : addresses.get(index).toString(),
                                    info.txCount);
                        }
                        if (info != null) {
                            synchronized (mergedRefs) {
                                for (ApiTxRef ref : info.getTransactions()) {
                                    if (ref == null || ref.txId == null) {
                                        continue;
                                    }
                                    ApiTxRef existing = mergedRefs.get(ref.txId);
                                    mergedRefs.put(ref.txId, existing != null ? existing.merge(ref) : ref);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("SNAPSHOT[sid={}] batch fetch error for index {} ex={} msg={}",
                                SESSION_ID, index, e.getClass().getSimpleName(), e.getMessage());
                    } finally {
                        scannedCount++;
                        nextCursor = index + 1;
                    }
                }

                if (foundAnyActivity || !mergedRefs.isEmpty()) {
                    log.info(
                            "SNAPSHOT[sid={}] found activity early; breaking batch to report balance, status=INCOMPLETE_RESUMABLE",
                            SESSION_ID);
                    break;
                }

                if (ADDRESS_QUERY_DELAY_MS > 0) {
                    try {
                        Thread.sleep(ADDRESS_QUERY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("SNAPSHOT[sid={}] sleepInterrupted ex={} msg={}",
                                SESSION_ID, e.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.error("SNAPSHOT[sid={}] wallet snapshot failed during address fetch: {}", SESSION_ID, e.toString());
            if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("Not Found"))) {
                log.info("SNAPSHOT[sid={}] 404/Empty from wallet snapshot endpoint. Treating as empty wallet.",
                        SESSION_ID, e);
                persistSuccess(apiTipHeight, null);
                return Result.emptyOk(apiTipHeight, wallet.getBalance(BalanceType.AVAILABLE));
            }
            markSnapshotFailure();
            return Result.failure("address-fetch-failed", Coin.valueOf(apiBalanceSats.get()),
                    wallet.getBalance(BalanceType.AVAILABLE), 0, nextCursor, scannedCount, totalToScan,
                    reachedTimeBudget);
        } finally {
            executor.shutdownNow();
        }

        final Coin apiBalance = Coin.valueOf(apiBalanceSats.get());

        if (mergedRefs.isEmpty()) {
            if (nextCursor < totalToScan) {
                log.info(
                        "FAST-BOOT-TX-SNAPSHOT: wallet snapshot incomplete (limit/budget); scannedCount={}, nextCursor={}",
                        scannedCount, nextCursor);
                // Do NOT mark as success yet, so it can resume.
                return Result.success(0, apiBalance, wallet.getBalance(BalanceType.AVAILABLE), apiTipHeight,
                        org.dash.wallet.common.data.WalletSnapshotStatus.INCOMPLETE_RESUMABLE,
                        nextCursor, scannedCount, totalToScan, reachedTimeBudget);
            } else {
                log.info(
                        "FAST-BOOT-TX-SNAPSHOT: wallet snapshot found no transactions after full scan; marking snapshot as complete.");
                persistSuccess(apiTipHeight, null);
                return Result.success(0, apiBalance, wallet.getBalance(BalanceType.AVAILABLE), apiTipHeight,
                        org.dash.wallet.common.data.WalletSnapshotStatus.EMPTY_OK,
                        0, scannedCount, totalToScan, reachedTimeBudget);
            }
        }

        Map<String, ApiTxDetail> details = new LinkedHashMap<>();
        Map<Integer, StoredBlock> headerCache = new LinkedHashMap<>();
        int relativityOffset = 0;

        try {
            for (Map.Entry<String, ApiTxRef> refEntry : mergedRefs.entrySet()) {
                String txId = refEntry.getKey();
                ApiTxRef ref = refEntry.getValue();
                ApiTxDetail detail = walletClient.fetchTransactionDetail(txId);
                int blockHeight = detail.blockHeight > 0 ? detail.blockHeight : ref.blockHeight;
                long blockTime = detail.blockTimeSeconds > 0 ? detail.blockTimeSeconds : ref.blockTimeSeconds;
                details.put(txId,
                        new ApiTxDetail(detail.txId, detail.rawHex, blockHeight, blockTime, detail.blockHash));
            }

            for (Map.Entry<String, ApiTxDetail> entry : details.entrySet()) {
                String txId = entry.getKey();
                ApiTxDetail detail = entry.getValue();
                if (detail.blockHeight <= 0) {
                    log.error("FAST-BOOT: Missing block height for tx {}. Aborting snapshot.", txId);
                    markSnapshotFailure();
                    return Result.failure("missing-height", apiBalance, wallet.getBalance(BalanceType.AVAILABLE),
                            details.size(), nextCursor, scannedCount, totalToScan, reachedTimeBudget);
                }
                StoredBlock storedBlock = buildStoredBlock(detail, headerCache);
                if (storedBlock == null) {
                    log.error("FAST-BOOT: Unable to construct StoredBlock for tx {} at height {}", txId,
                            detail.blockHeight);
                    markSnapshotFailure();
                    return Result.failure("header-fetch-failed", apiBalance,
                            wallet.getBalance(BalanceType.AVAILABLE), details.size(),
                            nextCursor, scannedCount, totalToScan, reachedTimeBudget);
                }
                int depth = Math.max(1, apiTipHeight - storedBlock.getHeight() + 1);

                Transaction tx = new Transaction(params, org.bitcoinj.core.Utils.HEX.decode(detail.rawHex));
                tx.getConfidence().setSource(TransactionConfidence.Source.NETWORK);
                wallet.receiveFromBlock(tx, storedBlock, BlockChain.NewBlockType.BEST_CHAIN, relativityOffset++);
                tx.getConfidence().setDepthInBlocks(depth);
            }
        } catch (Exception e) {
            log.error("FAST-BOOT: wallet snapshot failed while importing transactions", e);
            markSnapshotFailure();
            return Result.failure("tx-import-failed", apiBalance, wallet.getBalance(BalanceType.AVAILABLE),
                    details.size(), nextCursor, scannedCount, totalToScan, reachedTimeBudget);
        }

        persistSuccess(apiTipHeight, headerCache.get(apiTipHeight));
        Coin walletBalance = wallet.getBalance(BalanceType.AVAILABLE);
        Coin diff = walletBalance.subtract(apiBalance);
        if (diff.isNegative()) {
            diff = diff.negate();
        }
        Coin tolerance = Coin.valueOf(10_000); // ~0.0001
        if (diff.isGreaterThan(tolerance)) {
            log.warn("FAST-BOOT-TX-SNAPSHOT: wallet snapshot balance mismatch. wallet={} api={} diff={}",
                    walletBalance.toFriendlyString(), apiBalance.toFriendlyString(), diff.toFriendlyString());
        }
        log.info("FAST-BOOT: wallet snapshot result imported={} walletBalance={} apiBalance={} status={}",
                details.size(), walletBalance.toFriendlyString(), apiBalance.toFriendlyString(),
                nextCursor < totalToScan ? "INCOMPLETE_RESUMABLE" : "SUCCESS");

        final org.dash.wallet.common.data.WalletSnapshotStatus finalStatus = (nextCursor < totalToScan)
                ? org.dash.wallet.common.data.WalletSnapshotStatus.INCOMPLETE_RESUMABLE
                : org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS;

        return Result.success(details.size(), apiBalance, walletBalance, apiTipHeight,
                finalStatus,
                (finalStatus == org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS) ? 0 : nextCursor,
                scannedCount, totalToScan, reachedTimeBudget);
    }

    private void persistSuccess(int apiTipHeight, @Nullable StoredBlock apiTip) {
        config.setLastWalletSnapshotSuccess(true);
        config.setLastWalletSnapshotHeight(apiTipHeight);
        if (apiTip != null) {
            config.setLastWalletSnapshotHash(apiTip.getHeader().getHashAsString());
            config.setLastWalletSnapshotTime(apiTip.getHeader().getTimeSeconds());
        } else {
            config.setLastWalletSnapshotHash(null);
            config.setLastWalletSnapshotTime(System.currentTimeMillis() / 1000);
        }
    }

    private void markSnapshotFailure() {
        config.setLastWalletSnapshotSuccess(false);
        config.setLastWalletSnapshotHeight(0);
        config.setLastWalletSnapshotHash(null);
        config.setLastWalletSnapshotTime(System.currentTimeMillis() / 1000);
    }

    public List<Address> deriveAddresses(Wallet wallet) {
        Set<Address> addresses = new LinkedHashSet<>();

        // Priority 1: Current receive address
        try {
            addresses.add(wallet.currentReceiveAddress());
        } catch (Exception e) {
            log.warn("SNAPSHOT[sid={}] Failed to get current receive address", SESSION_ID, e);
        }

        // Priority 2: Recently issued receive addresses (most recent first)
        try {
            List<Address> issued = new ArrayList<>(wallet.getIssuedReceiveAddresses());
            java.util.Collections.reverse(issued);
            addresses.addAll(issued);
        } catch (Exception e) {
            log.warn("SNAPSHOT[sid={}] Failed to list issued receive addresses", SESSION_ID, e);
        }

        // Priority 3 & 4: Derived addresses for both purposes
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            addresses.addAll(deriveForPurpose(chain, KeyChain.KeyPurpose.RECEIVE_FUNDS, LOOKAHEAD_RECEIVE));
            addresses.addAll(deriveForPurpose(chain, KeyChain.KeyPurpose.CHANGE, LOOKAHEAD_CHANGE));
        }
        return new ArrayList<>(addresses);
    }

    private List<Address> deriveForPurpose(DeterministicKeyChain chain, KeyChain.KeyPurpose purpose, int lookahead) {
        List<Address> results = new ArrayList<>();
        int issued;
        try {
            issued = purpose == KeyChain.KeyPurpose.RECEIVE_FUNDS ? chain.getIssuedExternalKeys()
                    : chain.getIssuedInternalKeys();
        } catch (Exception e) {
            log.warn("FASTBOOT[sid={}] Failed to get issued keys count for purpose {}, assuming 0. Error: {}",
                    SESSION_ID, purpose,
                    e.toString());
            issued = 0;
        }

        // Safety cap on lookahead to prevent hangs
        int safeLookahead = Math.min(lookahead, 100);
        int target = issued + safeLookahead;

        for (int i = 0; i < target; i++) {
            List<org.bitcoinj.crypto.ChildNumber> subPath = purpose == KeyChain.KeyPurpose.RECEIVE_FUNDS
                    ? DeterministicKeyChain.EXTERNAL_SUBPATH
                    : DeterministicKeyChain.INTERNAL_SUBPATH;

            try {
                com.google.common.collect.ImmutableList<org.bitcoinj.crypto.ChildNumber> accountAndBranch = org.bitcoinj.crypto.HDUtils
                        .concat(chain.getAccountPath(), subPath);
                com.google.common.collect.ImmutableList<org.bitcoinj.crypto.ChildNumber> path = org.bitcoinj.crypto.HDUtils
                        .append(accountAndBranch, new org.bitcoinj.crypto.ChildNumber(i));

                org.bitcoinj.crypto.DeterministicKey key = chain.getKeyByPath(path, false);
                Address address = Address.fromKey(params, key, chain.getOutputScriptType());
                results.add(address);
            } catch (Exception e) {
                log.warn("FASTBOOT[sid={}] Failed to derive {} address at index {}: {}", SESSION_ID, purpose, i,
                        e.toString());
                break;
            }
        }
        return results;
    }

    @Nullable
    private StoredBlock buildStoredBlock(ApiTxDetail detail, Map<Integer, StoredBlock> cache) {
        try {
            HeaderDto header = detail.blockHash != null ? headerClient.fetchHeaderByHash(detail.blockHash)
                    : headerClient.fetchHeaderAtHeight(detail.blockHeight);
            if (header == null) {
                return null;
            }
            if (header.height == 0) {
                header.height = detail.blockHeight;
            }
            StoredBlock prev = cache.get((int) header.height - 1);
            Block block = header.toBlock(params);
            StoredBlock stored = new StoredBlock(block, header.toChainWork(params, prev), (int) header.height);
            cache.put(stored.getHeight(), stored);
            return stored;
        } catch (Exception e) {
            log.error("FASTBOOT[sid={}] Failed to fetch/build header for tx {} height {}: {}", SESSION_ID, detail.txId,
                    detail.blockHeight,
                    e.toString());
            return null;
        }
    }

    public static class Result {
        public final boolean success;
        public final int importedTxs;
        public final org.bitcoinj.core.Coin apiBalance;
        public final org.bitcoinj.core.Coin walletBalance;
        public final String failureReason;
        public final int apiTipHeight;
        public final org.dash.wallet.common.data.WalletSnapshotStatus status;
        // Cursor/progress for resumable scans (session-only; caller owns persistence).
        public final int nextCursor;
        public final int scannedAddresses;
        public final int totalAddresses;
        public final boolean timeBudgetHit;

        private Result(boolean success, int importedTxs, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, String failureReason, int apiTipHeight,
                org.dash.wallet.common.data.WalletSnapshotStatus status,
                int nextCursor, int scannedAddresses, int totalAddresses, boolean timeBudgetHit) {
            this.success = success;
            this.importedTxs = importedTxs;
            this.apiBalance = apiBalance;
            this.walletBalance = walletBalance;
            this.failureReason = failureReason;
            this.apiTipHeight = apiTipHeight;
            this.status = status;
            this.nextCursor = nextCursor;
            this.scannedAddresses = scannedAddresses;
            this.totalAddresses = totalAddresses;
            this.timeBudgetHit = timeBudgetHit;
        }

        public static Result success(int importedTxs, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, int apiTipHeight,
                org.dash.wallet.common.data.WalletSnapshotStatus status) {
            return new Result(true, importedTxs, apiBalance, walletBalance, null, apiTipHeight, status,
                    0, 0, 0, false);
        }

        public static Result success(int importedTxs, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, int apiTipHeight,
                org.dash.wallet.common.data.WalletSnapshotStatus status,
                int nextCursor, int scannedAddresses, int totalAddresses, boolean timeBudgetHit) {
            return new Result(true, importedTxs, apiBalance, walletBalance, null, apiTipHeight, status,
                    nextCursor, scannedAddresses, totalAddresses, timeBudgetHit);
        }

        public static Result skipped(int apiTipHeight, int importedTxs, org.bitcoinj.core.Coin walletBalance) {
            return new Result(true, importedTxs, org.bitcoinj.core.Coin.ZERO, walletBalance, "skipped", apiTipHeight,
                    org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS,
                    0, 0, 0, false);
        }

        public static Result failure(String reason, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, int importedSoFar) {
            return new Result(false, importedSoFar, apiBalance, walletBalance, reason, 0,
                    org.dash.wallet.common.data.WalletSnapshotStatus.FAILED,
                    0, 0, 0, false);
        }

        public static Result failure(String reason, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, int importedSoFar,
                int nextCursor, int scannedAddresses, int totalAddresses, boolean timeBudgetHit) {
            return new Result(false, importedSoFar, apiBalance, walletBalance, reason, 0,
                    org.dash.wallet.common.data.WalletSnapshotStatus.FAILED,
                    nextCursor, scannedAddresses, totalAddresses, timeBudgetHit);
        }

        public static Result emptyOk(int apiTipHeight, org.bitcoinj.core.Coin walletBalance) {
            return new Result(true, 0, org.bitcoinj.core.Coin.ZERO, walletBalance, null, apiTipHeight,
                    org.dash.wallet.common.data.WalletSnapshotStatus.EMPTY_OK,
                    0, 0, 0, false);
        }
    }
}
