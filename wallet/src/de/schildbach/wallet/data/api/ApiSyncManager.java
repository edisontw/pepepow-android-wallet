package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Address;
import org.dash.wallet.common.data.SyncMode;

public class ApiSyncManager {

    private static final Logger log = LoggerFactory.getLogger(ApiSyncManager.class);

    private final ApiHeaderClient apiClient;
    private final HeaderVerifier headerVerifier;
    private final PowVerifier powVerifier;
    private final BlockStore blockStore;
    private final NetworkParameters params;

    public static class FastSyncCheckpointResult {
        public final long tipHeight;
        public final long checkpointHeight;
        public final StoredBlock checkpoint;
        public final byte[] checkpointBytes;
        public FastSyncCheckpointResult(long tipHeight, long checkpointHeight, StoredBlock checkpoint,
                byte[] checkpointBytes) {
            this.tipHeight = tipHeight;
            this.checkpointHeight = checkpointHeight;
            this.checkpoint = checkpoint;
            this.checkpointBytes = checkpointBytes;
        }
    }

    public ApiSyncManager(ApiHeaderClient apiClient, HeaderVerifier headerVerifier, PowVerifier powVerifier,
            BlockStore blockStore, NetworkParameters params) {
        this.apiClient = apiClient;
        this.headerVerifier = headerVerifier;
        this.powVerifier = powVerifier;
        this.blockStore = blockStore;
        this.params = params;
    }

    /**
     * FAST_API_10POW sync: Basic header verification with light PoW checks.
     * This is the experimental fast sync mode.
     */
    public FastSyncCheckpointResult prepareFastSyncCheckpoint()
            throws IOException, VerificationException, BlockStoreException, ApiSyncException {
        log.info("=== Starting FAST_API_10POW bootstrap: sampling PoW + building checkpoint ===");
        long tipHeight = apiClient.fetchBlockCount();
        long windowStart = Math.max(1, tipHeight - 1000);
        long checkpointHeight = windowStart;
        List<Long> sampleHeights = pickSampleHeights(windowStart, tipHeight, 10);
        log.info("Selected {} PoW sample heights in range [{}, {}]: {}", sampleHeights.size(), windowStart, tipHeight,
                sampleHeights);

        List<HeaderDto> powSamples = new ArrayList<>();
        for (long h : sampleHeights) {
            HeaderDto header = apiClient.fetchHeaderAtHeight(h);
            if (header.height <= 0) {
                header.height = h;
            }
            powSamples.add(header);
        }

        powVerifier.verifyPow(powSamples);

        HeaderDto checkpointDto = apiClient.fetchHeaderAtHeight(checkpointHeight);
        if (checkpointDto.height <= 0) {
            checkpointDto.height = checkpointHeight;
        }

        StoredBlock checkpoint = toStoredBlock(checkpointDto, null);
        byte[] checkpointBytes = serializeCheckpoint(checkpoint);

        // Keep a minimal chain head in the temporary BlockStore so existing status
        // reporting continues to work before SPV kicks in.
        blockStore.put(checkpoint);
        blockStore.setChainHead(checkpoint);

        log.info("FAST_API_10POW bootstrap completed. Checkpoint at height {} hash {}", checkpoint.getHeight(),
                checkpoint.getHeader().getHashAsString());
        return new FastSyncCheckpointResult(tipHeight, checkpointHeight, checkpoint, checkpointBytes);
    }

    /**
     * API_1000POW sync: Fetch the last 1000 headers, verify chain structure + DGW
     * difficulty,
     * sample 10 headers for PoW, then insert into BlockStore as a runtime
     * checkpoint.
     * This completely replaces the old checkpoints.txt approach for API-assisted
     * sync.
     */
    public void syncHeadersWith1000Pow() throws IOException, VerificationException, BlockStoreException, ApiSyncException {
        log.info("Starting API_1000POW header sync (fetch 1000 headers, verify, checkpoint)...");

        long tipHeight = apiClient.fetchBlockCount();
        long startHeight = Math.max(1, tipHeight - 999);
        List<HeaderDto> headers = downloadHeaders(startHeight, tipHeight);

        if (headers.isEmpty()) {
            throw new ApiSyncException("No headers returned from API for 1000POW sync");
        }

        log.info("Fetched {} headers. Tip height: {}", headers.size(), tipHeight);

        headerVerifier.verifySequentialHeaders(headers);
        powVerifier.verifyPow(headers);

        persistHeaders(headers);

        StoredBlock newHead = blockStore.getChainHead();
        log.info("API_1000POW header sync completed. Runtime checkpoint established at height={}, hash={}",
                newHead != null ? newHead.getHeight() : -1,
                newHead != null ? newHead.getHeader().getHashAsString() : "null");
    }

    private List<HeaderDto> downloadHeaders(long startHeight, long tipHeight)
            throws IOException, ApiSyncException {
        List<HeaderDto> headers = new ArrayList<>();
        for (long h = startHeight; h <= tipHeight; h++) {
            HeaderDto header = apiClient.fetchHeaderAtHeight(h);
            if (header.height <= 0) {
                header.height = h;
            }
            headers.add(header);
            if ((h - startHeight + 1) % 100 == 0 || h == tipHeight) {
                log.info("Downloaded header height {}", h);
            }
        }
        return headers;
    }

    private void persistHeaders(List<HeaderDto> headers) throws BlockStoreException {
        StoredBlock prevStored = null;
        int storedCount = 0;
        for (HeaderDto dto : headers) {
            StoredBlock stored = toStoredBlock(dto, prevStored);
            prevStored = stored;
            storedCount++;
            if (storedCount % 200 == 0) {
                log.info("Stored {} headers...", storedCount);
            }
        }
        if (prevStored != null) {
            blockStore.setChainHead(prevStored);
        }
        log.info("Stored {} headers into BlockStore", storedCount);
    }

    public FastSyncCheckpointResult startFastSync(SyncMode syncMode, Wallet wallet)
            throws IOException, VerificationException, BlockStoreException, ApiSyncException {
        if (syncMode == SyncMode.API_1000POW) {
            log.info("Using API_1000POW sync mode");
            syncHeadersWith1000Pow();
            syncUtxos(wallet);
            return null;
        }

        log.info("Using FAST_API_10POW sync mode");
        FastSyncCheckpointResult result = prepareFastSyncCheckpoint();
        syncUtxos(wallet);
        return result;
    }

    public void syncUtxos(Wallet wallet) {
        log.info("=== Starting API UTXO sync ===");
        try {
            // Get addresses to check
            log.info("Step 1/3: Collecting wallet addresses...");
            List<Address> addresses = wallet.getIssuedReceiveAddresses();
            if (addresses.isEmpty()) {
                log.debug("No addresses issued yet, forcing initial address generation");
                wallet.currentReceiveAddress(); // Force issue
                addresses = wallet.getIssuedReceiveAddresses();
            }

            List<String> addrStrings = new ArrayList<>();
            for (Address addr : addresses) {
                addrStrings.add(addr.toString());
            }

            if (addrStrings.isEmpty()) {
                log.warn("No addresses to sync, UTXO sync aborted");
                return;
            }

            log.info("Step 1/3 Complete: Collected {} addresses to check", addrStrings.size());

            // Fetch UTXOs from API
            log.info("Step 2/3: Fetching UTXOs from API...");
            List<UtxoDto> utxos = apiClient.fetchUtxos(addrStrings);
            log.info("Step 2/3 Complete: Fetched {} UTXOs", utxos != null ? utxos.size() : 0);

            if (utxos == null || utxos.isEmpty()) {
                log.info("No UTXOs found for wallet addresses");
                log.info("=== API UTXO sync completed (no UTXOs) ===");
                return;
            }

            // Process UTXOs and fetch full transactions
            log.info("Step 3/3: Processing UTXOs and fetching transactions...");
            int processedCount = 0;
            int skippedCount = 0;
            int addedCount = 0;

            for (UtxoDto dto : utxos) {
                processedCount++;

                // Check if we already have this tx
                if (wallet.getTransaction(org.bitcoinj.core.Sha256Hash.wrap(dto.txId)) != null) {
                    skippedCount++;
                    log.debug("Transaction {} already in wallet, skipping", dto.txId);
                    continue;
                }

                try {
                    log.info("Fetching full tx for UTXO {}/{}: {}", processedCount, utxos.size(), dto.txId);
                    String hex = apiClient.fetchTransactionHex(dto.txId);
                    Transaction tx = new Transaction(params, Utils.HEX.decode(hex));

                    // Add to wallet
                    if (wallet.isPendingTransactionRelevant(tx)) {
                        wallet.receivePending(tx, null);
                        addedCount++;
                        log.info("Successfully added tx {} to wallet (total: {}/{})",
                                tx.getHash().toString(), addedCount, utxos.size());
                    } else {
                        skippedCount++;
                        log.warn("Transaction {} was not relevant to wallet", tx.getHash().toString());
                    }
                } catch (IOException e) {
                    log.error("Failed to fetch transaction {}: {} (Network Error)", dto.txId, e.getMessage());
                } catch (Exception e) {
                    log.error("Failed to process transaction {}: {} ({})",
                            dto.txId, e.getMessage(), e.getClass().getSimpleName());
                }
            }

            log.info("Step 3/3 Complete: Processed {} UTXOs, added {} transactions, skipped {}",
                    processedCount, addedCount, skippedCount);
            log.info("=== API UTXO sync completed successfully ===");

        } catch (Exception e) {
            log.error("UTXO Sync failed (Unexpected Error): {} ({})",
                    e.getMessage(), e.getClass().getSimpleName(), e);
        }
    }

    private StoredBlock toStoredBlock(HeaderDto dto, StoredBlock prevStored) throws BlockStoreException {
        Block block = dto.toBlock(params);
        if (dto.height <= 0 && prevStored != null) {
            dto.height = prevStored.getHeight() + 1;
        }
        if (dto.chainWork == null || dto.chainWork.isEmpty()) {
            log.warn("Checkpoint block {} missing chainWork field, falling back to computed work", dto.height);
        }
        BigInteger chainWork = dto.toChainWork(params, prevStored);
        StoredBlock stored = new StoredBlock(block, chainWork, (int) dto.height);
        blockStore.put(stored);
        return stored;
    }

    private List<Long> pickSampleHeights(long startHeight, long tipHeight, int targetCount) {
        Set<Long> heights = new LinkedHashSet<>();
        heights.add(tipHeight);
        heights.add(startHeight);

        long range = Math.max(0, tipHeight - startHeight);
        long maxDistinct = range + 1;
        while (heights.size() < targetCount && heights.size() < maxDistinct && range > 0) {
            long candidate = startHeight + ThreadLocalRandom.current().nextLong(range + 1);
            heights.add(candidate);
        }

        List<Long> orderedHeights = new ArrayList<>(heights);
        Collections.sort(orderedHeights);
        return orderedHeights;
    }

    private byte[] serializeCheckpoint(StoredBlock checkpoint) {
        ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);
        checkpoint.serializeCompact(buffer);
        String base64 = CheckpointManager.BASE64.encode(buffer.array());
        String textual = "TXT CHECKPOINTS 1\n0\n1\n" + base64 + "\n";
        return textual.getBytes(StandardCharsets.US_ASCII);
    }
}
