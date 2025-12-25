package de.schildbach.wallet.data.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.wallet.Wallet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

/**
 * Manages the independent UTXO snapshot lane ("Route B").
 * Implements 2-step scan:
 * 1. getaddresstxs (list of txids)
 * 2. getrawtransaction (details for vins/vouts)
 * Resolves UTXOs locally.
 */
public class UtxoSnapshotRunner {
    private static final Logger log = LoggerFactory.getLogger(UtxoSnapshotRunner.class);

    private final ApiWalletClient walletClient;
    private final ApiSessionWallet sessionWallet;
    private final Wallet canonicalWallet;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String sessionId = "UNKNOWN";
    private int attemptInWindow = 0;
    private long windowStartTimeMs = 0;

    @Nullable
    private Context appContext;

    public enum SnapshotState {
        IDLE,
        RUNNING,
        READY,
        FAILED_TRANSIENT,
        DISABLED_PERMANENT
    }

    private SnapshotState state = SnapshotState.IDLE;
    private static final long RETRY_INTERVAL_MS = 10000; // 10s
    private static final long WINDOW_DURATION_MS = 60000; // 60s
    private static final long REFRESH_INTERVAL_MS = 30000; // 30s
    private static final long FAST_REFRESH_INTERVAL_MS = 15000; // 15s for new empty wallets
    private static final int TX_PAGE_SIZE = 50;
    private static final int MAX_TX_TO_FETCH = 200; // Safety cap

    public interface Listener {
        void onSnapshotStateChanged(SnapshotState newState);

        void onDataUpdated();
    }

    private Listener listener;

    public UtxoSnapshotRunner(ApiWalletClient walletClient, ApiSessionWallet sessionWallet,
            Wallet canonicalWallet, ApiWalletSnapshotBootstrapper bootstrapper) {
        this.walletClient = walletClient;
        this.sessionWallet = sessionWallet;
        this.canonicalWallet = canonicalWallet;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Set application context for overlay address persistence access.
     */
    public void setContext(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized SnapshotState getState() {
        return state;
    }

    private synchronized void setState(SnapshotState newState) {
        if (this.state != newState) {
            log.info("SNAPSHOT_STATE[sid={}] {} -> {}", sessionId, this.state, newState);
            this.state = newState;
            if (listener != null) {
                handler.post(() -> listener.onSnapshotStateChanged(newState));
            }
        }
    }

    private static final long BOOT_GRACE_PERIOD_MS = 10 * 60 * 1000L; // 10 minutes
    private static final int BOOT_GRACE_ATTEMPTS = 10;

    private boolean emptyIsFinal = false;
    private final long creationTimeMs = System.currentTimeMillis();
    private int totalAttempts = 0;

    public synchronized boolean isEmptyFinal() {
        return emptyIsFinal;
    }

    public synchronized void startAttemptWindow(String reason) {
        if (state == SnapshotState.DISABLED_PERMANENT) {
            log.info("FASTBOOT[sid={}] ROUTE_B skip: reason={} state=DISABLED_PERMANENT", sessionId, reason);
            return;
        }

        // Fix B: Allow re-triggering if READY (e.g. auto-refresh polling)
        // If RUNNING, we still skip to avoid overlaps.
        if (state == SnapshotState.RUNNING) {
            log.info("FASTBOOT[sid={}] ROUTE_B skip: reason={} state=RUNNING", sessionId, reason);
            return;
        }

        log.info("FASTBOOT[sid={}] ROUTE_B runUtxoSnapshotIfNeeded: reason={} state={}", sessionId, reason, state);

        windowStartTimeMs = System.currentTimeMillis();
        attemptInWindow = 0;
        scheduleNext(0);
    }

    private boolean checkedAllAddresses = false;

    public synchronized boolean hasCheckedAllAddresses() {
        return checkedAllAddresses;
    }

    private synchronized void setCheckedAllAddresses(boolean checked) {
        this.checkedAllAddresses = checked;
    }

    private synchronized void scheduleNext(long delayMs) {
        if (state == SnapshotState.DISABLED_PERMANENT)
            return;

        handler.removeCallbacks(this::runSnapshot);
        handler.postDelayed(this::runSnapshot, delayMs);
    }

    private void runSnapshot() {
        executor.execute(() -> {
            synchronized (this) {
                if (state == SnapshotState.RUNNING || state == SnapshotState.DISABLED_PERMANENT)
                    return;
                if (state == SnapshotState.READY) {
                    log.info("FASTBOOT[sid={}] ROUTE_B refresh started (from READY)", sessionId);
                    setState(SnapshotState.RUNNING);
                } else {
                    setState(SnapshotState.RUNNING);
                }
            }

            try {
                attemptInWindow++;
                totalAttempts++;
                long elapsedInWindow = System.currentTimeMillis() - windowStartTimeMs;
                log.info("FASTBOOT[sid={}] ROUTE_B run: attemptW={} total={} windowElapsed={}ms", sessionId,
                        attemptInWindow, totalAttempts, elapsedInWindow);

                // A) ADDRESS SET TO SCAN
                // Use robust provider
                List<Address> scanKey = SnapshotAddressProvider.getScanAddresses(canonicalWallet);
                int providerAddressCount = scanKey.size();

                // Add session wallet's known addresses (includes registered change addresses)
                Set<String> knownAddrs = sessionWallet.getKnownAddresses();
                int sessionAddressCount = 0;
                NetworkParameters scanParams = canonicalWallet.getParams();
                for (String addrStr : knownAddrs) {
                    try {
                        Address addr = Address.fromString(scanParams, addrStr);
                        if (!scanKey.contains(addr)) {
                            scanKey.add(addr);
                            sessionAddressCount++;
                        }
                    } catch (Exception e) {
                        log.warn("SNAPSHOT[sid={}] invalid session known address: {}", sessionId, addrStr);
                    }
                }

                // Add persisted overlay addresses (change addresses from previous sessions)
                int overlayAddressCount = 0;
                if (appContext != null) {
                    Set<String> overlayAddrs = OverlayAddressStore.getAllAddresses(appContext);
                    for (String addrStr : overlayAddrs) {
                        try {
                            Address addr = Address.fromString(scanParams, addrStr);
                            if (!scanKey.contains(addr)) {
                                scanKey.add(addr);
                                overlayAddressCount++;
                            }
                        } catch (Exception e) {
                            log.warn("SNAPSHOT[sid={}] invalid overlay address: {}", sessionId, addrStr);
                        }
                    }
                }

                // Log up to 3 addresses
                StringBuilder firstThree = new StringBuilder();
                int logCount = 0;
                Address first = null;
                Address last = null;
                if (!scanKey.isEmpty()) {
                    first = scanKey.get(0);
                    last = scanKey.get(scanKey.size() - 1);
                    for (Address a : scanKey) {
                        if (logCount > 0)
                            firstThree.append(", ");
                        firstThree.append(a);
                        logCount++;
                        if (logCount >= 3)
                            break;
                    }
                }

                // Required log format: SNAPSHOT_ADDRS provider=<n> overlay=<n> total=<n>
                log.info(
                        "SNAPSHOT_ADDRS provider={} overlay={} total={} session={} currentReceive={}",
                        providerAddressCount, overlayAddressCount, scanKey.size(), sessionAddressCount,
                        canonicalWallet.currentReceiveAddress());

                // B) 2-STEP SCAN
                // 1. Collect TXIDs
                Set<String> allTxids = new HashSet<>();
                boolean allAddressesSuccess = true;
                long walletBirthTimeSec = canonicalWallet.getEarliestKeyCreationTime();

                // Ensure birth time safety margin (2 hours)
                long cutoffTimeSec = walletBirthTimeSec - 7200;

                for (Address addr : scanKey) {
                    try {
                        boolean keepFetching = true;
                        int start = 0;
                        int fetchedForAddr = 0;
                        int addedForAddr = 0;

                        while (keepFetching) {
                            List<ApiTxRef> page = walletClient.fetchAddressTransactions(addr.toString(), start,
                                    TX_PAGE_SIZE);
                            if (page.isEmpty()) {
                                keepFetching = false;
                            } else {
                                for (ApiTxRef ref : page) {
                                    // A-2: Normalize timestamp immediately (seconds to ms)
                                    long txTimeMs = ref.blockTimeSeconds * 1000L;

                                    // Sanity check: if API returned ms (very large number), treat as ms
                                    if (ref.blockTimeSeconds > 10_000_000_000L) {
                                        txTimeMs = ref.blockTimeSeconds;
                                    }

                                    // Compare ms to ms
                                    // walletBirthTimeSec is seconds, so convert it to ms for comparison or use
                                    // logic below
                                    long birthTimeMs = walletBirthTimeSec * 1000L;
                                    // Safety margin 2 hours = 7200 sec = 7,200,000 ms
                                    long cutoffTimeMs = birthTimeMs - 7_200_000L;

                                    if (txTimeMs > 0 && txTimeMs < cutoffTimeMs) {
                                        log.info("FASTBOOT[sid={}] SNAPSHOT_CUTOFF addr={} txTimeMs={} birthTimeMs={}",
                                                sessionId, addr, txTimeMs, birthTimeMs);
                                        continue;
                                    }

                                    if (ref.txId != null) {
                                        allTxids.add(ref.txId);
                                        addedForAddr++;
                                    }
                                }

                                start += TX_PAGE_SIZE;
                                fetchedForAddr += page.size();

                                if (start >= 500)
                                    keepFetching = false; // Safety cap
                            }
                        }
                        log.info("SNAPSHOT[sid={}] ADDR_SCAN addr={} fetched={} added={}", sessionId, addr,
                                fetchedForAddr, addedForAddr);
                    } catch (Exception e) {
                        log.warn("SNAPSHOT[sid={}] partial fail for address {}: {}", sessionId, addr, e.toString());
                        allAddressesSuccess = false;
                    }
                }

                log.info("FASTBOOT[sid={}] SNAPSHOT_ADDR_TXS addresses={} txCount={} allAddressesSuccess={}",
                        sessionId, scanKey.size(), allTxids.size(), allAddressesSuccess);

                if (allTxids.isEmpty()) {
                    completeEmpty("No transactions found", allAddressesSuccess);
                    return;
                }

                // 2. Fetch Details & Build Candidates
                List<SessionUtxo> candidates = new ArrayList<>();
                List<ApiSessionWallet.SessionTxItem> sessionWalletHistory = new ArrayList<>();
                Set<String> spentPrevouts = new HashSet<>(); // "txid:vout"

                List<String> txList = new ArrayList<>(allTxids);
                // Cap
                if (txList.size() > MAX_TX_TO_FETCH) {
                    log.warn("SNAPSHOT[sid={}] Capping tx fetch at {}", sessionId, MAX_TX_TO_FETCH);
                    txList = txList.subList(0, MAX_TX_TO_FETCH);
                }

                for (String txid : txList) {
                    // Force decrypt=1 to get vins for spent checking
                    ApiTxDetail detail = walletClient.fetchTransactionDetail(txid, true);

                    if (detail.sourceJson == null) {
                        log.warn("SNAPSHOT[sid={}] Missing sourceJson for {}", sessionId, txid);
                        continue;
                    }
                    JSONObject json = detail.sourceJson;

                    // Parse VOUTs
                    int conf = json.optInt("confirmations", 0);
                    int matchedOutputs = 0;
                    int addedUtxos = 0;
                    int pendingCount = 0;

                    JSONArray vouts = json.optJSONArray("vout");
                    if (vouts != null) {
                        for (int i = 0; i < vouts.length(); i++) {
                            JSONObject out = vouts.getJSONObject(i);

                            // Robust Value Extraction
                            long val = -1;
                            if (out.has("valueSat")) {
                                val = out.optLong("valueSat");
                            } else if (out.has("value")) {
                                // Try double or string
                                try {
                                    Object v = out.get("value");
                                    if (v instanceof Number) {
                                        val = Coin.valueOf((long) (((Number) v).doubleValue() * 100000000.0)).value;
                                    } else {
                                        val = Coin.parseCoin(v.toString()).value;
                                    }
                                } catch (Exception ignored) {
                                }
                            } else if (out.has("amount")) {
                                try {
                                    val = Coin.parseCoin(out.getString("amount")).value;
                                } catch (Exception ignored) {
                                }
                            }

                            if (val <= 0)
                                continue; // ignore non-value

                            // Robust Address Extraction
                            List<String> addressList = new ArrayList<>();
                            JSONObject spk = out.optJSONObject("scriptPubKey");
                            if (spk != null) {
                                JSONArray addrs = spk.optJSONArray("addresses");
                                if (addrs != null) {
                                    for (int k = 0; k < addrs.length(); k++)
                                        addressList.add(addrs.getString(k));
                                }
                                // Fallback: "address" in scriptPubKey
                                if (addressList.isEmpty() && spk.has("address")) {
                                    addressList.add(spk.getString("address"));
                                }
                            }
                            // Fallback: "addresses" at vout root (some forks)
                            if (addressList.isEmpty() && out.has("addresses")) {
                                JSONArray addrs = out.optJSONArray("addresses");
                                if (addrs != null) {
                                    for (int k = 0; k < addrs.length(); k++)
                                        addressList.add(addrs.getString(k));
                                }
                            }

                            // Match against wallet
                            boolean matched = false;
                            Address myAddr = null;
                            for (String aStr : addressList) {
                                myAddr = findAddress(scanKey, aStr);
                                if (myAddr != null) {
                                    matched = true;
                                    break;
                                }
                            }

                            if (matched) {
                                matchedOutputs++;
                                if (conf > 0) {
                                    // Candidate
                                    Coin amt = Coin.valueOf(val);
                                    String hex = (spk != null) ? spk.optString("hex", "") : "";
                                    candidates.add(new SessionUtxo(Sha256Hash.wrap(txid), out.optInt("n", i),
                                            amt, myAddr, hex, conf, detail.blockHeight));
                                    addedUtxos++;
                                } else {
                                    pendingCount++;
                                }
                            }
                        }
                    }

                    log.info("SESSION-WALLET[sid={}] scanTx txid={} conf={} matchedOutputs={} addedUtxos={} pending={}",
                            sessionId, txid, conf, matchedOutputs, addedUtxos, pendingCount);

                    // History Item (Incoming approximation)
                    // We sum all outputs to us. If > 0, we treat as incoming history event for now.
                    // (To do better, we'd need input scripts to detect sends).
                    long totalValueResult = 0;
                    if (matchedOutputs > 0) {
                        try {
                            // Re-calculate or just capture during loop?
                            // We didn't capture total value in the loop above, only added candidates.
                            // Let's iterate candidates for this tx? No, candidates only include confirmed >
                            // 0?
                            // Actually, history should show unconfirmed too?
                            // And "spent" outputs (which aren't candidates).
                            // So we should track value during the vout loop.
                            // Re-doing simple vout iteration for total value to be safe/clean
                            // (Optimization: merge with above loop if perf critical, but this is clearer).
                        } catch (Exception e) {
                        }
                    }

                    // Actually, let's just use a accumulator in the main loop above.
                    // But I cannot easily edit the exact lines inside the big block I just replaced
                    // in previous step without context.
                    // I will perform a separate loop here for history construction to ensure
                    // correctness without messy nested replaces.
                    long txValEu = 0;
                    if (vouts != null) {
                        for (int i = 0; i < vouts.length(); i++) {
                            JSONObject out = vouts.getJSONObject(i);
                            // Quick check address match
                            boolean isMine = false;
                            JSONObject spk = out.optJSONObject("scriptPubKey");
                            if (spk != null) {
                                JSONArray addrs = spk.optJSONArray("addresses");
                                if (addrs != null) {
                                    for (int k = 0; k < addrs.length(); k++) {
                                        if (findAddress(scanKey, addrs.getString(k)) != null) {
                                            isMine = true;
                                            break;
                                        }
                                    }
                                }
                                if (!isMine && spk.has("address")) {
                                    if (findAddress(scanKey, spk.getString("address")) != null)
                                        isMine = true;
                                }
                            }
                            if (isMine) {
                                long v = out.optLong("valueSat", -1);
                                if (v == -1 && out.has("value")) {
                                    try {
                                        v = Coin.parseCoin(out.getString("value")).value;
                                    } catch (Exception ignore) {
                                    }
                                }
                                if (v > 0)
                                    txValEu += v;
                            }
                        }
                    }

                    if (txValEu > 0) {
                        // Add to history
                        // Sort by time?
                        // The list 'txList' came from 'allTxids'. 'allTxids' is a Set, so order is
                        // undefined?
                        // 'txList' = new ArrayList(allTxids). Undefined order.
                        // We will sort 'history' before passing to session wallet? Or assume UI sorts?
                        // UI (WalletTransactionsFragment) usually sorts.
                        // SessionTxItem needs timeMs.
                        long timeMs = detail.blockTimeSeconds * 1000L;
                        // SessionTxItem(String txId, long timeMs, Coin valueDelta, int confirmations)
                        // We interpret 'txValEu' as positive (Receive).
                        // Use ApiSessionWallet.SessionTxItem
                        // Note: We need to import or fully qualify SessionTxItem.
                        // UtxoSnapshotRunner imports ApiSessionWallet.
                        sessionWalletHistory
                                .add(new ApiSessionWallet.SessionTxItem(txid, timeMs, Coin.valueOf(txValEu), conf));
                    }

                    // Parse VINs -> Spent
                    JSONArray vins = json.optJSONArray("vin");
                    if (vins != null) {
                        for (int i = 0; i < vins.length(); i++) {
                            JSONObject in = vins.getJSONObject(i);
                            String inTxid = in.optString("txid", null);
                            int inVout = in.optInt("vout", -1);
                            if (inTxid != null && inVout >= 0) {
                                spentPrevouts.add(inTxid + ":" + inVout);
                            }
                        }
                    }
                }

                // 3. Resolve
                List<SessionUtxo> finalUtxos = new ArrayList<>();
                for (SessionUtxo cand : candidates) {
                    if (!spentPrevouts.contains(cand.getKey())) {
                        finalUtxos.add(cand);
                    }
                }

                // C) Update Session
                log.info("SESSION-WALLET[sid={}] updateFromApi utxos={} candidates={} spent={}",
                        sessionId, finalUtxos.size(), candidates.size(), spentPrevouts.size());

                sessionWallet.updateFromApi(finalUtxos, sessionWalletHistory);

                // Initialize owned address set for outgoing tx tracking
                sessionWallet.initOwnedAddresses(scanKey);

                // D) Result
                setCheckedAllAddresses(allAddressesSuccess);

                log.info("SNAPSHOT_RESULT[sid={}] OK utxos={} checkedAllAddresses={}",
                        sessionId, finalUtxos.size(), allAddressesSuccess);

                synchronized (this) {
                    setState(SnapshotState.READY);
                }

                scheduleNext(REFRESH_INTERVAL_MS);

            } catch (

            Exception e) {
                handleFailure(e);
            }
        });
    }

    private void completeEmpty(String reason, boolean checkedAll) {
        // Fix A: Tentative Empty Logic
        long ageMs = System.currentTimeMillis() - creationTimeMs;
        boolean ageExpired = ageMs > BOOT_GRACE_PERIOD_MS;
        boolean attemptsExpired = totalAttempts >= BOOT_GRACE_ATTEMPTS;

        // Final only if checked ALL addresses AND (age expired OR attempts expired)
        this.emptyIsFinal = checkedAll && (ageExpired || attemptsExpired);

        log.info(
                "SNAPSHOT_RESULT[sid={}] EMPTY reason={} checkedAllAddresses={} emptyIsFinal={} (age={}s attempts={})",
                sessionId, reason, checkedAll, emptyIsFinal, ageMs / 1000, totalAttempts);

        sessionWallet.updateFromApi(new ArrayList<>(), new ArrayList<>());
        setCheckedAllAddresses(checkedAll);
        synchronized (this) {
            setState(SnapshotState.READY);
        }
        // Use faster refresh for new wallets that are still empty (not finalized)
        // This helps detect incoming transactions faster when explorer indexing lags
        long refreshInterval = (!emptyIsFinal) ? FAST_REFRESH_INTERVAL_MS : REFRESH_INTERVAL_MS;
        log.info("SNAPSHOT[sid={}] scheduling next refresh in {}ms (emptyIsFinal={})",
                sessionId, refreshInterval, emptyIsFinal);
        scheduleNext(refreshInterval);
    }

    private void handleFailure(Exception e) {
        boolean isPermanent = e.getMessage() != null
                && (e.getMessage().contains("403") || e.getMessage().contains("Network Mismatch"));

        log.error("SNAPSHOT_RESULT[sid={}] FAIL_{} ex={} msg={}",
                sessionId, isPermanent ? "PERMANENT" : "TRANSIENT", e.getClass().getSimpleName(),
                e.getMessage());

        if (isPermanent) {
            synchronized (this) {
                setState(SnapshotState.DISABLED_PERMANENT);
            }
            return;
        }

        long elapsedInWindow = System.currentTimeMillis() - windowStartTimeMs;
        if (elapsedInWindow < WINDOW_DURATION_MS) {
            synchronized (this) {
                // Keep moving if not ready
                if (state != SnapshotState.READY) {
                    setState(SnapshotState.FAILED_TRANSIENT);
                }
            }
            scheduleNext(RETRY_INTERVAL_MS);
        } else {
            synchronized (this) {
                if (state != SnapshotState.READY) {
                    setState(SnapshotState.FAILED_TRANSIENT);
                }
            }
        }
    }

    private Address findAddress(List<Address> myAddrs, String addrStr) {
        for (Address a : myAddrs) {
            if (a.toString().equals(addrStr))
                return a;
        }
        return null;
    }

    public synchronized void stop() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}
