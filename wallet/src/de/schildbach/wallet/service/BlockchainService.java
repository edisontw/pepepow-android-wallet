/*
 * Copyright 2012-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service;

import java.util.List;

import javax.annotation.Nullable;

import org.bitcoinj.core.Peer;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Coin;
import org.dash.wallet.common.data.SyncMode;
import androidx.lifecycle.LiveData;

import javax.annotation.CheckForNull;

/**
 * @author Andreas Schildbach
 */
public interface BlockchainService {
        public static final String ACTION_PEER_STATE = BlockchainService.class.getPackage().getName() + ".peer_state";
        public static final String ACTION_PEER_STATE_NUM_PEERS = "num_peers";

        public static final String ACTION_BLOCKCHAIN_STATE = BlockchainService.class.getPackage().getName()
                        + ".blockchain_state";

        public static final String ACTION_CANCEL_COINS_RECEIVED = BlockchainService.class.getPackage().getName()
                        + ".cancel_coins_received";
        public static final String ACTION_RESET_BLOCKCHAIN = BlockchainService.class.getPackage().getName()
                        + ".reset_blockchain";
        public static final String ACTION_WIPE_WALLET = BlockchainService.class.getPackage().getName()
                        + ".wipe_wallet";
        public static final String ACTION_BROADCAST_TRANSACTION = BlockchainService.class.getPackage().getName()
                        + ".broadcast_transaction";
        public static final String ACTION_BROADCAST_TRANSACTION_HASH = "hash";

        BlockchainState getBlockchainState();

        @Nullable
        List<Peer> getConnectedPeers();

        List<StoredBlock> getRecentBlocks(int maxBlocks);

        void switchSyncMode(SyncMode mode);

        @Nullable
        de.schildbach.wallet.data.api.ApiSessionWallet getSessionWallet();

        enum DataSource {
                SPV_CANONICAL, API_SESSION
        }

        DataSource getUiDataSource();

        java.util.concurrent.Future<org.bitcoinj.core.Transaction> broadcastTransaction(
                        org.bitcoinj.core.Transaction tx);

        /**
         * Gets the P2P broadcast-only manager for Session Wallet transactions.
         * This uses RAM-only bitcoinj objects and never touches disk or SPV state.
         * Returns null if not in API mode or not initialized.
         */
        @Nullable
        de.schildbach.wallet.data.BroadcastOnlyPeerManager getBroadcastOnlyPeerManager();

        LiveData<WalletUsabilityState> getWalletUsabilityLiveData();

        class WalletUsabilityState {
                public final String snapshotState;
                public final Coin sessionBalance;
                public final int sessionUtxoCount;
                public final String balanceSource; // SPV | API_SESSION
                public final boolean sendEnabled;
                public final Coin spvBalance;
                public final String reason;
                public final boolean historyChanged; // Fix C: track whether history actually changed
                @Nullable
                public final List<de.schildbach.wallet.data.api.ApiSessionWallet.SessionTxItem> sessionHistory;

                public WalletUsabilityState(String snapshotState, Coin sessionBalance, int sessionUtxoCount,
                                String balanceSource, boolean sendEnabled, Coin spvBalance, String reason,
                                boolean historyChanged,
                                @Nullable List<de.schildbach.wallet.data.api.ApiSessionWallet.SessionTxItem> sessionHistory) {
                        this.snapshotState = snapshotState;
                        this.sessionBalance = sessionBalance != null ? sessionBalance : Coin.ZERO;
                        this.sessionUtxoCount = sessionUtxoCount;
                        this.balanceSource = balanceSource;
                        this.sendEnabled = sendEnabled;
                        this.spvBalance = spvBalance != null ? spvBalance : Coin.ZERO;
                        this.reason = reason;
                        this.historyChanged = historyChanged;
                        this.sessionHistory = sessionHistory;
                }

                public boolean equivalentForLog(WalletUsabilityState other) {
                        if (other == null) {
                                return false;
                        }
                        int histSize = (sessionHistory != null) ? sessionHistory.size() : 0;
                        int otherHistSize = (other.sessionHistory != null) ? other.sessionHistory.size() : 0;

                        return sendEnabled == other.sendEnabled
                                        && sessionUtxoCount == other.sessionUtxoCount
                                        && safeEquals(balanceSource, other.balanceSource)
                                        && safeEquals(snapshotState, other.snapshotState)
                                        && safeEquals(sessionBalance, other.sessionBalance)
                                        && safeEquals(spvBalance, other.spvBalance)
                                        && histSize == otherHistSize;
                }

                private static boolean safeEquals(Object a, Object b) {
                        return a == b || (a != null && a.equals(b));
                }
        }
}
