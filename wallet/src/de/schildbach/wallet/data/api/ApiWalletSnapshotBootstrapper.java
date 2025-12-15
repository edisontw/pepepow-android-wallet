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

import javax.annotation.Nullable;

/**
 * Imports wallet transactions/UTXOs from the explorer API after FAST_API_10POW
 * header bootstrap.
 */
public class ApiWalletSnapshotBootstrapper {
    private static final Logger log = LoggerFactory.getLogger("FAST-BOOT-TX-SNAPSHOT");
    private static final int LOOKAHEAD_RECEIVE = 32;
    private static final int LOOKAHEAD_CHANGE = 16;
    private static final int MAX_SNAPSHOT_ADDRESSES = 20;
    private static final long ADDRESS_QUERY_DELAY_MS = 100;

    private final ApiWalletClient walletClient;
    private final ApiHeaderClient headerClient;
    private final Configuration config;
    private final NetworkParameters params;

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
        log.info("FAST-BOOT: wallet snapshot start, addresses={}, apiTipHeight={}", addresses.size(), apiTipHeight);

        if (config.getLastWalletSnapshotSuccess() && config.getLastWalletSnapshotHeight() >= apiTipHeight) {
            log.info("FAST-BOOT: wallet snapshot already applied at height {}, skipping.",
                    config.getLastWalletSnapshotHeight());
            return Result.skipped(apiTipHeight, 0, wallet.getBalance(BalanceType.AVAILABLE));
        }

        Map<String, ApiTxRef> mergedRefs = new LinkedHashMap<>();
        org.bitcoinj.core.Coin apiBalance = org.bitcoinj.core.Coin.ZERO;

        try {
            int count = 0;
            for (Address address : addresses) {
                if (count >= MAX_SNAPSHOT_ADDRESSES) {
                    log.info("FAST-BOOT: Reached max snapshot address limit ({}), stopping scan.",
                            MAX_SNAPSHOT_ADDRESSES);
                    break;
                }

                // Throttle
                try {
                    Thread.sleep(ADDRESS_QUERY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                ApiAddressInfo info = walletClient.fetchAddressInfo(address.toString());
                apiBalance = apiBalance.add(info.balance);
                log.info("FAST-BOOT: addr={}, apiTxCount={}", address.toString(), info.txCount);
                for (ApiTxRef ref : info.getTransactions()) {
                    if (ref == null || ref.txId == null) {
                        continue;
                    }
                    ApiTxRef existing = mergedRefs.get(ref.txId);
                    mergedRefs.put(ref.txId, existing != null ? existing.merge(ref) : ref);
                }
                count++;
            }
        } catch (Exception e) {
            log.error("FAST-BOOT: wallet snapshot failed during address fetch: {}", e.toString());
            if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("Not Found"))) {
                log.info("FAST-BOOT: 404/Empty from wallet snapshot endpoint. Treating as empty wallet.", e);
                persistSuccess(apiTipHeight, null);
                return Result.emptyOk(apiTipHeight, wallet.getBalance(BalanceType.AVAILABLE));
            }
            markSnapshotFailure();
            return Result.failure("address-fetch-failed", apiBalance, wallet.getBalance(BalanceType.AVAILABLE), 0);
        }

        if (mergedRefs.isEmpty()) {
            log.info("FAST-BOOT: wallet snapshot found no transactions; marking snapshot as complete.");
            persistSuccess(apiTipHeight, null);
            return Result.success(0, apiBalance, wallet.getBalance(BalanceType.AVAILABLE), apiTipHeight,
                    org.dash.wallet.common.data.WalletSnapshotStatus.EMPTY_OK);
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
                            details.size());
                }
                StoredBlock storedBlock = buildStoredBlock(detail, headerCache);
                if (storedBlock == null) {
                    log.error("FAST-BOOT: Unable to construct StoredBlock for tx {} at height {}", txId,
                            detail.blockHeight);
                    markSnapshotFailure();
                    return Result.failure("header-fetch-failed", apiBalance,
                            wallet.getBalance(BalanceType.AVAILABLE), details.size());
                }
                int depth = Math.max(1, apiTipHeight - storedBlock.getHeight() + 1);

                Transaction tx = new Transaction(params, Utils.HEX.decode(detail.rawHex));
                tx.getConfidence().setSource(TransactionConfidence.Source.NETWORK);
                wallet.receiveFromBlock(tx, storedBlock, BlockChain.NewBlockType.BEST_CHAIN, relativityOffset++);
                tx.getConfidence().setDepthInBlocks(depth);
            }
        } catch (Exception e) {
            log.error("FAST-BOOT: wallet snapshot failed while importing transactions", e);
            markSnapshotFailure();
            return Result.failure("tx-import-failed", apiBalance, wallet.getBalance(BalanceType.AVAILABLE),
                    details.size());
        }

        persistSuccess(apiTipHeight, headerCache.get(apiTipHeight));
        org.bitcoinj.core.Coin walletBalance = wallet.getBalance(BalanceType.AVAILABLE);
        org.bitcoinj.core.Coin diff = walletBalance.subtract(apiBalance);
        if (diff.isNegative()) {
            diff = diff.negate();
        }
        org.bitcoinj.core.Coin tolerance = org.bitcoinj.core.Coin.valueOf(10_000); // ~0.0001
        if (diff.isGreaterThan(tolerance)) {
            log.warn("FAST-BOOT: wallet snapshot balance mismatch. wallet={} api={} diff={}",
                    walletBalance.toFriendlyString(), apiBalance.toFriendlyString(), diff.toFriendlyString());
        }
        log.info("FAST-BOOT: wallet snapshot imported {} tx, balance={}, apiBalance={}", details.size(),
                walletBalance.toFriendlyString(), apiBalance.toFriendlyString());
        return Result.success(details.size(), apiBalance, walletBalance, apiTipHeight,
                org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS);
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

    private List<Address> deriveAddresses(Wallet wallet) {
        Set<Address> addresses = new LinkedHashSet<>();
        try {
            addresses.addAll(wallet.getIssuedReceiveAddresses());
        } catch (Exception e) {
            log.warn("FAST-BOOT: Failed to list issued receive addresses, continuing with deterministic derivation.",
                    e);
        }

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
            log.warn("FAST-BOOT: Failed to get issued keys count for purpose {}, assuming 0. Error: {}", purpose,
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
                log.warn("FAST-BOOT: Failed to derive {} address at index {}: {}", purpose, i, e.toString());
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
            log.error("FAST-BOOT: Failed to fetch/build header for tx {} height {}: {}", detail.txId,
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

        private Result(boolean success, int importedTxs, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, String failureReason, int apiTipHeight,
                org.dash.wallet.common.data.WalletSnapshotStatus status) {
            this.success = success;
            this.importedTxs = importedTxs;
            this.apiBalance = apiBalance;
            this.walletBalance = walletBalance;
            this.failureReason = failureReason;
            this.apiTipHeight = apiTipHeight;
            this.status = status;
        }

        public static Result success(int importedTxs, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, int apiTipHeight,
                org.dash.wallet.common.data.WalletSnapshotStatus status) {
            return new Result(true, importedTxs, apiBalance, walletBalance, null, apiTipHeight, status);
        }

        public static Result skipped(int apiTipHeight, int importedTxs, org.bitcoinj.core.Coin walletBalance) {
            return new Result(true, importedTxs, org.bitcoinj.core.Coin.ZERO, walletBalance, "skipped", apiTipHeight,
                    org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS);
        }

        public static Result failure(String reason, org.bitcoinj.core.Coin apiBalance,
                org.bitcoinj.core.Coin walletBalance, int importedSoFar) {
            return new Result(false, importedSoFar, apiBalance, walletBalance, reason, 0,
                    org.dash.wallet.common.data.WalletSnapshotStatus.FAILED);
        }

        public static Result emptyOk(int apiTipHeight, org.bitcoinj.core.Coin walletBalance) {
            return new Result(true, 0, org.bitcoinj.core.Coin.ZERO, walletBalance, null, apiTipHeight,
                    org.dash.wallet.common.data.WalletSnapshotStatus.EMPTY_OK);
        }
    }
}
