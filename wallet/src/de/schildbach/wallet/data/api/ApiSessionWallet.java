package de.schildbach.wallet.data.api;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import org.bitcoinj.core.*;

import de.schildbach.wallet.data.AddressBookProvider;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Ephemeral, in-memory component for managing API-provided UTXOs and signing
 * transactions.
 * Strict Isolation: Never modifies the canonical SPV blockstore or wallet.dat.
 */
public class ApiSessionWallet {
    private static final Logger log = LoggerFactory.getLogger(ApiSessionWallet.class);

    private final NetworkParameters params;
    // Map key: "txid:index"
    private final Map<String, SessionUtxo> utxos = new HashMap<>();
    // API-derived confirmed UTXOs
    private final Map<String, SessionUtxo> apiUtxos = new HashMap<>();
    // Local-derived change UTXOs (in-memory only)
    private final Map<String, LocalChangeUtxo> localChangeUtxos = new HashMap<>();
    private String sessionId = "--------";
    private int lastHistoryHash = 0;

    private static class LocalChangeUtxo {
        final SessionUtxo utxo;
        boolean spendable;

        LocalChangeUtxo(SessionUtxo utxo, boolean spendable) {
            this.utxo = utxo;
            this.spendable = spendable;
        }
    }

    private boolean ready = false;
    private Coin cachedBalance = Coin.ZERO;
    private Coin spendableBalance = Coin.ZERO;
    private long updatedAtMs = 0;

    // Owned addresses for isMine detection (base58 strings)
    // Populated from snapshot addresses + registered change addresses
    private final Set<String> ownedAddresses = new HashSet<>();

    // Newly confirmed incoming outpoints (for sound trigger)
    private final List<SessionUtxo> newlyConfirmedIncoming = new ArrayList<>();

    // Application context for persistence operations
    @Nullable
    private Context appContext;

    public ApiSessionWallet(NetworkParameters params) {
        this.params = params;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized void setCachedBalance(Coin available, Coin spendable) {
        this.cachedBalance = available;
        this.spendableBalance = spendable;
        log.info("SESSION-WALLET[sid={}] setCachedBalance available={} spendable={}", sessionId, available, spendable);
    }

    /**
     * Set application context for persistence operations.
     * Must be called before creating outgoing transactions.
     */
    public void setContext(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Load persisted overlay addresses from OverlayAddressStore.
     * Call this on session wallet initialization to restore change addresses.
     */
    public synchronized void loadOverlayAddresses(Context context) {
        if (context == null)
            return;

        Set<String> overlayAddrs = OverlayAddressStore.getAllAddresses(context);
        int beforeSize = ownedAddresses.size();
        ownedAddresses.addAll(overlayAddrs);
        int added = ownedAddresses.size() - beforeSize;

        log.info("SESSION-WALLET[sid={}] loadOverlayAddresses loaded={} total={}",
                sessionId, added, ownedAddresses.size());
    }

    // Pending transactions created by this session wallet, for retrieval by hash
    // during broadcast
    private final Map<Sha256Hash, Transaction> pendingTransactions = new HashMap<>();

    public synchronized void addPendingTransaction(Transaction tx) {
        pendingTransactions.put(tx.getTxId(), tx);
    }

    public synchronized Transaction getTransaction(Sha256Hash hash) {
        return pendingTransactions.get(hash);
    }

    public synchronized boolean isReady() {
        return ready;
    }

    public synchronized long getUpdatedAtMs() {
        return updatedAtMs;
    }

    // -- History Support --
    public enum TxDirection {
        RECEIVED, SENT
    }

    public static class SessionTxItem {
        public final String txId;
        public final long timeMs;
        public Coin valueDelta;
        public int confirmations;
        public TxDirection direction;
        public boolean pending;
        public boolean isSelfSend;

        public SessionTxItem(String txId, long timeMs, Coin valueDelta, int confirmations) {
            this(txId, timeMs, valueDelta, confirmations,
                    valueDelta.isNegative() ? TxDirection.SENT : TxDirection.RECEIVED,
                    confirmations == 0, false);
        }

        public SessionTxItem(String txId, long timeMs, Coin valueDelta, int confirmations,
                TxDirection direction, boolean pending, boolean isSelfSend) {
            this.txId = txId;
            this.timeMs = timeMs;
            this.valueDelta = valueDelta;
            this.confirmations = confirmations;
            this.direction = direction;
            this.pending = pending;
            this.isSelfSend = isSelfSend;
        }
    }

    private final List<SessionTxItem> history = new ArrayList<>();
    // Map of txId -> SessionTxItem for merge tracking
    private final Map<String, SessionTxItem> historyByTxId = new HashMap<>();

    public synchronized List<SessionTxItem> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Initialize history from cache on startup.
     */
    public synchronized void initializeHistory(List<SessionTxItem> cachedHistory) {
        if (cachedHistory == null || cachedHistory.isEmpty())
            return;

        history.clear();
        historyByTxId.clear();
        for (SessionTxItem item : cachedHistory) {
            history.add(item);
            historyByTxId.put(item.txId, item);
        }
        // Objective B: Mandatory logging for cached history loading
        log.info("SESSION-WALLET[sid={}] initializeHistory loaded={} items from cache", sessionId, history.size());
    }

    /**
     * Get filtered history by direction.
     * 
     * @param filter null for all, or RECEIVED/SENT to filter
     */
    public synchronized List<SessionTxItem> getFilteredHistory(@Nullable TxDirection filter) {
        if (filter == null) {
            return new ArrayList<>(history);
        }
        List<SessionTxItem> filtered = new ArrayList<>();
        for (SessionTxItem item : history) {
            if (item.direction == filter) {
                filtered.add(item);
            }
        }
        log.debug("SESSION-WALLET[sid={}] getFilteredHistory filter={} total={} filtered={}",
                sessionId, filter, history.size(), filtered.size());
        return filtered;
    }

    public synchronized int getTransactionCount() {
        return history.size();
    }

    public synchronized int utxoCount() {
        return utxos.size();
    }

    // Local Locked Outpoints (pending spends)
    private final java.util.Set<String> lockedOutpoints = new java.util.HashSet<>();

    public synchronized void lockOutpoint(String outpoint) {
        lockedOutpoints.add(outpoint);
        recalculateBalances();
    }

    public synchronized void unlockOutpoint(String outpoint) {
        if (lockedOutpoints.remove(outpoint)) {
            recalculateBalances();
        }
    }

    public static class CommitResult {
        public final int lockedInputsAdded;
        public final List<OutgoingTxJournal.ChangeOutpoint> localChangeOutpoints;

        CommitResult(int lockedInputsAdded, List<OutgoingTxJournal.ChangeOutpoint> localChangeOutpoints) {
            this.lockedInputsAdded = lockedInputsAdded;
            this.localChangeOutpoints = localChangeOutpoints != null ? localChangeOutpoints : new ArrayList<>();
        }
    }

    public synchronized boolean isOutpointLocked(String outpoint) {
        return lockedOutpoints.contains(outpoint);
    }

    private void recalculateBalances() {
        Coin newTotal = Coin.ZERO;
        Coin newSpendable = Coin.ZERO;

        for (SessionUtxo u : apiUtxos.values()) {
            if (lockedOutpoints.contains(u.getKey())) {
                continue;
            }
            newTotal = newTotal.add(u.value);
            newSpendable = newSpendable.add(u.value);
        }

        for (LocalChangeUtxo local : localChangeUtxos.values()) {
            SessionUtxo u = local.utxo;
            if (lockedOutpoints.contains(u.getKey())) {
                continue;
            }
            newTotal = newTotal.add(u.value);
            if (local.spendable) {
                newSpendable = newSpendable.add(u.value);
            }
        }

        this.cachedBalance = newTotal;
        this.spendableBalance = newSpendable;
        this.updatedAtMs = System.currentTimeMillis();
    }

    private void rebuildUtxoView() {
        utxos.clear();
        utxos.putAll(apiUtxos);
        for (LocalChangeUtxo local : localChangeUtxos.values()) {
            utxos.put(local.utxo.getKey(), local.utxo);
        }
    }

    public synchronized boolean isLocalChangeOutpoint(String outpointKey) {
        return localChangeUtxos.containsKey(outpointKey);
    }

    public synchronized int getLocalChangeUtxoCount() {
        return localChangeUtxos.size();
    }

    public synchronized int getApiUtxoCount() {
        return apiUtxos.size();
    }

    public synchronized List<SessionUtxo> drainNewConfirmedIncomingUtxos() {
        if (newlyConfirmedIncoming.isEmpty()) {
            return new ArrayList<>();
        }
        List<SessionUtxo> result = new ArrayList<>(newlyConfirmedIncoming);
        newlyConfirmedIncoming.clear();
        return result;
    }

    private void addLocalChangeUtxo(SessionUtxo utxo, boolean spendable) {
        localChangeUtxos.put(utxo.getKey(), new LocalChangeUtxo(utxo, spendable));
        utxos.put(utxo.getKey(), utxo);
    }

    public synchronized int addLocalChangeFromJournal(List<OutgoingTxJournal.ChangeOutpoint> changeOutpoints) {
        if (changeOutpoints == null || changeOutpoints.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (OutgoingTxJournal.ChangeOutpoint change : changeOutpoints) {
            if (change == null) {
                continue;
            }
            String outpointKey = change.getKey();
            if (localChangeUtxos.containsKey(outpointKey) || apiUtxos.containsKey(outpointKey)
                    || utxos.containsKey(outpointKey)) {
                continue;
            }
            try {
                Address addr = Address.fromString(params, change.address);
                SessionUtxo utxo = new SessionUtxo(
                        Sha256Hash.wrap(change.txid),
                        change.vout,
                        Coin.valueOf(change.valueSat),
                        addr,
                        change.scriptPubKey != null ? change.scriptPubKey : "",
                        0,
                        -1);
                registerOwnedAddress(change.address);
                addLocalChangeUtxo(utxo, true);
                added++;
            } catch (Exception e) {
                log.warn("SESSION-WALLET[sid={}] addLocalChangeFromJournal failed ex={} msg={}",
                        sessionId, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        if (added > 0) {
            recalculateBalances();
        }
        return added;
    }

    /**
     * Initialize owned addresses from snapshot scan (receive addresses).
     * Called after UtxoSnapshotRunner completes.
     */
    public synchronized void initOwnedAddresses(List<Address> snapshotAddresses) {
        // Keep existing addresses (change addresses registered during send)
        int beforeSize = ownedAddresses.size();
        for (Address addr : snapshotAddresses) {
            ownedAddresses.add(addr.toBase58());
        }
        log.info("SESSION-WALLET[sid={}] ownedAddress init type=SNAPSHOT added={} total={}",
                sessionId, ownedAddresses.size() - beforeSize, ownedAddresses.size());
    }

    /**
     * Register a single address as owned (e.g., change address during send).
     * Also persists to OverlayAddressStore and AddressBook for restart survival.
     */
    public synchronized void registerOwnedAddress(String base58Address) {
        boolean added = ownedAddresses.add(base58Address);
        if (added) {
            log.info("SESSION-WALLET[sid={}] ownedAddress add type=CHANGE addr={} size={}",
                    sessionId, base58Address, ownedAddresses.size());

            // Persist to survive restart
            if (appContext != null) {
                OverlayAddressStore.addAddress(appContext, base58Address);
                addChangeAddressToAddressBook(appContext, base58Address);
            }
        }
    }

    /**
     * Add a history entry from journal (SENT tx from previous session).
     * Uses internal tracking to survive updateFromApi() merge.
     */
    public synchronized void addJournalHistoryEntry(SessionTxItem item) {
        if (item == null || item.txId == null)
            return;

        // Check for duplicate / update existing
        if (historyByTxId.containsKey(item.txId)) {
            // Objective C: Self-send classification stability
            // If txid is in outgoingJournal AND snapshot indicates outputsToUs -> SELF (net
            // 0)
            boolean isJournalSent = isTxInJournal(item.txId);
            boolean isSelf = false;

            if (isJournalSent && item.direction == TxDirection.RECEIVED) {
                // If it was sent by us (in journal) but API says RECEIVED (outputs to us), it's
                // a SELF send
                isSelf = true;
                log.info("HISTORY_CLASSIFY txid={} journalOut=true outputsToUs=true -> SELF net=0", item.txId);
            }

            SessionTxItem existing = historyByTxId.get(item.txId);
            if (existing != null) {
                // Update confirmations and block state
                existing.confirmations = item.confirmations;
                existing.pending = item.pending;
                if (isSelf) {
                    existing.valueDelta = Coin.ZERO;
                    existing.direction = TxDirection.SENT; // Classify as SENT but value 0 for SELF
                    existing.isSelfSend = true;
                    log.info("[history] mark_self_transfer txid=" + item.txId + " journal=true outputsToUs=true");
                }
                log.info("SESSION-WALLET[sid={}] journalEntry updated already exists: {} conf={}->{}", sessionId,
                        item.txId, existing.confirmations, item.confirmations);
            } else {
                if (isSelf) {
                    item.valueDelta = Coin.ZERO;
                    item.direction = TxDirection.SENT;
                    item.isSelfSend = true;
                }
                history.add(0, item);
                historyByTxId.put(item.txId, item);
            }
            return;
        }

        history.add(0, item); // Add to top
        historyByTxId.put(item.txId, item);
        log.info("SESSION-WALLET[sid={}] journalEntry added txid={} amount={}",
                sessionId, item.txId, item.valueDelta);
    }

    private boolean isTxInJournal(String txId) {
        if (appContext == null)
            return false;
        return OutgoingTxJournal.isTxInJournal(appContext, txId);
    }

    /**
     * Add change address to AddressBook with auto-generated label.
     */
    private void addChangeAddressToAddressBook(Context context, String address) {
        try {
            // Check if already exists
            String existingLabel = AddressBookProvider.resolveLabel(context, address);
            if (existingLabel != null) {
                return; // Already in address book
            }

            // Count existing change addresses for label numbering
            Set<String> overlayAddrs = OverlayAddressStore.getAllAddresses(context);
            int changeNum = overlayAddrs.size();
            String label = "Change #" + changeNum;

            Uri uri = AddressBookProvider.contentUri(context.getPackageName())
                    .buildUpon().appendPath(address).build();

            ContentValues values = new ContentValues();
            values.put(AddressBookProvider.KEY_LABEL, label);
            context.getContentResolver().insert(uri, values);

            log.info("SESSION-WALLET[sid={}] AddressBook add change addr={} label={}",
                    sessionId, address, label);
        } catch (Exception e) {
            log.warn("SESSION-WALLET[sid={}] AddressBook add failed: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Record a recipient address in AddressBook for SENDING ADDRESSES display.
     * Called when sending coins to an external address.
     * Only adds if not already present.
     */
    private void recordRecipientAddress(String address) {
        if (appContext == null || address == null || address.isEmpty()) {
            return;
        }

        try {
            // Check if already exists
            String existingLabel = AddressBookProvider.resolveLabel(appContext, address);
            if (existingLabel != null) {
                // Already in address book - update last used time by updating label to itself
                log.debug("SESSION-WALLET[sid={}] RecipientAddr already in book: {}", sessionId, address);
                return;
            }

            // Add with "Sent to" label
            String timestamp = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(new java.util.Date());
            String label = "Sent " + timestamp;

            Uri uri = AddressBookProvider.contentUri(appContext.getPackageName())
                    .buildUpon().appendPath(address).build();

            ContentValues values = new ContentValues();
            values.put(AddressBookProvider.KEY_LABEL, label);
            appContext.getContentResolver().insert(uri, values);

            log.info("SESSION-WALLET[sid={}] RecipientAddr added to AddressBook addr={} label={}",
                    sessionId, address, label);
        } catch (Exception e) {
            log.warn("SESSION-WALLET[sid={}] RecipientAddr add failed: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Check if an address is owned by this session wallet.
     * Used for custom isMine detection without bitcoinj TransactionBag.
     */
    public synchronized boolean isAddressMine(String base58Address) {
        return ownedAddresses.contains(base58Address);
    }

    /**
     * Get all known wallet addresses (receive + change).
     * Used by UtxoSnapshotRunner to ensure change addresses are scanned.
     */
    public synchronized Set<String> getKnownAddresses() {
        return new HashSet<>(ownedAddresses);
    }

    /**
     * Full replacement of UTXO set from API.
     */
    public synchronized boolean updateFromApi(List<SessionUtxo> newUtxos, List<SessionTxItem> newHistory) {
        final List<SessionUtxo> apiList = (newUtxos != null) ? newUtxos : new ArrayList<>();
        final List<SessionTxItem> historyList = (newHistory != null) ? newHistory : new ArrayList<>();

        final Coin prevTotal = cachedBalance;
        final Coin prevSpendable = spendableBalance;
        final int prevHistorySize = history.size();
        final Set<String> prevApiKeys = new HashSet<>(apiUtxos.keySet());
        final Set<String> existingKeys = new HashSet<>(utxos.keySet());

        Map<String, SessionUtxo> newApi = new HashMap<>();
        List<SessionUtxo> newConfirmedIncoming = new ArrayList<>();
        Set<String> seenApiKeys = new HashSet<>();

        for (SessionUtxo u : apiList) {
            if (u == null) {
                continue;
            }
            String key = u.getKey();
            if (!seenApiKeys.add(key)) {
                continue;
            }
            newApi.put(key, u);
            if (!existingKeys.contains(key)) {
                newConfirmedIncoming.add(u);
            }
        }

        int localRemoved = 0;
        for (String key : newApi.keySet()) {
            if (localChangeUtxos.containsKey(key)) {
                localChangeUtxos.remove(key);
                localRemoved++;
            }
        }

        // Compute robust changed flag (Objective B)
        final boolean balanceChanged = !prevTotal.equals(cachedBalance) || !prevSpendable.equals(spendableBalance);
        final boolean historySizeChanged = historyList.size() != prevHistorySize;

        // Simple history content hash for change detection
        int historyContentHash = 0;
        for (SessionTxItem item : historyList) {
            historyContentHash = 31 * historyContentHash + (item.txId.hashCode() ^ item.confirmations);
        }
        boolean historyContentChanged = (historyContentHash != lastHistoryHash);
        lastHistoryHash = historyContentHash;

        boolean changed = !apiList.isEmpty() && (!newApi.keySet().equals(prevApiKeys) || balanceChanged
                || historySizeChanged || historyContentChanged || localRemoved > 0);

        apiUtxos.clear();
        apiUtxos.putAll(newApi);
        rebuildUtxoView();
        recalculateBalances();

        // Merge API history with existing local history (preserving SENT entries)
        // Build map of incoming API history items
        Map<String, SessionTxItem> apiHistoryMap = new HashMap<>();
        for (SessionTxItem item : historyList) {
            // Ensure all API-sourced items are marked as RECEIVED (unless already SENT)
            SessionTxItem withDirection = new SessionTxItem(
                    item.txId, item.timeMs, item.valueDelta, item.confirmations,
                    item.valueDelta.isNegative() ? TxDirection.SENT : TxDirection.RECEIVED,
                    item.confirmations == 0, item.isSelfSend);
            apiHistoryMap.put(item.txId, withDirection);
        }

        // Preserve local SENT entries not yet in API response
        // and merge confirmations for existing entries
        List<SessionTxItem> mergedHistory = new ArrayList<>();
        Map<String, SessionTxItem> newHistoryByTxId = new HashMap<>();

        // First, add all local SENT entries that are not in API response
        for (SessionTxItem localItem : history) {
            if (localItem.direction == TxDirection.SENT) {
                SessionTxItem apiItem = apiHistoryMap.get(localItem.txId);
                if (apiItem != null) {
                    // HISTORY_DEDUP: API has seen this tx - use API confirmations but keep SENT
                    // direction
                    // If API value delta is different (e.g. fee calc), stick to API or Local?
                    // Local SENT usually has fee included correctly if from commit.
                    // API might just see the value sent to external?
                    // API history from UtxoSnapshotRunner computes "all outputs to us".
                    // If we sent it, "outputs to us" is change.
                    // But UtxoSnapshotRunner logic is "sum all outputs to us".
                    // For SENT tx, that is change only.
                    // So API thinks it is RECEIVED (change amount).
                    // We must override with our SENT knowledge (total amount + fee).

                    // Logic: Keep local valueDelta (full spent amount), update confirmations from
                    // API.
                    // Update timestamp to API if valid?
                    // API timestamp is block time. Local is creation time.
                    // Use API time if confirmed.
                    long time = (apiItem.confirmations > 0 && apiItem.timeMs > 0) ? apiItem.timeMs : localItem.timeMs;

                    Coin localValue = localItem.valueDelta;
                    Coin apiValue = apiItem.valueDelta;
                    boolean isSelfSend = false;

                    // Goal C: Canonical self-send detection
                    // If local SENT is 0 (excluding fee, which is handled in commitTransaction)
                    // or if API sees 0 (all outputs mine), it is a SELF_TRANSFER.
                    if (localValue.isZero() || (apiValue != null && apiValue.isZero())) {
                        isSelfSend = true;
                        log.info("[history] mark_self_transfer txid=" + localItem.txId
                                + " outgoingLocal=true outputsToUs=true");
                    }

                    log.info("HIST_MERGE txid={} localDir={} localValue={} apiValue={} isSelfSend={} -> using={}",
                            localItem.txId, localItem.direction, localValue.toFriendlyString(),
                            apiValue != null ? apiValue.toFriendlyString() : "null",
                            isSelfSend, isSelfSend ? "ZERO" : "LOCAL");

                    SessionTxItem merged = new SessionTxItem(
                            localItem.txId, time, isSelfSend ? Coin.ZERO : localValue,
                            Math.max(localItem.confirmations, apiItem.confirmations),
                            TxDirection.SENT,
                            apiItem.confirmations == 0, isSelfSend);

                    mergedHistory.add(merged);
                    newHistoryByTxId.put(merged.txId, merged);
                    apiHistoryMap.remove(localItem.txId); // Don't add again

                    log.info("HISTORY_DEDUP[sid={}] merged SENT txid={} conf={} (was {}) self={}",
                            sessionId, merged.txId, merged.confirmations, localItem.confirmations, isSelfSend);
                } else {
                    // Still pending/not seen by API - keep local entry
                    mergedHistory.add(localItem);
                    newHistoryByTxId.put(localItem.txId, localItem);
                }
            }
        }

        // Add remaining API entries (RECEIVED)
        for (SessionTxItem apiItem : apiHistoryMap.values()) {
            mergedHistory.add(apiItem);
            newHistoryByTxId.put(apiItem.txId, apiItem);
        }

        // Sort by time descending (newest first)
        java.util.Collections.sort(mergedHistory, (a, b) -> Long.compare(b.timeMs, a.timeMs));

        this.history.clear();
        this.history.addAll(mergedHistory);
        this.historyByTxId.clear();
        this.historyByTxId.putAll(newHistoryByTxId);

        this.ready = true;

        // Task C: Save cache for fast startup
        if (appContext != null) {
            Coin pending = cachedBalance.subtract(spendableBalance);
            LastKnownSessionCache.save(appContext, cachedBalance, spendableBalance, pending, utxoCount(), getHistory());
        }

        for (SessionUtxo u : newConfirmedIncoming) {
            if (!localChangeUtxos.containsKey(u.getKey())) {
                newlyConfirmedIncoming.add(u);
            }
        }

        if (changed) {
            log.info("UI_REFRESH_TRIGGER reason=snapshot_ready src=API_SESSION screen=Home");
            log.info("API_SESSION_CHANGED emit reason=SESSION_WALLET_CHANGED total={} spendable={} txCount={}",
                    cachedBalance.toFriendlyString(), spendableBalance.toFriendlyString(), history.size());
        }

        log.info(
                "SESSION-WALLET[sid={}] updateFromApi apiUtxos={} localChangeUtxos={} balance={} PEPEPOW spendable={} PEPEPOW localLocks={} history={} changed={} ready=true",
                sessionId, apiUtxos.size(), localChangeUtxos.size(),
                cachedBalance.toPlainString(), spendableBalance.toPlainString(),
                lockedOutpoints.size(), history.size(), changed);

        return changed;
    }

    public synchronized Coin getBalance() {
        return cachedBalance;
    }

    public synchronized Coin getSpendableBalance() {
        return spendableBalance;
    }

    public synchronized List<SessionUtxo> getUtxos() {
        return new ArrayList<>(utxos.values());
    }

    public synchronized long getSnapshotTimeMs() {
        return updatedAtMs;
    }

    public synchronized String getSourceTag() {
        return "api-explorer";
    }

    /**
     * Builds and signs a transaction using the canonical wallet's keychain for
     * ECKey access only.
     */
    public synchronized Transaction createSignedTransaction(Wallet canonicalWallet, Address destination, Coin amount,
            @Nullable KeyParameter aesKey)
            throws InsufficientMoneyException {
        log.info("SESSION-WALLET[sid={}] creating transaction: amount={} to={}", sessionId, amount.toFriendlyString(),
                destination);

        // Fixed fee for simple Route B implementation
        final Coin fee = Coin.valueOf(100000); // 0.001 PEPEW
        final Coin totalNeeded = amount.add(fee);

        // Simple coin selection (greedy) using SPENDABLE coins only (respecting locks)
        Coin collected = Coin.ZERO;
        List<SessionUtxo> selected = new ArrayList<>();

        for (SessionUtxo utxo : utxos.values()) {
            if (lockedOutpoints.contains(utxo.getKey())) {
                continue; // Skip locally spent
            }
            boolean isLocalChange = localChangeUtxos.containsKey(utxo.getKey());
            if (!isLocalChange && utxo.confirmations < 1) {
                continue; // Skip immature/unconfirmed coins from API
            }
            if (isLocalChange && !localChangeUtxos.get(utxo.getKey()).spendable) {
                continue; // Local change not yet spendable
            }
            selected.add(utxo);
            collected = collected.add(utxo.value);
            if (!collected.isLessThan(totalNeeded)) {
                break;
            }
        }

        if (collected.isLessThan(totalNeeded)) {
            throw new InsufficientMoneyException(totalNeeded.subtract(collected));
        }

        Transaction tx = new Transaction(params);
        tx.addOutput(amount, destination);

        // Add change output if significant
        Coin change = collected.subtract(totalNeeded);
        if (change.isGreaterThan(Coin.valueOf(10000))) { // > 0.0001
            Address changeAddr = canonicalWallet.currentChangeAddress();
            // Register change address as owned for commitTransaction detection
            registerOwnedAddress(changeAddr.toBase58());
            tx.addOutput(change, changeAddr);
            log.info("SESSION-WALLET[sid={}] added change output: {} to {}", sessionId, change.toFriendlyString(),
                    changeAddr);
        }

        // Add inputs and sign
        for (int i = 0; i < selected.size(); i++) {
            SessionUtxo utxo = selected.get(i);
            TransactionOutPoint outPoint = new TransactionOutPoint(params, utxo.index, utxo.txId);

            // We need to find the correct key for this address in the canonical wallet
            ECKey key = canonicalWallet.findKeyFromPubHash(utxo.address.getHash());
            if (key == null) {
                throw new RuntimeException("Key not found in canonical wallet for address: " + utxo.address);
            }
            if (key.isEncrypted()) {
                if (aesKey == null) {
                    throw new RuntimeException("Wallet is encrypted but no AES key provided for API session signing");
                }
                key = key.decrypt(aesKey);
            }

            byte[] scriptBytes = org.bitcoinj.core.Utils.HEX.decode(utxo.scriptPubKey);
            Script scriptPubKey = new Script(scriptBytes);

            // Add input without scriptSig yet
            tx.addInput(utxo.txId, utxo.index, scriptPubKey);
        }

        // Now sign all inputs
        for (int i = 0; i < tx.getInputs().size(); i++) {
            SessionUtxo utxo = selected.get(i);
            ECKey key = canonicalWallet.findKeyFromPubHash(utxo.address.getHash());
            if (key.isEncrypted()) {
                key = key.decrypt(aesKey);
            }
            Script scriptPubKey = new Script(org.bitcoinj.core.Utils.HEX.decode(utxo.scriptPubKey));

            TransactionSignature sig = tx.calculateSignature(i, key, scriptPubKey, Transaction.SigHash.ALL, false);
            tx.getInput(i).setScriptSig(ScriptBuilder.createInputScript(sig, key));
        }

        // IMPORTANT: Do NOT add to pending/lock here yet.
        // Caller must do that after broadcast success or intention to broadcast.
        // Actually, safer to lock immediately?
        // Let's rely on caller (SessionSendManager) to call committed().

        log.info("SESSION-WALLET[sid={}] transaction signed and ready: hash={} inputs={} outputs={}",
                sessionId, tx.getTxId(), tx.getInputs().size(), tx.getOutputs().size());
        return tx;
    }

    public synchronized CommitResult commitTransaction(Transaction tx) {
        // 1. Add to pending transactions and lock inputs (prevent double-spend)
        addPendingTransaction(tx);

        // Calculate totalInput from the inputs we're spending
        Coin totalInput = Coin.ZERO;
        int lockedInputsAdded = 0;
        for (TransactionInput input : tx.getInputs()) {
            String outpoint = input.getOutpoint().getHash().toString() + ":" + input.getOutpoint().getIndex();
            if (lockedOutpoints.add(outpoint)) {
                lockedInputsAdded++;
            }

            // Get input value from our UTXO set
            SessionUtxo spentUtxo = utxos.get(outpoint);
            if (spentUtxo != null) {
                totalInput = totalInput.add(spentUtxo.value);
            }
        }

        // 2. Iterate outputs and classify as mine or external
        Coin totalToMine = Coin.ZERO;
        Coin totalToExternal = Coin.ZERO;
        List<OutgoingTxJournal.ChangeOutpoint> localChangeOutpoints = new ArrayList<>();

        for (int i = 0; i < tx.getOutputs().size(); i++) {
            TransactionOutput output = tx.getOutput(i);
            Coin outputValue = output.getValue();

            // Extract address from scriptPubKey WITHOUT using isMine/TransactionBag
            String outputAddress = null;
            try {
                Script script = output.getScriptPubKey();
                Address addr = script.getToAddress(params);
                outputAddress = addr.toBase58();
            } catch (Exception e) {
                // Script parsing failed (e.g., OP_RETURN, non-standard)
                // Treat as external/unknown - log and continue safely
                log.warn("SESSION-WALLET[sid={}] commitTransaction output {} script parse failed ex={} msg={}",
                        sessionId, i, e.getClass().getSimpleName(), e.getMessage());
            }

            // Check ownership using our custom ownedAddresses set
            boolean isMine = outputAddress != null && isAddressMine(outputAddress);

            if (isMine) {
                totalToMine = totalToMine.add(outputValue);

                // Add change output as local pending UTXO (confirmations=0, spendable once
                // broadcast has been initiated).
                // This ensures balance reflects the change even before next snapshot refresh.
                String outpointKey = tx.getTxId().toString() + ":" + i;
                if (!utxos.containsKey(outpointKey)) {
                    // Create pending UTXO with confirmations=0
                    SessionUtxo pendingUtxo = new SessionUtxo(
                            tx.getTxId(),
                            i,
                            outputValue,
                            Address.fromString(params, outputAddress),
                            output.getScriptPubKey().getProgram() != null
                                    ? org.bitcoinj.core.Utils.HEX.encode(output.getScriptPubKey().getProgram())
                                    : "",
                            0, // confirmations = 0 (pending)
                            -1 // height = -1 (unconfirmed)
                    );
                    addLocalChangeUtxo(pendingUtxo, true);
                    localChangeOutpoints.add(new OutgoingTxJournal.ChangeOutpoint(
                            tx.getTxId().toString(),
                            i,
                            outputValue.value,
                            outputAddress,
                            pendingUtxo.scriptPubKey));
                }
            } else {
                totalToExternal = totalToExternal.add(outputValue);

                // TASK 2: Record recipient address in AddressBook for SENDING ADDRESSES
                // Only if address is valid and context is available
                if (outputAddress != null && appContext != null) {
                    recordRecipientAddress(outputAddress);
                }
            }
        }

        // 3. Calculate fee
        Coin fee = totalInput.subtract(totalToExternal).subtract(totalToMine);
        if (fee.isNegative()) {
            // Should not happen with valid tx, but guard against corruption
            log.error("SESSION-WALLET[sid={}] commitTransaction negative fee! totalInput={} toExternal={} toMine={}",
                    sessionId, totalInput, totalToExternal, totalToMine);
            fee = Coin.ZERO;
        }

        // 4. Add to history as outgoing (amount = negative of what we sent externally)
        SessionTxItem item = new SessionTxItem(
                tx.getTxId().toString(),
                System.currentTimeMillis(),
                totalToExternal.negate(), // Negative for outgoing
                0, // confirmations = 0 (pending)
                TxDirection.SENT,
                true, // pending = true
                totalToExternal.isZero() // isSelfSend
        );
        history.add(0, item); // Add to top of history
        historyByTxId.put(item.txId, item);

        // 5. Recalculate balances (locked inputs are now excluded)
        recalculateBalances();

        // 6. Log comprehensive commit info
        log.info(
                "SESSION-WALLET[sid={}] commitOutgoing txid={} inputs={} toExternal={} toMine={} fee={} utxosNow={} historyNow={}",
                sessionId, tx.getTxId(),
                tx.getInputs().size(),
                totalToExternal.toPlainString(),
                totalToMine.toPlainString(),
                fee.toPlainString(),
                utxos.size(),
                history.size());

        return new CommitResult(lockedInputsAdded, localChangeOutpoints);
    }

    public synchronized void rollbackTransaction(Transaction tx) {
        if (tx == null) {
            return;
        }

        pendingTransactions.remove(tx.getTxId());

        int unlockedInputs = 0;
        for (TransactionInput input : tx.getInputs()) {
            String outpoint = input.getOutpoint().getHash().toString() + ":" + input.getOutpoint().getIndex();
            if (lockedOutpoints.remove(outpoint)) {
                unlockedInputs++;
            }
        }

        int localChangeRemoved = 0;
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            TransactionOutput output = tx.getOutput(i);
            String outputAddress = null;
            try {
                Script script = output.getScriptPubKey();
                Address addr = script.getToAddress(params);
                outputAddress = addr.toBase58();
            } catch (Exception e) {
                log.warn("SESSION-WALLET[sid={}] rollbackTransaction output {} script parse failed ex={} msg={}",
                        sessionId, i, e.getClass().getSimpleName(), e.getMessage());
            }

            boolean isMine = outputAddress != null && isAddressMine(outputAddress);
            if (isMine) {
                String outpointKey = tx.getTxId().toString() + ":" + i;
                if (localChangeUtxos.remove(outpointKey) != null) {
                    utxos.remove(outpointKey);
                    localChangeRemoved++;
                }
            }
        }

        SessionTxItem historyItem = historyByTxId.remove(tx.getTxId().toString());
        if (historyItem != null) {
            history.remove(historyItem);
        }

        recalculateBalances();

        log.info("SESSION-WALLET[sid={}] rollbackOutgoing txid={} unlockedInputs={} localChangeRemoved={}",
                sessionId, tx.getTxId(), unlockedInputs, localChangeRemoved);
    }
}
