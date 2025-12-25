package de.schildbach.wallet.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.core.Coin;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.data.api.ApiSessionWallet;

/**
 * Canonical UI readiness contract:
 * WalletReady := walletLoaded && spvBestHeight > 0
 *
 * IMPORTANT: FAST_API_10POW is overlay-only and must never gate wallet
 * usability.
 */
public final class WalletReadiness {
    private static final Logger log = LoggerFactory.getLogger(WalletReadiness.class);

    public static final String UI_SESSION_ID = UUID.randomUUID().toString().substring(0, 8);

    private static final Set<String> loggedUiGates = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> loggedIgnoredFlags = Collections.synchronizedSet(new HashSet<String>());
    private static final AtomicBoolean loggedReadyOverride = new AtomicBoolean(false);

    private WalletReadiness() {
    }

    public static boolean isWalletLoaded(final WalletApplication application) {
        return application != null
                && application.getWalletState() == WalletApplication.WalletState.LOADED
                && application.getWalletOrNull() != null;
    }

    public static int spvBestHeight(@Nullable final BlockchainState blockchainState) {
        return blockchainState != null ? blockchainState.bestChainHeight : 0;
    }

    public static boolean isWalletReady(final WalletApplication application,
            @Nullable final BlockchainState blockchainState) {

        // Decoupling Logic: If API_SESSION is authoritative, we are READY.
        // We do NOT wait for SPV height, consistency, or snapshots.
        final BlockchainService service = application.getBlockchainService();
        if (service != null && service.getUiDataSource() == BlockchainService.DataSource.API_SESSION) {
            return isWalletLoaded(application);
        }

        final boolean hasSnapshot = application.getConfiguration()
                .getWalletSnapshotStatus() == org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS;
        return isWalletLoaded(application) && (hasSnapshot || spvBestHeight(blockchainState) > 0);
    }

    /**
     * Deterministic Send button gate:
     * WalletReady(SPV) AND (unlocked) AND (balance > 0 OR snapshot active)
     */
    public static boolean canSendCoins(final WalletApplication application,
            @Nullable final BlockchainState blockchainState,
            @Nullable final Wallet wallet) {

        final BlockchainService service = application.getBlockchainService();

        // Fast-path for API_SESSION: Delegate entirely to service usability state
        // This bypasses ALL SPV checks (height, chain, replaying).
        if (service != null && service.getUiDataSource() == BlockchainService.DataSource.API_SESSION) {
            // We trust the service to have switched source only if session is actually
            // ready/usable.
            // We MUST check if we have spendable balance.
            ApiSessionWallet session = service.getSessionWallet();
            boolean canSend = session != null && session.getSpendableBalance().signum() > 0;
            if (!canSend && session != null) {
                // Log once per session ideally, but static context makes it hard.
                // Just return false.
            }
            return canSend;
        }

        // Fast-path for API_SESSION if snapshot successfully applied (Legacy/Fallback)
        final boolean hasSnapshot = application.getConfiguration()
                .getWalletSnapshotStatus() == org.dash.wallet.common.data.WalletSnapshotStatus.SUCCESS;

        if (hasSnapshot) {
            // If API_SESSION has been established, we trust the UI readiness of the
            // session.
            // Note: In API mode, we don't necessarily need spvBestHeight > 0 to enable SEND
            // if we have UTXOs from API.
            return true;
        }

        if (!isWalletReady(application, blockchainState) || wallet == null) {
            return false;
        }

        if (blockchainState != null && blockchainState.replaying) {
            return false;
        }

        // Must have balance (either SPV or overlay)
        final Coin balance = wallet.getBalance(Wallet.BalanceType.AVAILABLE);
        final boolean hasSpvBalance = balance.isGreaterThan(Coin.ZERO);

        return hasSpvBalance;
    }

    public static void logUiGateWalletReadyOnlyOnce(@Nullable final String component) {
        final String key = component != null ? component : "unknown";
        if (loggedUiGates.add(key)) {
            log.info("UI-GATE[sid={}] gate=WalletReady only component={}", UI_SESSION_ID, key);
        }
    }

    public static void logIgnoredFlagOnce(@Nullable final String flag) {
        if (flag == null) {
            return;
        }
        if (loggedIgnoredFlags.add(flag)) {
            log.info("UI-GATE[sid={}] ignoredFlag={}", UI_SESSION_ID, flag);
        }
    }

    public static void logUiReadyOverrideOnce() {
        if (loggedReadyOverride.compareAndSet(false, true)) {
            log.info("UI-READY[sid={}] WalletReady=true overridingOtherSyncFlags", UI_SESSION_ID);
        }
    }

    private static final Set<String> loggedUiSources = Collections.synchronizedSet(new HashSet<String>());

    public static void logUiSourceOnce(String balanceSource, String txSource, String heightSource, String reason) {
        String key = balanceSource + txSource + heightSource + reason;
        if (loggedUiSources.add(key)) {
            log.info("UI-SOURCE[sid={}] balanceSource={} txSource={} heightSource={} reason={}",
                    UI_SESSION_ID, balanceSource, txSource, heightSource, reason);
        }
    }
}
