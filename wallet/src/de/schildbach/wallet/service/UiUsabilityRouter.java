package de.schildbach.wallet.service;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

import de.schildbach.wallet.data.api.ApiSessionWallet;
import de.schildbach.wallet.service.BlockchainService.DataSource;

/**
 * Single source of truth for UI usability state (Send/Balance).
 * Decouples logic from SPV availability when API_SESSION is active.
 */
public class UiUsabilityRouter {
    private static final Logger log = LoggerFactory.getLogger(UiUsabilityRouter.class);

    private final String sessionId;
    private final AtomicReference<UiState> currentState = new AtomicReference<>(new UiState());

    public static class UiState {
        public final DataSource dataSource;
        public final Coin availableBalance;
        public final boolean canSend;
        public final long timestampMs;

        public UiState() {
            this(DataSource.SPV_CANONICAL, Coin.ZERO, false);
        }

        public UiState(DataSource dataSource, Coin availableBalance, boolean canSend) {
            this.dataSource = dataSource;
            this.availableBalance = availableBalance;
            this.canSend = canSend;
            this.timestampMs = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("src=%s bal=%s canSend=%s", dataSource, availableBalance.toFriendlyString(), canSend);
        }
    }

    public UiUsabilityRouter(String sessionId) {
        this.sessionId = sessionId;
    }

    public UiState getUiState() {
        return currentState.get();
    }

    public void updateState(DataSource newSource, ApiSessionWallet sessionWallet, Coin spvBalance, boolean spvReady) {
        final UiState oldState = currentState.get();
        UiState newState;

        if (newSource == DataSource.API_SESSION) {
            // API_SESSION is authoritative
            Coin balance = (sessionWallet != null) ? sessionWallet.getBalance() : Coin.ZERO;
            boolean canSend = (sessionWallet != null && sessionWallet.isReady() && balance.signum() > 0);
            newState = new UiState(DataSource.API_SESSION, balance, canSend);
        } else {
            // SPV_CANONICAL fallback
            boolean canSend = spvReady && (spvBalance != null && spvBalance.signum() > 0);
            newState = new UiState(DataSource.SPV_CANONICAL, spvBalance != null ? spvBalance : Coin.ZERO, canSend);
        }

        if (newState.dataSource != oldState.dataSource || newState.canSend != oldState.canSend) {
            log.info("UI-SRC[sid={}] {} -> {} reason=router_update old_bal={} new_bal={}",
                    sessionId, oldState.dataSource, newState.dataSource,
                    oldState.availableBalance.toFriendlyString(), newState.availableBalance.toFriendlyString());
        }

        currentState.set(newState);
    }
}
