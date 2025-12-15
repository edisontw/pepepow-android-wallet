/*
 * Copyright 2011-2015 the original author or authors.
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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.common.base.Stopwatch;

import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.SporkMessage;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.listeners.PeerConnectedEventListener;
import org.bitcoinj.core.listeners.PeerDataEventListener;
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener;
import org.bitcoinj.core.listeners.SporkUpdatedEventListener;
import org.bitcoinj.evolution.SimplifiedMasternodeListManager;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.MultiplexingDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;
import org.bitcoinj.net.discovery.SeedPeers;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.greenrobot.eventbus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

import javax.annotation.Nullable;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.AddressBookProvider;
import de.schildbach.wallet.service.BlockchainState.Impediment;
import de.schildbach.wallet.ui.SyncProgressEvent;
import de.schildbach.wallet.ui.WalletActivity;
import de.schildbach.wallet.util.BlockchainStateUtils;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet.data.api.ApiHeaderClient;
import de.schildbach.wallet.data.api.HeaderVerifier;
import de.schildbach.wallet.data.api.HeaderDto;
import de.schildbach.wallet.data.api.PowVerifier;
import de.schildbach.wallet.data.api.ApiSyncManager;
import de.schildbach.wallet.data.api.ApiStatus;
import de.schildbach.wallet.data.api.ApiPowBootstrapper;
import de.schildbach.wallet.data.api.ApiWalletClient;
import de.schildbach.wallet.data.api.ApiWalletSnapshotBootstrapper;
import org.dash.wallet.common.data.SyncMode;
import org.pepepow.wallet.R;
import androidx.lifecycle.LifecycleService;
import static org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO;

/**
 * @author Andreas Schildbach
 */
/*
 * Lifecycle notes for PEPEPOW fork:
 * - Wallet loading and SPV initialization now run on a background single-thread
 * executor to keep the main thread responsive on second launch.
 * - SPV blockstore handling keeps the file open across FAST_API_10POW bootstrap
 * and re-open attempts and tracks readiness via spvReady.
 * - If wallet loading or SPV init fails, the service stops itself so UI can
 * react instead of hanging behind a null wallet reference.
 */
public class BlockchainServiceImpl extends LifecycleService implements BlockchainService {

    private static final String TAG = "BlockchainServiceImpl";
    private static final String FASTBOOT = "PB-FASTBOOT";
    private WalletApplication application;
    private Configuration config;

    private BlockStore blockStore;
    private File blockChainFile;
    private BlockChain blockChain;
    private InputStream bootStrapStream;
    @Nullable
    private PeerGroup peerGroup;
    private ApiSyncManager apiSyncManager;
    private SyncMode selectedSyncMode = SyncMode.FULL_SPV;
    private Thread bootstrapThread;
    private CountDownLatch bootstrapLatch;

    private boolean connectivityReceiverRegistered = false;
    private boolean tickReceiverRegistered = false;
    private boolean walletListenersRegistered = false;
    private boolean spvInitialized = false;
    private final AtomicBoolean spvReady = new AtomicBoolean(false);
    private final AtomicBoolean forceFullSpvThisSession = new AtomicBoolean(false);
    private volatile boolean fastApiBootstrapAttempted = false;
    private volatile boolean fastApiBootstrapSucceeded = false;
    private volatile boolean fastBootCompleted = false;
    private volatile boolean fastApiIntegrityFailed = false;
    private volatile ApiPowBootstrapper.BootstrapResult lastBootstrapResult;
    private volatile ApiWalletSnapshotBootstrapper.Result lastWalletSnapshotResult;
    private volatile boolean walletSnapshotAttempted = false;

    // FAST_BOOT State Machine
    public enum FastBootState {
        IDLE, RUNNING, SUCCEEDED, DISABLED_SESSION, DISABLED_COOLDOWN
    }

    private volatile FastBootState fastBootState = FastBootState.IDLE;

    private int apiBestChainHeight = 0;
    private Date apiBestChainDate = new Date(0);
    private int apiSyncPercentage = 0;

    private final Handler handler = new Handler();
    private final Handler delayHandler = new Handler();
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blockchain-init");
        t.setDaemon(true);
        return t;
    });
    private WakeLock wakeLock;
    private long nextPeerGroupStartTimeMs = 0L;
    private final Runnable peerGroupConnectionTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (peerGroup != null && peerGroup.numConnectedPeers() == 0) {
                log.warn("PeerGroup failed to connect within {} ms, applying backoff", PEER_CONNECT_TIMEOUT_MS);
                applyPeerGroupBackoff();
            }
        }
    };
    private final Runnable peerGroupBackoffRunnable = new Runnable() {
        @Override
        public void run() {
            if (!impediments.contains(Impediment.NETWORK)) {
                updatePeerGroup();
            }
        }
    };

    private PeerConnectivityListener peerConnectivityListener;
    private NotificationManager nm;
    private ConnectivityManager connectivityManager;
    private final Set<Impediment> impediments = EnumSet.noneOf(Impediment.class);
    private int notificationCount = 0;
    private Coin notificationAccumulatedAmount = Coin.ZERO;
    private final List<Address> notificationAddresses = new LinkedList<Address>();
    private AtomicInteger transactionsReceived = new AtomicInteger();
    private long serviceCreatedAt;
    private boolean resetBlockchainOnShutdown = false;
    private boolean deleteWalletFileOnShutdown = false;

    // Settings to bypass dashj default dns seeds
    private final SeedPeers seedPeerDiscovery = new SeedPeers(Constants.NETWORK_PARAMETERS);
    private final DnsDiscovery dnsDiscovery = new DnsDiscovery(Constants.DNS_SEED, Constants.NETWORK_PARAMETERS);
    ArrayList<PeerDiscovery> peerDiscoveryList = new ArrayList<>(2);

    private static final int MIN_COLLECT_HISTORY = 2;
    private static final int IDLE_BLOCK_TIMEOUT_MIN = 2;
    private static final int IDLE_TRANSACTION_TIMEOUT_MIN = 9;
    private static final int MAX_HISTORY_SIZE = Math.max(IDLE_TRANSACTION_TIMEOUT_MIN, IDLE_BLOCK_TIMEOUT_MIN);
    private static final long APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long TX_EXCHANGE_RATE_TIME_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(180);
    private static final long PEER_CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(45);
    private static final long PEER_GROUP_FAILURE_BACKOFF_MS = TimeUnit.SECONDS.toMillis(60);
    private static final long API_BOOTSTRAP_MIN_INTERVAL_MS = 60L * 60L * 1000L; // 1 hour
    private static final long API_FAILURE_COOLDOWN_MS = 10L * 60L * 1000L; // 10 minutes

    private static final Logger log = LoggerFactory.getLogger(BlockchainServiceImpl.class);

    // Round-1: in-memory, process-lifetime FASTBOOT guard + observability session
    // id.
    private static final String FASTBOOT_SESSION_ID = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean spvInitInProgress = new AtomicBoolean(false);
    private static final AtomicReference<FastBootState> FASTBOOT_SESSION_STATE = new AtomicReference<>(
            FastBootState.IDLE);
    private static final AtomicLong FASTBOOT_LAST_RUN_TS_MS = new AtomicLong(0L);
    // One-shot latch to prevent repeated ACTION_FAST_SYNC_FAILED broadcasts per
    // session
    private static final AtomicBoolean FASTBOOT_FAILURE_BROADCAST_SENT = new AtomicBoolean(false);

    // Round-1 observability throttles (log only on changes).
    private static final AtomicInteger LAST_LOGGED_SPV_BEST_HEIGHT = new AtomicInteger(-1);
    private static final AtomicInteger LAST_LOGGED_UI_SPV_HEIGHT = new AtomicInteger(-1);
    private static final AtomicInteger LAST_LOGGED_UI_API_HEIGHT = new AtomicInteger(-1);
    private static final AtomicInteger LAST_LOGGED_BEST_PEER_HEIGHT = new AtomicInteger(-1);

    // Sync Stall Watchdog (Task B): logging-only instrumentation
    private static final long WATCHDOG_INTERVAL_MS = 10_000L; // 10 seconds
    private static final long WATCHDOG_STALL_THRESHOLD_MS = 60_000L; // 60 seconds
    private final AtomicBoolean chainAdvancedAtLeastOnce = new AtomicBoolean(false);
    private volatile long syncStartedTimeMs = 0L;
    private volatile boolean watchdogRunning = false;
    private final Runnable syncWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!watchdogRunning) {
                return;
            }
            try {
                final int localHeight = blockChain != null ? blockChain.getBestChainHeight() : -1;
                final int peersConnected = peerGroup != null ? peerGroup.numConnectedPeers() : 0;
                final int downloadPeerHeight = peerGroup != null ? peerGroup.getMostCommonChainHeight() : 0;
                final Wallet w = application.getWallet();
                final int walletBestHeight = (w != null) ? w.getLastBlockSeenHeight() : -1;
                final boolean advanced = chainAdvancedAtLeastOnce.get();
                final long elapsedMs = (syncStartedTimeMs > 0) ? (System.currentTimeMillis() - syncStartedTimeMs) : 0;

                log.info(
                        "SPV-WATCHDOG[sid={}] elapsedMs={} localHeight={} peersConnected={} downloadPeerHeight={} walletBestHeight={} chainAdvancedOnce={}",
                        FASTBOOT_SESSION_ID, elapsedMs, localHeight, peersConnected, downloadPeerHeight,
                        walletBestHeight, advanced);

                // High-signal stall warning: if localHeight is still 0 after 60s
                if (localHeight == 0 && elapsedMs > WATCHDOG_STALL_THRESHOLD_MS && !advanced) {
                    log.warn(
                            "SPV-WATCHDOG[sid={}] stalledAfterStart localHeight=0 elapsedMs={} peersConnected={} downloadPeerHeight={} fastState={} mode={}",
                            FASTBOOT_SESSION_ID, elapsedMs, peersConnected, downloadPeerHeight, fastBootState,
                            selectedSyncMode);
                }

                // Continue watchdog if still syncing
                if (watchdogRunning) {
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            } catch (Exception e) {
                log.warn("SPV-WATCHDOG[sid={}] exception: {}", FASTBOOT_SESSION_ID, e.getMessage());
            }
        }
    };

    public static final String START_AS_FOREGROUND_EXTRA = "start_as_foreground";

    private boolean isApiMode() {
        return selectedSyncMode == SyncMode.FAST_API_10POW || selectedSyncMode == SyncMode.API_1000POW;
    }

    /**
     * Returns true only when FAST overlay data should be used for UI.
     * Conditions: API mode selected, SUCCEEDED state, AND bootstrap succeeded.
     */
    private boolean isOverlayEnabled() {
        return isApiMode()
                && FASTBOOT_SESSION_STATE.get() == FastBootState.SUCCEEDED
                && fastApiBootstrapSucceeded;
    }

    private final ThrottlingWalletChangeListener walletEventListener = new ThrottlingWalletChangeListener(
            APPWIDGET_THROTTLE_MS) {
        @Override
        public void onThrottledWalletChanged() {
            WalletBalanceWidgetProvider.updateWidgets(BlockchainServiceImpl.this, application.getWallet());
        }

        @Override
        public void onCoinsReceived(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                final Coin newBalance) {

            if (blockChain == null) {
                return;
            }

            final int bestChainHeight = blockChain.getBestChainHeight();
            final boolean replaying = Constants.isFullReplayAllowed()
                    && bestChainHeight < config.getBestChainHeightEver();

            long now = new Date().getTime();
            long blockChainHeadTime = blockChain.getChainHead().getHeader().getTime().getTime();
            boolean insideTxExchangeRateTimeThreshold = (now - blockChainHeadTime) < TX_EXCHANGE_RATE_TIME_THRESHOLD_MS;

            if (tx.getExchangeRate() == null && ((!replaying || insideTxExchangeRateTimeThreshold)
                    || tx.getConfidence().getConfidenceType() == ConfidenceType.PENDING)) {
                try {
                    final de.schildbach.wallet.rates.ExchangeRate exchangeRate = AppDatabase.getAppDatabase()
                            .exchangeRatesDao().getRateSync(config.getExchangeCurrencyCode());
                    if (exchangeRate != null) {
                        log.info("Setting exchange rate on received transaction.  Rate:  " + exchangeRate.toString()
                                + " tx: " + tx.getHashAsString());
                        tx.setExchangeRate(new ExchangeRate(Coin.COIN, exchangeRate.getFiat()));
                        application.saveWallet();
                    }
                } catch (Exception e) {
                    log.error("Failed to get exchange rate", e);
                }
            }

            transactionsReceived.incrementAndGet();

            final Address address = WalletUtils.getWalletAddressOfReceived(tx, wallet);
            final Coin amount = tx.getValue(wallet);
            final ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();
            final boolean isRestoringBackup = application.getConfiguration().isRestoringBackup();

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean isReceived = amount.signum() > 0;
                    final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING
                            && (replaying || isRestoringBackup);

                    if (isReceived && !isReplayedTx)
                        notifyCoinsReceived(address, amount, tx.getExchangeRate());
                }
            });
        }

        @Override
        public void onCoinsSent(final Wallet wallet, final Transaction tx, final Coin prevBalance,
                final Coin newBalance) {
            transactionsReceived.incrementAndGet();
        }
    };

    private void notifyCoinsReceived(@Nullable final Address address, final Coin amount,
            @Nullable ExchangeRate exchangeRate) {
        if (notificationCount == 1)
            nm.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED);

        notificationCount++;
        notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
        if (address != null && !notificationAddresses.contains(address))
            notificationAddresses.add(address);

        final MonetaryFormat btcFormat = config.getFormat();

        final String packageFlavor = application.applicationPackageFlavor();
        String msgSuffix = packageFlavor != null ? " [" + packageFlavor + "]" : "";

        if (exchangeRate != null) {
            exchangeRate.coinToFiat(amount);
            MonetaryFormat format = Constants.LOCAL_FORMAT.code(0,
                    PREFIX_ALMOST_EQUAL_TO + exchangeRate.fiat.getCurrencyCode());
            msgSuffix += " " + format.format(exchangeRate.coinToFiat(amount));
        }

        final String tickerMsg = getString(R.string.notification_coins_received_msg, btcFormat.format(amount))
                + msgSuffix;
        final String msg = getString(R.string.notification_coins_received_msg,
                btcFormat.format(notificationAccumulatedAmount)) + msgSuffix;

        final StringBuilder text = new StringBuilder();
        for (final Address notificationAddress : notificationAddresses) {
            if (text.length() > 0)
                text.append(", ");

            final String addressStr = notificationAddress.toString();
            final String label = AddressBookProvider.resolveLabel(getApplicationContext(), addressStr);
            text.append(label != null ? label : addressStr);
        }

        final NotificationCompat.Builder notification = new NotificationCompat.Builder(this,
                Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS)
                .setSmallIcon(R.drawable.ic_pepepow_logo);
        notification.setTicker(tickerMsg);
        notification.setContentTitle(msg);
        if (text.length() > 0)
            notification.setContentText(text);
        notification.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, WalletActivity.class), 0));
        notification.setNumber(notificationCount == 1 ? 0 : notificationCount);
        notification.setWhen(System.currentTimeMillis());
        notification.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received));
        nm.notify(Constants.NOTIFICATION_ID_COINS_RECEIVED, notification.getNotification());
    }

    private final class PeerConnectivityListener
            implements PeerConnectedEventListener, PeerDisconnectedEventListener, OnSharedPreferenceChangeListener {
        private int peerCount;
        private AtomicBoolean stopped = new AtomicBoolean(false);

        public PeerConnectivityListener() {
            config.registerOnSharedPreferenceChangeListener(this);
        }

        public void stop() {
            stopped.set(true);

            config.unregisterOnSharedPreferenceChangeListener(this);
            handler.removeCallbacks(peerGroupConnectionTimeoutRunnable);
            handler.removeCallbacks(peerGroupBackoffRunnable);

            nm.cancel(Constants.NOTIFICATION_ID_CONNECTED);
        }

        @Override
        public void onPeerConnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            try {
                final int localHeight = blockChain != null ? blockChain.getBestChainHeight() : -1;
                log.info("SPV[sid={}] peer=connected addr={} peerBestHeight={} localHeight={} peersConnected={}",
                        FASTBOOT_SESSION_ID, peer.getAddress(), peer.getBestHeight(), localHeight, peerCount);
            } catch (Exception e) {
                log.debug("SPV peer connected (log suppressed due to exception): {}", e.getMessage());
            }
            changed(peerCount);
        }

        @Override
        public void onPeerDisconnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            try {
                final int localHeight = blockChain != null ? blockChain.getBestChainHeight() : -1;
                log.info("SPV[sid={}] peer=disconnected addr={} peerBestHeight={} localHeight={} peersConnected={}",
                        FASTBOOT_SESSION_ID, peer.getAddress(), peer.getBestHeight(), localHeight, peerCount);
            } catch (Exception e) {
                log.debug("SPV peer disconnected (log suppressed due to exception): {}", e.getMessage());
            }
            changed(peerCount);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION.equals(key)) {
                changed(peerCount);
            } else if (Configuration.PREFS_KEY_SYNC_MODE.equals(key)) {
                log.info("Sync mode changed, restarting sync pipeline...");
                stopPeerGroup(application.getWallet());
                // Re-initialize the pipeline with the new mode
                initSyncPipeline();
            }
        }

        private void changed(final int numPeers) {
            if (stopped.get())
                return;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean connectivityNotificationEnabled = config.getConnectivityNotificationEnabled();
                    if (numPeers > 0) {
                        cancelPeerGroupConnectTimeout();
                        resetPeerGroupBackoff();
                    }

                    if (!connectivityNotificationEnabled || numPeers == 0) {
                        nm.cancel(Constants.NOTIFICATION_ID_CONNECTED);
                    } else {
                        final Notification.Builder notification = new Notification.Builder(BlockchainServiceImpl.this);
                        notification.setSmallIcon(R.drawable.stat_sys_peers, numPeers > 4 ? 4 : numPeers);
                        notification.setContentTitle(getString(R.string.app_name));
                        notification.setContentText(getString(R.string.notification_peers_connected_msg, numPeers));
                        notification.setContentIntent(PendingIntent.getActivity(BlockchainServiceImpl.this, 0,
                                new Intent(BlockchainServiceImpl.this, WalletActivity.class), 0));
                        notification.setWhen(System.currentTimeMillis());
                        notification.setOngoing(true);
                        nm.notify(Constants.NOTIFICATION_ID_CONNECTED, notification.getNotification());
                    }

                    // send broadcast
                    broadcastPeerState(numPeers);
                }
            });
        }
    }

    private final PeerDataEventListener blockchainDownloadListener = new DownloadProgressTracker() {
        private final AtomicLong lastMessageTime = new AtomicLong(0);

        @Override
        public void onBlocksDownloaded(final Peer peer, final Block block, final FilteredBlock filteredBlock,
                final int blocksLeft) {
            super.onBlocksDownloaded(peer, block, filteredBlock, blocksLeft);
            delayHandler.removeCallbacksAndMessages(null);

            final long now = System.currentTimeMillis();
            if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS)
                delayHandler.post(runnable);
            else
                delayHandler.postDelayed(runnable, BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS);

        }

        private final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                lastMessageTime.set(System.currentTimeMillis());

                config.maybeIncrementBestChainHeightEver(blockChain.getChainHead().getHeight());
                if (config.isRestoringBackup()) {
                    long timeAgo = System.currentTimeMillis()
                            - blockChain.getChainHead().getHeader().getTimeSeconds() * 1000;
                    // if the app was restoring a backup from a file or seed and block chain is
                    // nearly synced
                    // then turn off the restoring indicator
                    if (timeAgo < DateUtils.DAY_IN_MILLIS)
                        config.setRestoringBackup(false);
                }
                broadcastBlockchainState();
            }
        };

        @Override
        public void progress(double pct, int blocksLeft, Date date) {
            if (isApiMode() && fastBootCompleted) {
                // In FAST_API_10POW, once bootstrap is done, we want the UI
                // to stay at 100% even if SPV background sync is catching up.
                pct = 100.0;
            }
            super.progress(pct, blocksLeft, date);
            if (pct < 0) {
                pct = 0;
            }
            final SyncProgressEvent event = new SyncProgressEvent(pct);
            log.info(event.toString());
            EventBus.getDefault().postSticky(event);

        }

        @Override
        public void doneDownload() {
            super.doneDownload();
            final SyncProgressEvent event = new SyncProgressEvent(100);
            log.info(event.toString());
            EventBus.getDefault().postSticky(event);

        }
    };

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                final NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
                final boolean hasConnectivity = networkInfo != null && networkInfo.isConnected();

                if (log.isInfoEnabled()) {
                    final StringBuilder s = new StringBuilder("active network is ")
                            .append(hasConnectivity ? "up" : "down");
                    if (networkInfo != null) {
                        s.append(", type: ").append(networkInfo.getTypeName());
                        s.append(", state: ").append(networkInfo.getState()).append('/')
                                .append(networkInfo.getDetailedState());
                        final String extraInfo = networkInfo.getExtraInfo();
                        if (extraInfo != null)
                            s.append(", extraInfo: ").append(extraInfo);
                        final String reason = networkInfo.getReason();
                        if (reason != null)
                            s.append(", reason: ").append(reason);
                    }
                    log.info(s.toString());
                }

                if (hasConnectivity) {
                    impediments.remove(Impediment.NETWORK);
                    resetPeerGroupBackoff();
                } else {
                    impediments.add(Impediment.NETWORK);
                    handler.removeCallbacks(peerGroupBackoffRunnable);
                }
                updatePeerGroup();
            } else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                log.info("device storage low");

                impediments.add(Impediment.STORAGE);
                updatePeerGroup();
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                log.info("device storage ok");

                impediments.remove(Impediment.STORAGE);
                updatePeerGroup();
            }
        }
    };

    @SuppressLint("Wakelock")
    private void updatePeerGroup() {
        if (!spvInitialized && isApiMode()) {
            log.info(
                    "START-PEERGROUP: updatePeerGroup returning early because bootstrap/SPV not ready in API mode. spvInitialized={}",
                    spvInitialized);
            return;
        }

        final Wallet wallet = application.getWallet();
        final boolean apiMode = isApiMode();

        if (impediments.contains(Impediment.NETWORK)) {
            final SyncProgressEvent event = new SyncProgressEvent(0, true);
            log.info(event.toString());
            EventBus.getDefault().postSticky(event);
        }

        final long now = SystemClock.elapsedRealtime();
        if (impediments.isEmpty() && peerGroup == null) {
            if (now < nextPeerGroupStartTimeMs) {
                final long delay = nextPeerGroupStartTimeMs - now;
                log.info("PeerGroup restart delayed for {} ms to prevent ANR loop", delay);
                handler.removeCallbacks(peerGroupBackoffRunnable);
                handler.postDelayed(peerGroupBackoffRunnable, delay);
                return;
            }

            log.debug("acquiring wakelock");
            wakeLock.acquire();

            // consistency check
            final int walletLastBlockSeenHeight = wallet.getLastBlockSeenHeight();
            final int bestChainHeight = blockChain.getBestChainHeight();
            if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                final String message = "wallet/blockchain out of sync: " + walletLastBlockSeenHeight + "/"
                        + bestChainHeight;
                log.error(message);
                CrashReporter.saveBackgroundTrace(new RuntimeException(message), application.packageInfo());
            }

            log.info("START-PEERGROUP: starting peergroup");
            log.info("starting peergroup");
            log.info("SPV[sid={}] peerGroup=start", FASTBOOT_SESSION_ID);
            peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain);
            // Task B: Log PeerGroup wiring with identityHashCode
            log.info("SPV-CHAIN[sid={}] peerGroup attached to blockChain instanceId={}",
                    FASTBOOT_SESSION_ID, System.identityHashCode(blockChain));
            peerGroup.setRequiredServices(VersionMessage.NODE_NETWORK);

            peerGroup.addWallet(wallet);
            peerGroup.setUserAgent(Constants.USER_AGENT, application.packageInfo().versionName);
            peerGroup.addConnectedEventListener(peerConnectivityListener);
            peerGroup.addDisconnectedEventListener(peerConnectivityListener);

            final int maxConnectedPeers = application.maxConnectedPeers();

            final String trustedPeerHost = config.getTrustedPeerHost();
            final boolean hasTrustedPeer = trustedPeerHost != null;

            final boolean connectTrustedPeerOnly = hasTrustedPeer && config.getTrustedPeerOnly();
            peerGroup.setMaxConnections(connectTrustedPeerOnly ? 1 : maxConnectedPeers);
            peerGroup.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS);
            peerGroup.setPeerDiscoveryTimeoutMillis(Constants.PEER_DISCOVERY_TIMEOUT_MS);

            // Stability contract: FAST_API_10POW must never enable bitcoinj core "FAST"
            // behavior.
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
            Peer.FAST_API_10POW_ENABLED_FOR_CORE = false;
            if (selectedSyncMode == SyncMode.FAST_API_10POW && fastBootState == FastBootState.SUCCEEDED) {
                // Relax stall detection for FAST_API_10POW to avoid thrashing on slow networks
                // Default is 10s / ~800 bytes/sec. We relax to 20s / 500 bytes/sec.
                peerGroup.setStallThreshold(20, 500);
                log.info("FAST_API_10POW: Relaxed stall detection to 20s / 500 bytes/sec (overlay-only)");
            }

            if (apiMode && blockChain != null && fastBootState == FastBootState.SUCCEEDED) {
                // TASK 2: Fix P2P Start Time
                // Trust the API tip time if we are in API mode and have it.
                // This prevents P2P from trying to download from Genesis or "Height 1".
                long tipTimeSecs = blockChain.getChainHead().getHeader().getTimeSeconds();
                long fastCatchupTime = Math.max(0, tipTimeSecs - TimeUnit.DAYS.toSeconds(1));

                // If we possess a specific API best chain date (from bootstrap), use that if
                // it's newer.
                if (apiBestChainDate != null && apiBestChainDate.getTime() > 0) {
                    long apiTipTime = apiBestChainDate.getTime() / 1000;
                    if (apiTipTime > fastCatchupTime) {
                        fastCatchupTime = Math.max(0, apiTipTime - TimeUnit.HOURS.toSeconds(4)); // 4hr buffer
                    }
                }

                peerGroup.setFastCatchupTimeSecs(fastCatchupTime);
                log.info(
                        "FAST-BOOT: Configured fast catch-up at time={} (approx height={}).",
                        fastCatchupTime, blockChain.getChainHead().getHeight());
            }

            if (apiMode && fastBootState == FastBootState.SUCCEEDED) {
                peerGroup.setDownloadTxDependencies(0);
                log.info("FAST-CHAIN: {} mode – disabling tx dependency downloads and historical SPV catch-up.",
                        selectedSyncMode);
            }

            peerGroup.addPeerDiscovery(new PeerDiscovery() {
                private final PeerDiscovery normalPeerDiscovery = new MultiplexingDiscovery(
                        Constants.NETWORK_PARAMETERS, peerDiscoveryList);

                @Override
                public InetSocketAddress[] getPeers(final long services, final long timeoutValue,
                        final TimeUnit timeoutUnit) throws PeerDiscoveryException {
                    final List<InetSocketAddress> peers = new LinkedList<>();

                    boolean needsTrimPeersWorkaround = false;

                    if (hasTrustedPeer) {
                        log.info("trusted peer '" + trustedPeerHost + "'" + (connectTrustedPeerOnly ? " only" : ""));

                        final InetSocketAddress addr = new InetSocketAddress(trustedPeerHost,
                                Constants.NETWORK_PARAMETERS.getPort());
                        if (addr.getAddress() != null) {
                            peers.add(addr);
                            needsTrimPeersWorkaround = true;
                        }
                    }

                    if (!connectTrustedPeerOnly) {
                        final InetSocketAddress hardcodedPeer = Constants.HARDCODED_PEER;
                        if (hardcodedPeer != null && !peers.contains(hardcodedPeer)) {
                            log.info("Adding hardcoded peer for testing: {}", hardcodedPeer);
                            peers.add(hardcodedPeer);
                            needsTrimPeersWorkaround = true;
                        }

                        try {
                            peers.addAll(Arrays.asList(
                                    normalPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                        } catch (PeerDiscoveryException x) {
                            // swallow and continue with another method of connection.
                            log.info("DNS peer discovery failed: " + x.getMessage());
                            if (x.getCause() != null)
                                log.info("cause:  " + x.getCause().getMessage());
                        }
                        if (peers.size() < 10) {
                            if (Constants.NETWORK_PARAMETERS.getAddrSeeds() != null) {
                                log.info(
                                        "DNS peer discovery returned less than 10 nodes. Adding seed peers to the list to increase connections");
                                peers.addAll(Arrays.asList(
                                        seedPeerDiscovery.getPeers(services, timeoutValue, timeoutUnit)));
                            } else {
                                log.info(
                                        "DNS peer discovery returned less than 10 nodes. Unable to add seed peers (it is not specified for this network).");
                            }
                        }
                    }

                    // workaround because PeerGroup will shuffle peers
                    if (needsTrimPeersWorkaround)
                        while (peers.size() >= maxConnectedPeers)
                            peers.remove(peers.size() - 1);

                    return peers.toArray(new InetSocketAddress[0]);
                }

                @Override
                public void shutdown() {
                    normalPeerDiscovery.shutdown();
                }
            });

            // start peergroup
            log.info("START-PEERGROUP: calling peerGroup.startAsync()");
            peerGroup.startAsync();

            if (apiMode) {
                if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                    if (fastBootState != FastBootState.SUCCEEDED) {
                        log.warn("FAST-BOOT: snapshot failed or not succeeded, falling back to normal SPV download.");
                        peerGroup.addBlocksDownloadedEventListener(blockchainDownloadListener);
                        log.info(
                                "SPV[sid={}] download=begin source=bitcoinj localHeight={} bestPeerHeight={} apiTip={}",
                                FASTBOOT_SESSION_ID,
                                blockChain != null ? blockChain.getBestChainHeight() : -1,
                                peerGroup != null ? peerGroup.getMostCommonChainHeight() : 0,
                                apiBestChainHeight);
                        // Task D: Sanity check before download
                        verifyPeerGroupBlockChainInstance();
                        peerGroup.startBlockChainDownload(blockchainDownloadListener);
                    } else {
                        log.info(
                                "[P2P-POST-SNAPSHOT] FAST_API_10POW active & SUCCEEDED. Starting SPV download to sync from local head.");
                        peerGroup.addBlocksDownloadedEventListener(blockchainDownloadListener);
                        // ALWAYS start download. No skipping.
                        log.info(
                                "SPV[sid={}] download=begin source=bitcoinj localHeight={} bestPeerHeight={} apiTip={}",
                                FASTBOOT_SESSION_ID,
                                blockChain != null ? blockChain.getBestChainHeight() : -1,
                                peerGroup != null ? peerGroup.getMostCommonChainHeight() : 0,
                                apiBestChainHeight);
                        // Task D: Sanity check before download
                        verifyPeerGroupBlockChainInstance();
                        peerGroup.startBlockChainDownload(blockchainDownloadListener);
                    }
                } else {
                    log.info("[P2P-POST-SNAPSHOT] Enabling startBlockChainDownload in API mode.");
                    peerGroup.addBlocksDownloadedEventListener(blockchainDownloadListener);
                    log.info("SPV[sid={}] download=begin source=bitcoinj", FASTBOOT_SESSION_ID);
                    // Task D: Sanity check before download
                    verifyPeerGroupBlockChainInstance();
                    peerGroup.startBlockChainDownload(blockchainDownloadListener);
                }
            } else {
                log.info("Starting P2P BlockChain Download...");
                log.info("SPV[sid={}] download=begin source=bitcoinj", FASTBOOT_SESSION_ID);
                // Task D: Sanity check before download
                verifyPeerGroupBlockChainInstance();
                peerGroup.startBlockChainDownload(blockchainDownloadListener);
            }
            // Start sync stall watchdog after download begins
            startSyncWatchdog();
            schedulePeerGroupConnectTimeout();

        } else if (!impediments.isEmpty() && peerGroup != null) {
            stopPeerGroup(wallet);
        }

        broadcastBlockchainState();
    }

    // startApiSync removed

    private void triggerFastSyncFallback() {
        if (selectedSyncMode != SyncMode.FAST_API_10POW) {
            return;
        }

        log.error(
                "FAST_API_10POW: Orphan/Merkle failure detected. Logging warning only (no user fallback in API mode).");
    }

    /**
     * Task D: Regression guard - verify PeerGroup is using the canonical BlockChain
     * instance.
     * Uses reflection to access PeerGroup's protected 'chain' field.
     * Logs ERROR if mismatch detected, but does NOT crash.
     */
    private void verifyPeerGroupBlockChainInstance() {
        if (peerGroup == null || blockChain == null) {
            log.warn("SPV-CHAIN[sid={}] sanityCheck: peerGroup={} blockChain={} - skipping verification",
                    FASTBOOT_SESSION_ID,
                    peerGroup != null ? "exists" : "null",
                    blockChain != null ? "exists" : "null");
            return;
        }

        try {
            java.lang.reflect.Field chainField = PeerGroup.class.getDeclaredField("chain");
            chainField.setAccessible(true);
            Object peerGroupChain = chainField.get(peerGroup);

            int canonicalId = System.identityHashCode(blockChain);
            int peerGroupChainId = System.identityHashCode(peerGroupChain);

            if (peerGroupChain != blockChain) {
                log.error("SPV-CHAIN[sid={}] MISMATCH DETECTED! canonicalBlockChainId={} peerGroupChainId={} " +
                        "- blocks downloaded by PeerGroup will NOT advance the observed chain!",
                        FASTBOOT_SESSION_ID, canonicalId, peerGroupChainId);
            } else {
                log.info("SPV-CHAIN[sid={}] sanityCheck PASSED: canonicalBlockChainId={} == peerGroupChainId={}",
                        FASTBOOT_SESSION_ID, canonicalId, peerGroupChainId);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("SPV-CHAIN[sid={}] sanityCheck: unable to verify via reflection: {}",
                    FASTBOOT_SESSION_ID, e.getMessage());
        }
    }

    private void resetBlockStoreForFullSpvFallback() {
        if (selectedSyncMode == SyncMode.FAST_API_10POW) {
            log.warn("BLOCKSTORE_DELETE_PREVENTED: resetBlockStoreForFullSpvFallback BLOCKED in FAST_API_10POW mode. " +
                    "sid={} fastState={} reason=overlay_mode_active", FASTBOOT_SESSION_ID, fastBootState);
            return;
        }
        log.warn("resetBlockStoreForFullSpvFallback: Deleting blockstore and clearing checkpoint configuration.");
        try {
            if (blockStore != null) {
                blockStore.close();
                blockStore = null;
            }
            if (blockChainFile != null && blockChainFile.exists()) {
                blockChainFile.delete();
            }
            // Reset API offset hints
            config.setApiSpvOffset(0);
            config.setLastFastBootstrapSuccess(false);
        } catch (Exception e) {
            log.error("Error resetting blockstore for fallback", e);
        }
    }

    private void showApiFallbackToast() {
        Runnable toastRunnable = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(BlockchainServiceImpl.this,
                        "Fast sync failed. You may switch to FULL_SPV mode (slower but safe).",
                        Toast.LENGTH_LONG).show();
            }
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            toastRunnable.run();
        } else {
            handler.post(toastRunnable);
        }
    }

    private boolean isBootstrapConnectivityFailure(@Nullable ApiPowBootstrapper.BootstrapResult result) {
        if (result == null) {
            return true;
        }
        if (result.explorerTipHeight <= 0) {
            return true;
        }

        final String reason = result.failureReason != null ? result.failureReason : "";
        if ("fetch-tip-failed".equals(reason) || "tip-header-missing".equals(reason)) {
            return true;
        }
        final String lowerReason = reason.toLowerCase();
        if (reason.startsWith("exception-") && (lowerReason.contains("api response")
                || lowerReason.contains("timeout") || lowerReason.contains("network")
                || lowerReason.contains("unknown host"))) {
            return true;
        }
        return false;
    }

    private void publishApiSyncComplete(int explorerTipHeight, long chainHeadTimeSeconds) {
        if (!isApiMode()) {
            return;
        }
        updateApiTipMetadata(chainHeadTimeSeconds, explorerTipHeight);
        EventBus.getDefault().postSticky(new SyncProgressEvent(100));
    }

    private void recordBootstrapSuccessState(@Nullable StoredBlock head, int explorerTipHeight) {
        if (head == null) {
            return;
        }
        try {
            config.setLastFastBootstrapSuccess(true);
            config.setLastFastBootstrapHeadHeight(head.getHeight());
            config.setLastFastBootstrapExplorerTip(explorerTipHeight);
            config.setLastFastBootstrapTime(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("FAST-BOOT: Failed to persist bootstrap success metadata", e);
        }
    }

    private void updateApiSyncStateFromStore() {
        if (blockStore == null) {
            return;
        }

        try {
            final StoredBlock head = blockStore.getChainHead();
            if (head != null) {
                apiBestChainHeight = head.getHeight();
                apiBestChainDate = head.getHeader().getTime();
                apiSyncPercentage = 100;
                application.updateApiCheckpoint(head.getHeight(), head.getHeader().getHashAsString());
                application.publishApiStatus(ApiStatus.State.HEALTHY, null, 200);
            }
        } catch (BlockStoreException e) {
            log.error("Failed to read API chain head", e);
        }
    }

    private void snapshotChainHeadToApiTipIfNeeded(int apiTipHeight) {
        // Stability hard-guard:
        // FAST_API_10POW is an overlay only and MUST NOT write to blockstore or mutate
        // chain head.
        log.warn(
                "FASTBOOT[sid={}] FAST overlay: snapshotChainHeadToApiTipIfNeeded disabled (no blockstore/chainHead writes)",
                FASTBOOT_SESSION_ID);
    }

    private void triggerFastSyncFailureWarning() {
        final FastBootState currentState = FASTBOOT_SESSION_STATE.get();
        final String reason = lastBootstrapResult != null ? lastBootstrapResult.failureReason : "unknown";
        final boolean spvStarted = spvInitialized || peerGroup != null;

        // Guard: Only send broadcast once per session, and only on RUNNING ->
        // DISABLED_SESSION transition
        if (currentState != FastBootState.DISABLED_SESSION) {
            log.info(
                    "FAST-BOOT[sid={}] fast_failed_broadcast suppressed reason=state_not_disabled fastState={} spvStarted={}",
                    FASTBOOT_SESSION_ID, currentState, spvStarted);
            return;
        }

        // Guard: Suppress broadcast if SPV is already active
        if (spvStarted) {
            log.info(
                    "FAST-BOOT[sid={}] fast_failed_broadcast suppressed reason=spv_already_active fastState={} spvStarted={}",
                    FASTBOOT_SESSION_ID, currentState, spvStarted);
            return;
        }

        if (!FASTBOOT_FAILURE_BROADCAST_SENT.compareAndSet(false, true)) {
            log.info(
                    "FAST-BOOT[sid={}] fast_failed_broadcast suppressed reason=already_sent_this_session fastState={} spvStarted={}",
                    FASTBOOT_SESSION_ID, currentState, spvStarted);
            return;
        }

        log.error("FAST-BOOT[sid={}] fast_failed_broadcast sent reason={} fastState={} spvStarted={}",
                FASTBOOT_SESSION_ID, reason, currentState, spvStarted);

        Intent intent = new Intent("de.schildbach.wallet.service.ACTION_FAST_SYNC_FAILED");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        showApiFallbackToast();
    }

    private void schedulePeerGroupConnectTimeout() {
        handler.removeCallbacks(peerGroupConnectionTimeoutRunnable);
        handler.postDelayed(peerGroupConnectionTimeoutRunnable, PEER_CONNECT_TIMEOUT_MS);
    }

    private void cancelPeerGroupConnectTimeout() {
        handler.removeCallbacks(peerGroupConnectionTimeoutRunnable);
    }

    private void applyPeerGroupBackoff() {
        stopPeerGroup(application.getWallet());
        nextPeerGroupStartTimeMs = SystemClock.elapsedRealtime() + PEER_GROUP_FAILURE_BACKOFF_MS;
        handler.removeCallbacks(peerGroupBackoffRunnable);
        handler.postDelayed(peerGroupBackoffRunnable, PEER_GROUP_FAILURE_BACKOFF_MS);
    }

    private void resetPeerGroupBackoff() {
        nextPeerGroupStartTimeMs = 0L;
        handler.removeCallbacks(peerGroupBackoffRunnable);
    }

    // Sync Stall Watchdog (Task B): Start watchdog when download begins
    private void startSyncWatchdog() {
        if (watchdogRunning) {
            return; // Already running
        }
        log.info("SPV-WATCHDOG[sid={}] starting syncWatchdog localHeight={}",
                FASTBOOT_SESSION_ID, blockChain != null ? blockChain.getBestChainHeight() : -1);
        chainAdvancedAtLeastOnce.set(false);
        syncStartedTimeMs = System.currentTimeMillis();
        watchdogRunning = true;
        handler.postDelayed(syncWatchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    // Sync Stall Watchdog (Task B): Stop watchdog
    private void stopSyncWatchdog() {
        if (!watchdogRunning) {
            return;
        }
        log.info("SPV-WATCHDOG[sid={}] stopping syncWatchdog chainAdvancedOnce={}",
                FASTBOOT_SESSION_ID, chainAdvancedAtLeastOnce.get());
        watchdogRunning = false;
        handler.removeCallbacks(syncWatchdogRunnable);
    }

    private final static class ActivityHistoryEntry {
        public final int numTransactionsReceived;
        public final int numBlocksDownloaded;

        public ActivityHistoryEntry(final int numTransactionsReceived, final int numBlocksDownloaded) {
            this.numTransactionsReceived = numTransactionsReceived;
            this.numBlocksDownloaded = numBlocksDownloaded;
        }

        @Override
        public String toString() {
            return numTransactionsReceived + "/" + numBlocksDownloaded;
        }
    }

    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
        private int lastChainHeight = 0;
        private final List<ActivityHistoryEntry> activityHistory = new LinkedList<ActivityHistoryEntry>();

        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (blockChain == null) {
                lastChainHeight = 0;
                return;
            }

            final int chainHeight = blockChain.getBestChainHeight();

            if (lastChainHeight > 0) {
                final int numBlocksDownloaded = chainHeight - lastChainHeight;
                final int numTransactionsReceived = transactionsReceived.getAndSet(0);

                // push history
                activityHistory.add(0, new ActivityHistoryEntry(numTransactionsReceived, numBlocksDownloaded));

                // trim
                while (activityHistory.size() > MAX_HISTORY_SIZE)
                    activityHistory.remove(activityHistory.size() - 1);

                // print
                final StringBuilder builder = new StringBuilder();
                for (final ActivityHistoryEntry entry : activityHistory) {
                    if (builder.length() > 0)
                        builder.append(", ");
                    builder.append(entry);
                }
                log.info("History of transactions/blocks: " + builder);

                // determine if block and transaction activity is idling
                boolean isIdle = false;
                if (activityHistory.size() >= MIN_COLLECT_HISTORY) {
                    isIdle = true;
                    for (int i = 0; i < activityHistory.size(); i++) {
                        final ActivityHistoryEntry entry = activityHistory.get(i);
                        final boolean blocksActive = entry.numBlocksDownloaded > 0 && i <= IDLE_BLOCK_TIMEOUT_MIN;
                        final boolean transactionsActive = entry.numTransactionsReceived > 0
                                && i <= IDLE_TRANSACTION_TIMEOUT_MIN;

                        if (blocksActive || transactionsActive) {
                            isIdle = false;
                            break;
                        }
                    }
                }

                // if idling, shutdown service
                if (isIdle) {
                    log.info("idling detected, stopping service");
                    stopSelf();
                }
            }

            lastChainHeight = chainHeight;
        }
    };

    public class LocalBinder extends Binder {
        public BlockchainService getService() {
            return BlockchainServiceImpl.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(final Intent intent) {
        super.onBind(intent);
        log.debug(".onBind()");

        return mBinder;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        log.debug(".onUnbind()");

        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        selectedSyncMode = SyncMode.FULL_SPV;
        serviceCreatedAt = System.currentTimeMillis();
        log.info(".onCreate() thread={}, initial mode={}", Thread.currentThread().getName(), selectedSyncMode);

        // Round-1: service instance may be recreated; keep FASTBOOT state
        // process-stable.
        fastBootState = FASTBOOT_SESSION_STATE.get();
        log.info("FASTBOOT[sid={}] service onCreate fastState={}", FASTBOOT_SESSION_ID, fastBootState);

        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        final String lockName = getPackageName() + " blockchain sync";

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

        application = (WalletApplication) getApplication();
        config = application.getConfiguration();
        // Wallet is NOT loaded here yet. It will be loaded in startAfterBootstrapAsync.
        // final Wallet wallet = application.getWallet();
        selectedSyncMode = config.getSyncMode();
        application.refreshExplorerStats(true);

        peerConnectivityListener = new PeerConnectivityListener();

        broadcastPeerState(0);

        blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);

        // Start initialization sequence
        initSyncPipeline();
    }

    private void initSyncPipeline() {
        Log.i(FASTBOOT, "initSyncPipeline(): starting bootstrap sequence, mode=" + config.getSyncMode());
        SyncMode configuredMode = config.getSyncMode();
        if (forceFullSpvThisSession.get() && configuredMode != SyncMode.FULL_SPV) {
            log.warn("FAST_API_10POW: forcing FULL_SPV for this session due to previous bootstrap failure");
        }
        selectedSyncMode = forceFullSpvThisSession.get() ? SyncMode.FULL_SPV : configuredMode;

        // Framework refinement: FAST_API_10POW disablement is session-only; do not
        // persist "failed" state.
        // (Failure cooldown is handled separately via
        // lastApiBootstrapFailureTimeMillis.)
        try {
            if (config.isFastApiSyncFailed()) {
                config.setFastApiSyncFailed(false);
            }
        } catch (Exception ignore) {
            // Best-effort cleanup of legacy flag.
        }

        // Round-1: enforce session-level disablement for FAST_API_10POW (no re-entry,
        // no retry).
        // Do NOT switch modes; only force-disable the overlay flags when the session is
        // disabled.
        final FastBootState sessionFastState = FASTBOOT_SESSION_STATE.get();
        if (selectedSyncMode == SyncMode.FAST_API_10POW && sessionFastState == FastBootState.DISABLED_SESSION) {
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
            AbstractBlockChain.FAST_API_10POW_ENABLED = false;
            AbstractBlockChain.API_MODE_NO_HISTORY = false;
            log.warn("FASTBOOT[sid={}] session already DISABLED_SESSION; forcing overlay flags OFF",
                    FASTBOOT_SESSION_ID);
        }

        // Stability contract:
        // FAST_API_10POW is UI overlay-only and MUST NOT enable any bitcoinj core
        // "FAST" behavior.
        Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
        AbstractBlockChain.FAST_API_10POW_ENABLED = false;
        AbstractBlockChain.API_MODE_NO_HISTORY = (selectedSyncMode == SyncMode.API_1000POW);

        if (!AbstractBlockChain.API_MODE_NO_HISTORY) {
            AbstractBlockChain.API_SNAPSHOT_TIP_HEIGHT = -1;
            AbstractBlockChain.API_SNAPSHOT_TIP_HASH = null;
        }

        // Register the fallback trigger
        AbstractBlockChain.FAST_SYNC_FALLBACK_TRIGGER = () -> {
            log.error("FAST_API_10POW: FALLBACK TRIGGERED by AbstractBlockChain/Peer!");
            triggerFastSyncFallback();
        };

        log.info("Initializing sync pipeline. Mode: {} (thread={})", selectedSyncMode,
                Thread.currentThread().getName());

        // Set global flags based on mode - INITIAL STATE
        // We start with them DISABLED until bootstrap succeeds, unless it's just a
        // restart with success already
        // But for safety, let's keep them matched to selectedSyncMode for now, BUT
        // `runBootstrapIfNeeded` will kill them if it fails.
        // Actually, adhering to the plan: "Strictly guard".
        // Let's set them potentially true here, but `runBootstrapIfNeeded` is the
        // authority.
        Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
        AbstractBlockChain.FAST_API_10POW_ENABLED = false;

        if (initExecutor.isShutdown()) {
            log.warn("initSyncPipeline: initExecutor is shutdown, ignoring.");
            return;
        }

        // Unified startup sequence: Load Wallet -> Bootstrap (if needed) -> Init SPV
        initExecutor.execute(() -> {
            Thread.currentThread().setName("blockchain-init");
            org.bitcoinj.core.Context.propagate(((WalletApplication) getApplication()).getBitcoinContext());

            log.info("startSyncSequence: Step 1 - Loading wallet...");
            application.loadWallet();
            final Wallet wallet = application.getWallet();

            if (wallet == null) {
                log.error("Wallet failed to load! Cannot start blockchain service.");
                spvReady.set(false);
                stopSelf();
                return;
            }

            // TASK 1: Explicit readiness checks
            if (wallet.getKeyChainGroupSize() == 0) {
                log.warn("FAST-BOOT: Wallet loaded but KeychainGroup is empty! potential issue.");
            }

            log.info("startSyncSequence: Step 2 - Running bootstrap if needed (Mode={})", selectedSyncMode);
            if (selectedSyncMode == SyncMode.FAST_API_10POW || selectedSyncMode == SyncMode.API_1000POW) {
                runBootstrapIfNeeded(wallet);
            } else {
                // Non-API modes don't need bootstrap
                log.info("startSyncSequence: Skipping bootstrap for mode {}", selectedSyncMode);
            }

            log.info("startSyncSequence: Step 3 - Intializing SPV...");
            initializeSpv(wallet);

            if (!spvInitialized) {
                log.warn("SPV initialization did not complete successfully; skipping peer start for now.");
                spvReady.set(false);
            } else {
                spvReady.set(true);
                // Trigger PeerGroup update now that SPV is ready
                handler.post(this::updatePeerGroup);
            }
        });
    }

    private void runBootstrapIfNeeded(Wallet wallet) {
        // Mandatory debug contract: entry log (always).
        final long lastRunTs = FASTBOOT_LAST_RUN_TS_MS.get();
        final FastBootState sessionFastStateAtEnter = FASTBOOT_SESSION_STATE.get();
        log.info("FASTBOOT[sid={}] enter runBootstrapIfNeeded", FASTBOOT_SESSION_ID);
        log.info("FASTBOOT[sid={}] invariant: FULL_SPV is canonical; FAST bootstrap NEVER touches blockstore/chainHead",
                FASTBOOT_SESSION_ID);
        log.info("mode={}", selectedSyncMode);
        log.info("fastState={}", sessionFastStateAtEnter);
        log.info("lastRunTs={}", lastRunTs);

        // TASK 1 Checking Wallet Readiness
        if (wallet == null) {
            log.error("[FAST-BOOT] ABORT: Wallet is null!");
            return;
        }

        if (!isApiMode()) {
            return;
        }

        // Round-1: process-lifetime once guard for FAST_API_10POW only (prevents
        // lifecycle re-entry).
        // API_1000POW remains unchanged in this round.
        if (selectedSyncMode == SyncMode.FAST_API_10POW) {
            // Sync instance view from session state (service may have been recreated).
            fastBootState = sessionFastStateAtEnter;

            if (sessionFastStateAtEnter != FastBootState.IDLE) {
                log.info("FASTBOOT[sid={}] ignore runBootstrapIfNeeded reason=state_{}",
                        FASTBOOT_SESSION_ID, sessionFastStateAtEnter);
                return;
            }

            // Only one transition from IDLE -> RUNNING is permitted per process session.
            if (!FASTBOOT_SESSION_STATE.compareAndSet(FastBootState.IDLE, FastBootState.RUNNING)) {
                log.info("FASTBOOT[sid={}] ignore runBootstrapIfNeeded reason=lost_race state={}",
                        FASTBOOT_SESSION_ID, FASTBOOT_SESSION_STATE.get());
                return;
            }

            FASTBOOT_LAST_RUN_TS_MS.set(System.currentTimeMillis());
            fastBootState = FastBootState.RUNNING;
            log.info("FASTBOOT[sid={}] state IDLE -> RUNNING reason=first_call", FASTBOOT_SESSION_ID);
        } else {
            // Legacy per-instance state machine check for non-FAST API modes.
            if (fastBootState == FastBootState.DISABLED_SESSION) {
                log.warn("FAST-BOOT: Skipping execution (DISABLED_SESSION active). SPV will handle sync.");
                return;
            }
        }

        // TASK 1: Robust Time-based Throttle using direct SharedPreferences
        // We bypass 'config' wrapper to ensure persistence works as expected.
        final SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        long lastBootstrapTime = prefs.getLong("lastApiBootstrapTimeMillis", 0L);
        String lastMode = prefs.getString("lastApiBootstrapMode", "");
        long now = System.currentTimeMillis();
        boolean forceRun = false; // logic to force run if needed (e.g. debug)

        // Strict throttle: If recently bootstrapped in same mode, SKIP unconditionally.
        if (!forceRun && selectedSyncMode.name().equals(lastMode)
                && lastBootstrapTime > 0
                && (now - lastBootstrapTime < API_BOOTSTRAP_MIN_INTERVAL_MS)) {

            // Log the skip
            log.info(
                    "FAST-BOOT: skip explorer bootstrap (recently completed within 1 hour; last={} ms ago; mode={}); starting P2P directly.",
                    (now - lastBootstrapTime), lastMode);

            // Signal readiness to UI immediately
            fastBootCompleted = true;
            fastApiBootstrapSucceeded = true;

            // Restore overlay metadata from persisted bootstrap state (read-only).
            int cachedTipHeight = 0;
            int cachedLocalSpvHeight = 0;
            long cachedTipTimeSecs = 0;
            try {
                cachedTipHeight = config.getLastFastBootstrapExplorerTip();
                cachedLocalSpvHeight = config.getLastFastBootstrapHeadHeight();
                cachedTipTimeSecs = TimeUnit.MILLISECONDS.toSeconds(config.getLastFastBootstrapTime());
            } catch (Exception e) {
                log.warn("FAST-BOOT: Failed to read cached bootstrap metadata for throttle skip", e);
            }
            if (cachedTipHeight > 0) {
                updateApiTipMetadata(cachedTipTimeSecs, cachedTipHeight);
                lastBootstrapResult = ApiPowBootstrapper.BootstrapResult.success(
                        cachedLocalSpvHeight, cachedTipHeight, cachedTipHeight, null, cachedTipTimeSecs);
            }

            // CRITICAL: Notify UI that we are synced
            publishApiSyncComplete(apiBestChainHeight, apiBestChainDate.getTime() / 1000);

            if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                // Round-1: treat throttle-skip as a completed session attempt (no re-entry).
                FASTBOOT_SESSION_STATE.set(FastBootState.SUCCEEDED);
                fastBootState = FastBootState.SUCCEEDED;
                log.info("FASTBOOT[sid={}] state RUNNING -> SUCCEEDED reason=throttle_skip_cached",
                        FASTBOOT_SESSION_ID);
            }
            log.info("FASTBOOT[sid={}] post-bootstrap fastState={} overlayEnabled={}",
                    FASTBOOT_SESSION_ID, fastBootState,
                    (selectedSyncMode == SyncMode.FAST_API_10POW && fastBootState == FastBootState.SUCCEEDED
                            && fastApiBootstrapSucceeded));
            return;
        }

        // Failure Cooldown Check
        long lastFailureTime = prefs.getLong("lastApiBootstrapFailureTimeMillis", 0L);
        if (now - lastFailureTime < API_FAILURE_COOLDOWN_MS) {
            log.warn("FAST-BOOT: Skipping bootstrap due to recent failure cooldown ({} ms remaining).",
                    (API_FAILURE_COOLDOWN_MS - (now - lastFailureTime)));
            if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                FASTBOOT_SESSION_STATE.set(FastBootState.DISABLED_COOLDOWN);
                fastBootState = FastBootState.DISABLED_COOLDOWN;
            }
            log.info("FASTBOOT[sid={}] post-bootstrap fastState={} overlayEnabled=false reason=cooldown",
                    FASTBOOT_SESSION_ID, fastBootState);
            return;
        }

        try {
            fastBootState = FastBootState.RUNNING;
            log.info("FAST-BOOT: explorer bootstrap START (mode={}, state={})", selectedSyncMode, fastBootState);

            final de.schildbach.wallet.data.api.ApiPowBootstrapper bootstrapper = application.getBootstrapper();
            fastApiBootstrapAttempted = true;
            fastBootCompleted = false;
            fastApiBootstrapSucceeded = false;

            // STRICT GUARD: FAST_API_10POW must NEVER touch SPVBlockStore during bootstrap.
            // If it's somehow open, we force-close it to ensure pure overlay isolation.
            if (blockStore != null) {
                log.warn(
                        "FAST-BOOT: Violation guard! BlockStore found OPEN during API bootstrap. API bootstrapper will run isolated.");
                // We do NOT strictly need to close it if the bootstrapper doesn't use it,
                // but closing keeps it "pure".
                // However, repeatedly closing/opening might be unstable if init is parallel.
                // Given "runBootstrapIfNeeded" takes "wallet" but not "blockStore", it is safe.
                // Let's just log.
            }

            // Ensure we do NOT create SPVBlockStore here.
            if (selectedSyncMode == SyncMode.FAST_API_10POW && blockChainFile.exists()) {
                log.info("FAST-BOOT: Guaranteed pure overlay. Ignoring existing blockchain file for this phase.");
            }

            long bootstrapStartMs = SystemClock.elapsedRealtime();

            // Run bootstrap purely via API (no blockstore passed)
            ApiPowBootstrapper.BootstrapResult result = bootstrapper.runBootstrapIfNeeded(
                    Constants.NETWORK_PARAMETERS,
                    selectedSyncMode);
            lastBootstrapResult = result;
            log.info("FAST-BOOT: runBootstrapIfNeeded() -> " + result);

            // Accepted result: SUCCESS only. Any failure disables FAST for this session.
            if (result != null && result.success) {
                fastBootState = FastBootState.SUCCEEDED;
                fastApiBootstrapSucceeded = true;
                fastBootCompleted = true; // Signals UI
                apiBestChainHeight = result.explorerTipHeight;

                if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                    FASTBOOT_SESSION_STATE.set(FastBootState.SUCCEEDED);
                    log.info("FASTBOOT[sid={}] state RUNNING -> SUCCEEDED reason=bootstrap_success",
                            FASTBOOT_SESSION_ID);
                }

                config.setFastApiSyncFailed(false);

                // Update Metadata ONLY for UI
                updateApiTipMetadata(result.chainHeadTimeSeconds, apiBestChainHeight);

                // Persist successful bootstrap time and mode DIRECTLY
                prefs.edit()
                        .putLong("lastApiBootstrapTimeMillis", System.currentTimeMillis())
                        .putString("lastApiBootstrapMode", selectedSyncMode.name())
                        .apply();

                try {
                    config.setLastFastBootstrapSuccess(true);
                    // config.setFastApiSyncFailed(false); // Handled above
                    config.setLastFastBootstrapTime(System.currentTimeMillis());
                } catch (Exception e) {
                    /* ignore */ }

                // Run Wallet Snapshot (Overlay logic)
                ApiHeaderClient headerClient = new ApiHeaderClient(config.getApiBaseUrl());
                ApiWalletClient walletClient = new ApiWalletClient(config.getApiBaseUrl());
                ApiWalletSnapshotBootstrapper snapshotBootstrapper = new ApiWalletSnapshotBootstrapper(
                        walletClient,
                        headerClient,
                        config, Constants.NETWORK_PARAMETERS);

                log.info("[FAST-BOOT] Starting Wallet UTXO Snapshot...");
                lastWalletSnapshotResult = snapshotBootstrapper.runWalletSnapshot(wallet, result.explorerTipHeight);

                if (lastWalletSnapshotResult.success) {
                    log.info("[FAST-BOOT] Wallet snapshot success: {}", lastWalletSnapshotResult.importedTxs + " txs");
                } else {
                    log.warn("[FAST-BOOT] Wallet snapshot failed: {}", lastWalletSnapshotResult.failureReason);
                }

                publishApiSyncComplete(apiBestChainHeight, result.chainHeadTimeSeconds);

            } else {
                // Failure Case
                fastBootState = FastBootState.DISABLED_SESSION;
                fastApiBootstrapSucceeded = false;
                apiBestChainHeight = result != null ? result.explorerTipHeight : 0;

                if (result != null && result.explorerTipHeight > 0) {
                    // Overlay-only metadata: keep API tip for UI (even when DISABLED_SESSION).
                    updateApiTipMetadata(result.chainHeadTimeSeconds, result.explorerTipHeight);
                }

                // CRITICAL: Disable globals immediately
                Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
                AbstractBlockChain.FAST_API_10POW_ENABLED = false;
                AbstractBlockChain.API_MODE_NO_HISTORY = false;

                if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                    FASTBOOT_SESSION_STATE.set(FastBootState.DISABLED_SESSION);
                    final String reason = (result != null ? String.valueOf(result.failureReason) : "null_result");
                    log.error("FASTBOOT[sid={}] state RUNNING -> DISABLED_SESSION reason={} exception={}",
                            FASTBOOT_SESSION_ID, reason, ("ApiBootstrapFailure:" + reason));
                    log.info("FAST-BOOT[sid={}] disabled; removed from sync decision path for this session",
                            FASTBOOT_SESSION_ID);
                }

                log.error(
                        "[FAST-BOOT] Bootstrap FAILED (reason={}) -> DISABLED_SESSION. Overlay disabled for this session.",
                        result != null ? result.failureReason : "null");
                log.info("SPV: continuing FULL_SPV sync without FAST overlay");
                // Session-only disablement: do NOT persist "failed" state; FULL_SPV continues
                // normally.
                // GUARD: DO NOT RESET BLOCKSTORE HERE.

                // Overlay-only: still allow a wallet snapshot for UI when API is reachable,
                // but NEVER touch SPV core state.
                if (selectedSyncMode == SyncMode.FAST_API_10POW
                        && result != null
                        && result.explorerTipHeight > 0
                        && !isBootstrapConnectivityFailure(result)) {
                    try {
                        ApiHeaderClient headerClient = new ApiHeaderClient(config.getApiBaseUrl());
                        ApiWalletClient walletClient = new ApiWalletClient(config.getApiBaseUrl());
                        ApiWalletSnapshotBootstrapper snapshotBootstrapper = new ApiWalletSnapshotBootstrapper(
                                walletClient,
                                headerClient,
                                config, Constants.NETWORK_PARAMETERS);
                        log.warn("[FAST-BOOT] DISABLED_SESSION: running overlay wallet snapshot (reason={})",
                                result.failureReason);
                        lastWalletSnapshotResult = snapshotBootstrapper.runWalletSnapshot(wallet,
                                result.explorerTipHeight);
                    } catch (Exception e) {
                        log.warn("[FAST-BOOT] DISABLED_SESSION: overlay wallet snapshot failed", e);
                    }
                }

                // Record failure time
                prefs.edit().putLong("lastApiBootstrapFailureTimeMillis", System.currentTimeMillis()).apply();

                triggerFastSyncFailureWarning();
            }

            long elapsedMs = SystemClock.elapsedRealtime() - bootstrapStartMs;
            log.info("FAST-BOOT: explorer bootstrap DONE (mode={}, elapsed={}ms, state={})",
                    selectedSyncMode, elapsedMs, fastBootState);
            log.info("FASTBOOT[sid={}] post-bootstrap fastState={} overlayEnabled={}",
                    FASTBOOT_SESSION_ID,
                    fastBootState,
                    (selectedSyncMode == SyncMode.FAST_API_10POW && fastBootState == FastBootState.SUCCEEDED
                            && FASTBOOT_SESSION_STATE.get() == FastBootState.SUCCEEDED && fastApiBootstrapSucceeded));

        } catch (Exception e) {
            log.error("[FAST-BOOT] Exception in bootstrap", e);
            fastBootState = FastBootState.DISABLED_SESSION;
            fastApiBootstrapSucceeded = false;

            // CRITICAL: Disable globals immediately
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
            AbstractBlockChain.FAST_API_10POW_ENABLED = false;
            AbstractBlockChain.API_MODE_NO_HISTORY = false;

            if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                FASTBOOT_SESSION_STATE.set(FastBootState.DISABLED_SESSION);
                log.error("FASTBOOT[sid={}] state RUNNING -> DISABLED_SESSION reason=exception exception={}",
                        FASTBOOT_SESSION_ID,
                        (e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage())));
                log.info("FAST-BOOT[sid={}] disabled; removed from sync decision path for this session",
                        FASTBOOT_SESSION_ID);
            }

            log.info("FAST-BOOT: state RUNNING -> DISABLED_SESSION (reason=exception)");
            log.info("SPV: continuing FULL_SPV sync without FAST overlay");

            // Record failure time
            prefs.edit().putLong("lastApiBootstrapFailureTimeMillis", System.currentTimeMillis()).apply();
            triggerFastSyncFailureWarning();
            log.info("FASTBOOT[sid={}] post-bootstrap fastState={} overlayEnabled=false reason=exception",
                    FASTBOOT_SESSION_ID, fastBootState);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            log.warn("service restart, although it was started as non-sticky");
            return START_NOT_STICKY;
        }

        final Bundle extras = intent.getExtras();
        if (extras != null && extras.containsKey(START_AS_FOREGROUND_EXTRA)) {
            startForeground();
        }

        log.info("service start command: " + intent
                + (intent.hasExtra(Intent.EXTRA_ALARM_COUNT)
                        ? " (alarm count: " + intent.getIntExtra(Intent.EXTRA_ALARM_COUNT, 0) + ")"
                        : ""));

        final String action = intent.getAction();

        if (BlockchainService.ACTION_CANCEL_COINS_RECEIVED.equals(action)) {
            notificationCount = 0;
            notificationAccumulatedAmount = Coin.ZERO;
            notificationAddresses.clear();

            nm.cancel(Constants.NOTIFICATION_ID_COINS_RECEIVED);
        } else if (BlockchainService.ACTION_RESET_BLOCKCHAIN.equals(action)) {
            log.info("will remove blockchain on service shutdown");

            resetBlockchainOnShutdown = true;
            stopSelf();
        } else if (BlockchainService.ACTION_WIPE_WALLET.equals(action)) {
            log.info("will remove blockchain and delete walletFile on service shutdown");

            deleteWalletFileOnShutdown = true;
            stopSelf();
        } else if (BlockchainService.ACTION_BROADCAST_TRANSACTION.equals(action)) {
            final Sha256Hash hash = Sha256Hash
                    .wrap(intent.getByteArrayExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH));
            final Transaction tx = application.getWallet().getTransaction(hash);

            if (peerGroup != null) {
                log.info("broadcasting transaction " + tx.getHashAsString());
                int count = peerGroup.numConnectedPeers();
                int minimum = peerGroup.getMinBroadcastConnections();
                // if the number of peers is <= 3, then only require that number of peers to
                // send
                // if the number of peers is 0, then require 3 peers (default min connections)
                if (count > 0 && count <= 3)
                    minimum = count;

                peerGroup.broadcastTransaction(tx, minimum, false);
            } else {
                log.info("peergroup not available, not broadcasting transaction " + tx.getHashAsString());
                tx.getConfidence().setPeerInfo(0, 1);
            }
        }

        return START_NOT_STICKY;
    }

    private void startForeground() {
        // Shows ongoing notification promoting service to foreground service and
        // preventing it from being killed in Android 26 or later
        Notification notification = createNetworkSyncNotification(getBlockchainState());
        if (notification != null) {
            startForeground(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
        }
    }

    @Override
    public void onTrimMemory(final int level) {
        log.info("onTrimMemory({}) called", level);

        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            log.warn("low memory detected, stopping service");
            stopSelf();
        }
    }

    private Notification createNetworkSyncNotification(BlockchainState blockchainState) {
        Intent notificationIntent = new Intent(this, WalletActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        String message = BlockchainStateUtils.getSyncStateString(blockchainState, this);
        if (message == null) {
            message = getString(R.string.blockchain_state_progress_downloading);
        }

        return new NotificationCompat.Builder(this,
                Constants.NOTIFICATION_CHANNEL_ID_ONGOING)
                .setSmallIcon(R.drawable.ic_dash_d_white_bottom)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(pendingIntent).build();
    }

    @Override
    public BlockchainState getBlockchainState() {
        // In API mode, we still want to return the REAL local chain state,
        // but we override the "percentageSync" and "isReady" flags to satisfy the UI.

        if (blockChain == null) {
            return new BlockchainState(new Date(0), 0, false, impediments, 0, 0, 0, false);
        }

        final StoredBlock chainHead = blockChain.getChainHead();
        final Date bestChainDate = chainHead.getHeader().getTime();
        final int bestChainHeight = chainHead.getHeight();

        // Round-1 observability: SPV chain progress is read-only (bitcoinj).
        final int prevSpvLogged = LAST_LOGGED_SPV_BEST_HEIGHT.getAndSet(bestChainHeight);
        if (prevSpvLogged != bestChainHeight) {
            log.info("SPV[sid={}] bestHeight={} source=bitcoinj", FASTBOOT_SESSION_ID, bestChainHeight);
        }

        final int bestPeerHeight = peerGroup != null ? peerGroup.getMostCommonChainHeight() : 0;
        final int prevPeerLogged = LAST_LOGGED_BEST_PEER_HEIGHT.getAndSet(bestPeerHeight);
        if (bestPeerHeight > 0 && prevPeerLogged != bestPeerHeight) {
            log.info("SPV[sid={}] bestPeerHeight={} peersConnected={}", FASTBOOT_SESSION_ID, bestPeerHeight,
                    peerGroup != null ? peerGroup.numConnectedPeers() : 0);
        }

        // Round-1 observability: UI data source selection for height.
        final int prevUiSpv = LAST_LOGGED_UI_SPV_HEIGHT.getAndSet(bestChainHeight);
        if (prevUiSpv != bestChainHeight) {
            log.info("UI[sid={}] heightSource=SPV value={} reason=canonical overlayEnabled={} fastState={}",
                    FASTBOOT_SESSION_ID, bestChainHeight, isOverlayEnabled(), FASTBOOT_SESSION_STATE.get());
            // Guard log: If FAST failed but SPV is active, explicitly note UI is using SPV
            // data
            final FastBootState fs = FASTBOOT_SESSION_STATE.get();
            if (fs == FastBootState.DISABLED_SESSION && bestChainHeight > 0) {
                log.info("UI[sid={}] fastFailureIgnored overlayUnavailable=true spvActive=true",
                        FASTBOOT_SESSION_ID);
            }
        }
        if (isOverlayEnabled() && apiBestChainHeight > 0) {
            final int prevUiApi = LAST_LOGGED_UI_API_HEIGHT.getAndSet(apiBestChainHeight);
            if (prevUiApi != apiBestChainHeight) {
                log.info("UI[sid={}] heightSource=API value={} reason=overlay_active overlayEnabled={} fastState={}",
                        FASTBOOT_SESSION_ID, apiBestChainHeight, isOverlayEnabled(), FASTBOOT_SESSION_STATE.get());
            }
        }
        final boolean replaying = Constants.isFullReplayAllowed()
                && chainHead.getHeight() < config.getBestChainHeightEver();

        StoredBlock block = null;
        if (Constants.NETWORK_PARAMETERS.isLlmqEnabled()) {
            org.bitcoinj.quorums.ChainLocksHandler handler = application.getWallet().getContext().chainLockHandler;
            if (handler != null) {
                block = handler.getBestChainLockBlock();
            }
        }
        final int chainLockHeight = block != null ? block.getHeight() : 0;
        final int mnListHeight = (int) application.getWallet().getContext().masternodeListManager.getListAtChainTip()
                .getHeight();

        if (isApiMode()) {
            boolean isReady = fastBootCompleted;
            int syncPercent = apiSyncPercentage;

            // TASK 2: UI Readiness Logic
            // If FAST bootstrap succeeded, we consider the wallet "usable" (Ready=true).
            // However, the "Blocks" tab and Progress Bars should reflect REALITY (SPV
            // height).

            if (apiBestChainHeight > 0) {
                // Calculate percentage based on REAL SPV progress relative to API tip.
                // This ensures the progress bar moves from 0% to 100% as we catch up.
                syncPercent = (int) (((float) bestChainHeight / (float) apiBestChainHeight) * 100);
                syncPercent = Math.max(0, Math.min(100, syncPercent));

                // If we are fully caught up, clamp to 100
                if (bestChainHeight >= apiBestChainHeight) {
                    syncPercent = 100;
                }
            }

            // Return the TRUE SPV height (bestChainHeight) as the primary heightSource.
            // UI components using `bestChainHeight` will now see the rising SPV blocks.
            // `isReady` controls whether "Syncing..." overlay disappears.
            // `syncPercent` controls the precision progress bar.
            return new BlockchainState(bestChainDate, bestChainHeight, replaying, impediments, chainLockHeight,
                    mnListHeight,
                    syncPercent, isReady);
        }

        return new BlockchainState(bestChainDate, bestChainHeight, replaying, impediments, chainLockHeight,
                mnListHeight, percentageSync(), false);
    }

    @Override
    public List<Peer> getConnectedPeers() {
        if (peerGroup != null)
            return peerGroup.getConnectedPeers();
        else
            return null;
    }

    @Override
    public List<StoredBlock> getRecentBlocks(final int maxBlocks) {
        final List<StoredBlock> blocks = new ArrayList<StoredBlock>(maxBlocks);

        if (blockChain == null || blockStore == null) {
            return blocks;
        }

        try {
            StoredBlock block = blockChain.getChainHead();

            while (block != null) {
                blocks.add(block);

                if (blocks.size() >= maxBlocks)
                    break;

                block = block.getPrev(blockStore);
            }
        } catch (final BlockStoreException x) {
            // swallow
        }

        return blocks;
    }

    @Override
    public void switchSyncMode(final SyncMode mode) {
        log.info("[FAST-BOOT] switchSyncMode(): {}", mode);
        final boolean fastDisabledThisSession = mode == SyncMode.FAST_API_10POW
                && FASTBOOT_SESSION_STATE.get() == FastBootState.DISABLED_SESSION;
        if (fastDisabledThisSession) {
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
            AbstractBlockChain.FAST_API_10POW_ENABLED = false;
            log.warn(
                    "FASTBOOT[sid={}] switchSyncMode FAST_API_10POW ignored (session DISABLED_SESSION); overlay flags OFF",
                    FASTBOOT_SESSION_ID);
        }
        // FAST_API_10POW is overlay-only and must never enable bitcoinj core
        // snapshot/filtering behavior.
        Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
        AbstractBlockChain.FAST_API_10POW_ENABLED = false;

        AbstractBlockChain.API_MODE_NO_HISTORY = (mode == SyncMode.API_1000POW);
        if (!AbstractBlockChain.API_MODE_NO_HISTORY) {
            AbstractBlockChain.API_SNAPSHOT_TIP_HEIGHT = -1;
            AbstractBlockChain.API_SNAPSHOT_TIP_HASH = null;
        }
        config.setSyncMode(mode);
        application.stopBlockchainService();
        handler.postDelayed(() -> application.startBlockchainService(false), 200);
    }

    private static final String ACTION_PEER_STATE_NUM_PEERS = "num_peers";

    private void broadcastPeerState(final int numPeers) {
        final Intent broadcast = new Intent(ACTION_PEER_STATE);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(ACTION_PEER_STATE_NUM_PEERS, numPeers);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private synchronized void initializeSpv(final Wallet wallet) {
        initializeSpv(wallet, null);
    }

    private synchronized void initializeSpv(final Wallet wallet, @Nullable byte[] apiCheckpointBytes) {
        if (spvInitialized) {
            return;
        }

        // GUARD: Prevent concurrent initialization attempts (e.g. from multiple threads
        // or retry loops)
        if (!spvInitInProgress.compareAndSet(false, true)) {
            log.warn("SPV-INIT: initializeSpv() called but initialization is already in progress. Skipping re-entry.");
            return;
        }

        try {
            spvReady.set(false);
            log.info("SPV-INIT[sid={}] begin: thread={} mode={} fastBootState={}",
                    FASTBOOT_SESSION_ID, Thread.currentThread().getName(), selectedSyncMode, fastBootState);

            // WAIT for bootstrap to complete if we are in API mode
            if (bootstrapLatch != null) {
                try {
                    log.info("initializeSpv: Waiting for bootstrap completion (latch)...");
                    bootstrapLatch.await();
                    log.info("initializeSpv: Bootstrap latch released. Proceeding with chain creation.");
                } catch (InterruptedException e) {
                    log.warn("initializeSpv: Interrupted while waiting for bootstrap latch");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            final SyncMode configuredMode = config.getSyncMode();
            selectedSyncMode = configuredMode; // Trust config

            // Framework refinement:
            // FAST_API_10POW is overlay-only. If the overlay is disabled for any reason,
            // SPV must
            // initialize and run in canonical FULL_SPV mode (blockstore open/create +
            // PeerGroup start).
            final FastBootState sessionFastState = FASTBOOT_SESSION_STATE.get();
            final boolean fastOverlayConfigured = (selectedSyncMode == SyncMode.FAST_API_10POW);
            final boolean bootstrapOk = fastOverlayConfigured && fastApiBootstrapSucceeded;
            final boolean fastOverlayDisabled = !fastOverlayConfigured
                    || sessionFastState == FastBootState.DISABLED_SESSION
                    || sessionFastState == FastBootState.DISABLED_COOLDOWN
                    || !bootstrapOk;
            final SyncMode effectiveSpvMode = (fastOverlayConfigured && !fastOverlayDisabled)
                    ? selectedSyncMode
                    : SyncMode.FULL_SPV;

            // Stability contract:
            // FAST_API_10POW is overlay-only and MUST NOT enable any bitcoinj core "FAST"
            // behavior or snapshot filtering.
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
            AbstractBlockChain.FAST_API_10POW_ENABLED = false;
            // SPV-only: Skip difficulty validation for PEPEPOW (custom diff algorithm not
            // in bitcoinj)
            // Canonical difficulty validation is performed by full nodes (pepepowd)
            AbstractBlockChain.SPV_SKIP_DIFFICULTY_VALIDATION = true;
            AbstractBlockChain.API_MODE_NO_HISTORY = (selectedSyncMode == SyncMode.API_1000POW);
            if (!AbstractBlockChain.API_MODE_NO_HISTORY) {
                AbstractBlockChain.API_SNAPSHOT_TIP_HEIGHT = -1;
                AbstractBlockChain.API_SNAPSHOT_TIP_HASH = null;
            }

            log.info("PEPEPOW-FAST-API: syncMode=" + selectedSyncMode +
                    ", FAST_ENABLED=" + AbstractBlockChain.FAST_API_10POW_ENABLED +
                    ", API_NO_HISTORY=" + AbstractBlockChain.API_MODE_NO_HISTORY +
                    ", bootstrapOk=" + fastApiBootstrapSucceeded);

            final boolean blockChainFileExists = blockChainFile.exists();
            log.info(
                    "INIT: SPV preflight selectedSyncMode={} effectiveSpvMode={} blockstoreExists={} fastState={} bootstrapOk={}",
                    selectedSyncMode, effectiveSpvMode, blockChainFileExists, fastBootState, bootstrapOk);

            try {
                bootStrapStream = getAssets().open(Constants.Files.MNLIST_BOOTSTRAP_FILENAME);
                SimplifiedMasternodeListManager.setBootStrapStream(bootStrapStream, null, 0);
            } catch (IOException x) {
                log.info("cannot load the boot strap stream.  " + x.getMessage());
            }

            if (!blockChainFileExists) {
                // Log fresh-install status when blockstore is missing
                File walletFile = getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
                boolean walletExists = walletFile != null && walletFile.exists();
                log.warn(
                        "INIT: SPV blockstore missing. walletExists={} selectedSyncMode={} effectiveSpvMode={}",
                        walletExists, selectedSyncMode, effectiveSpvMode);
            }

            // TASK 3: Ensure FAST_API_10POW always opens existing blockstore if present
            if (selectedSyncMode == SyncMode.FAST_API_10POW && blockChainFileExists) {
                log.info("INIT: FAST_API_10POW preserving existing blockstore at {}",
                        blockChainFile.getAbsolutePath());
            }

            try {
                // Open SPVBlockStore ONCE - with retry logic for transient errors
                if (blockStore == null) {
                    final boolean existedBefore = blockChainFile.exists();
                    log.info("SPV-INIT[sid={}] openBlockStore: path={} existsBefore={}",
                            FASTBOOT_SESSION_ID, blockChainFile.getAbsolutePath(), existedBefore);
                    blockStore = openBlockStoreWithRetry();

                    if (blockStore == null) {
                        // All retries failed - disable SPV for this session, but DO NOT delete file
                        log.error("SPV-INIT[sid={}] FATAL: openBlockStore failed reason=retries_exhausted path={}",
                                FASTBOOT_SESSION_ID, blockChainFile.getAbsolutePath());

                        // Transition FAST_BOOT_STATE to DISABLED_SESSION
                        if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                            FastBootState oldState = FASTBOOT_SESSION_STATE.getAndSet(FastBootState.DISABLED_SESSION);
                            fastBootState = FastBootState.DISABLED_SESSION;
                            log.error(
                                    "FASTBOOT[sid={}] state {} -> DISABLED_SESSION reason=spv_blockstore_open_exhausted",
                                    FASTBOOT_SESSION_ID, oldState);
                        }

                        // DO NOT stopSelf() here - allow app to function without SPV
                        // User will see "Syncing..." or stall, which is safer than resetting to block 0
                        spvInitialized = false;
                        return;
                    }
                    final boolean createdNew = !existedBefore && blockChainFile.exists();
                    log.info("SPV-INIT[sid={}] openBlockStore: success createdNew={} path={}",
                            FASTBOOT_SESSION_ID, createdNew, blockChainFile.getAbsolutePath());
                } else {
                    log.info("SPVBlockStore already open (likely from bootstrap).");
                }

                StoredBlock chainHead;
                try {
                    chainHead = blockStore.getChainHead();
                } catch (BlockStoreException e) {
                    log.error("Failed to read chain head from blockstore", e);
                    log.info("Re-opening SPVBlockStore after failure");
                    if (!reopenBlockStoreFile()) {
                        log.error("Re-open of SPVBlockStore failed, disabling sync for this session.");
                        stopSelf();
                        return;
                    }
                    try {
                        chainHead = blockStore.getChainHead();
                    } catch (BlockStoreException retryEx) {
                        log.error(
                                "Failed to read chain head after re-open, disabling sync for this session.",
                                retryEx);
                        stopSelf();
                        return;
                    }
                }
                int localHeight = (chainHead != null) ? chainHead.getHeight() : 0;
                int explorerTipHeight = (lastBootstrapResult != null)
                        ? lastBootstrapResult.explorerTipHeight
                        : apiBestChainHeight;

                int offset = (explorerTipHeight > 0 && localHeight > 0)
                        ? (explorerTipHeight - localHeight)
                        : 0;

                log.info("INIT: initializeSpv(): spvHeadHeight={}, explorerTipHeight={}, offset={}",
                        localHeight, explorerTipHeight, offset);

                // Ensure apiBestChainHeight is set correctly for UI
                if (explorerTipHeight > 0) {
                    apiBestChainHeight = explorerTipHeight;
                    config.maybeIncrementBestChainHeightEver(apiBestChainHeight);
                }
                if (chainHead != null && selectedSyncMode == SyncMode.API_1000POW) {
                    AbstractBlockChain.API_MODE_NO_HISTORY = true;
                    AbstractBlockChain.API_SNAPSHOT_TIP_HEIGHT = chainHead.getHeight();
                    AbstractBlockChain.API_SNAPSHOT_TIP_HASH = chainHead.getHeader().getHash();
                    updateApiTipMetadata(chainHead.getHeader().getTimeSeconds(),
                            explorerTipHeight > 0 ? explorerTipHeight : chainHead.getHeight());
                }

                // Log detailed status if we have a bootstrap result
                if (lastBootstrapResult != null) {
                    if (lastBootstrapResult.success) {
                        log.info("FAST-BOOT: Bootstrap succeeded (spvHead={}, tip={})",
                                lastBootstrapResult.spvHeadHeight, lastBootstrapResult.explorerTipHeight);
                    } else if ("skipped".equals(lastBootstrapResult.failureReason)) {
                        log.info(
                                "FAST-BOOT: Bootstrap skipped (local height {}). P2P will continue normally.",
                                lastBootstrapResult.spvHeadHeight);
                    } else {
                        log.info(
                                "FAST-BOOT: Bootstrap result was failure (reason={}). SPV will sync normally.",
                                lastBootstrapResult.failureReason);
                    }
                } else if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                    log.warn("FAST-BOOT: No bootstrap result found (unexpected).");
                }

                log.info("DEBUG: initializeSpv(): apiTipHeight=" + apiBestChainHeight);

                final long earliestKeyCreationTime = wallet.getEarliestKeyCreationTime();

                if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                    if (effectiveSpvMode != SyncMode.FULL_SPV) {
                        log.info("CHECKPOINT[sid={}] API/overlay mode active. Skipping legacy checkpoints.",
                                FASTBOOT_SESSION_ID);
                    } else if (!config.isFullSyncEnabled()) {
                        // Part A: Apply checkpoints with full diagnostics
                        applyCheckpointsWithDiagnostics(earliestKeyCreationTime);
                    } else {
                        log.info("CHECKPOINT[sid={}] Full validation sync enabled. Skipping checkpoints.",
                                FASTBOOT_SESSION_ID);
                        Toast.makeText(this, R.string.preferences_full_sync_active_toast, Toast.LENGTH_LONG)
                                .show();
                    }
                }
            } catch (final Exception x) {
                // FIX: REMOVED DANGEROUS RECOVERY (Deletion)
                // blockChainFile.delete(); // NEVER DELETE AUTOMATICALLY

                SimplifiedMasternodeListManager manager = application.getWallet().getContext().masternodeListManager;
                if (manager != null) {
                    // In FAST mode, do NOT wipe the MN list just because blockstore failed
                    // logic if
                    // we can avoid it.
                    // But if blockstore cannot be created, we are kind of stuck.
                    // Let's stick to default behavior here as this is "cannot create"
                    // scenario.
                    manager.resetMNList(true, true);
                }

                final String msg = "blockstore cannot be created " + x.getMessage();
                log.error(msg, x);
                stopSelf();
                return;
            }

            // Initialize ApiSyncManager
            ApiHeaderClient apiClient = new ApiHeaderClient(config.getApiBaseUrl());
            HeaderVerifier headerVerifier = new HeaderVerifier(Constants.NETWORK_PARAMETERS);
            PowVerifier powVerifier = new PowVerifier(Constants.NETWORK_PARAMETERS);
            apiSyncManager = new ApiSyncManager(apiClient, headerVerifier, powVerifier, blockStore,
                    Constants.NETWORK_PARAMETERS);

            // Create BlockChain
            try {
                log.info("SPV-INIT[sid={}] BlockChain: creating with spvHeadHeight={}",
                        FASTBOOT_SESSION_ID, blockStore.getChainHead().getHeight());
                blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
                // Task A: Log canonical BlockChain instance creation with identityHashCode
                log.info("SPV-CHAIN[sid={}] created blockChain instanceId={}",
                        FASTBOOT_SESSION_ID, System.identityHashCode(blockChain));
                log.info("SPV-INIT[sid={}] BlockChain: created chainHeadHeight={} chainHeadHash={}",
                        FASTBOOT_SESSION_ID,
                        blockChain.getChainHead().getHeight(),
                        blockChain.getChainHead().getHeader().getHashAsString());

                // Debug Contract #4: Add listener for chain head changes with source=SPV
                blockChain.addNewBestBlockListener(new org.bitcoinj.core.listeners.NewBestBlockListener() {
                    @Override
                    public void notifyNewBestBlock(org.bitcoinj.core.StoredBlock block)
                            throws org.bitcoinj.core.VerificationException {
                        int height = block.getHeight();
                        String hash = block.getHeader().getHashAsString();
                        int prev = LAST_LOGGED_SPV_BEST_HEIGHT.getAndSet(height);

                        // Set watchdog flag on first block
                        boolean wasFirst = chainAdvancedAtLeastOnce.compareAndSet(false, true);
                        if (wasFirst) {
                            log.info("SPV-HEIGHT[sid={}] firstBlockConnected height={} hash={} source=SPV",
                                    FASTBOOT_SESSION_ID, height, hash);
                        }

                        if (height != prev) {
                            log.info("SPV-HEIGHT[sid={}] chainAdvanced height={} hash={} source=SPV",
                                    FASTBOOT_SESSION_ID, height, hash);
                        }
                        broadcastBlockchainState();
                    }
                });
                // Task C: Log listener attachment with identityHashCode
                log.info("SPV-CHAIN[sid={}] listener attached to blockChain instanceId={}",
                        FASTBOOT_SESSION_ID, System.identityHashCode(blockChain));
                // ensureChainHeadAlignedWithApiTip(); // DISABLED: FAST overlay must not modify
                // blockstore
                // GUARD: Removing the logic that would align/reset blockstore.
                if (isApiMode() && blockStore.getChainHead().getHeight() < apiBestChainHeight) {
                    log.info("INIT: Local chain ({}) behind API tip ({}). SPV will catch up via P2P.",
                            blockStore.getChainHead().getHeight(), apiBestChainHeight);
                }
                if (isApiMode()) {
                    StoredBlock apiTip = blockChain.getChainHead();
                    // Overlay logic: we do NOT force the chain head. We just trust what we have.
                    if (lastBootstrapResult != null && lastBootstrapResult.success) {
                        // We can update wallet lastSeen for UI benefit, but BEWARE of messing with SPV
                        // logic.
                        // Ideally, we do NOT touch this if we want pure overlay.
                        // But for now, let's just log.
                        log.info("FAST-BOOT: SPV initialized. ChainHead={}, API Tip={}",
                                apiTip.getHeight(), apiBestChainHeight);
                    }
                }
            } catch (final Exception x) {
                log.error("blockchain cannot be created", x);
                stopSelf();
                return;
            }
            Log.i("SPV", "chain head height=" + blockChain.getChainHead().getHeight());
            log.info("DEBUG: BlockChain created. ChainHead height: " + blockChain.getChainHead().getHeight()
                    + " hash="
                    + blockChain.getChainHead().getHeader().getHashAsString());

            if (selectedSyncMode == SyncMode.FAST_API_10POW && fastApiBootstrapSucceeded) {
                runWalletSnapshotIfNeeded(wallet, apiClient, apiBestChainHeight);
            }

            spvInitialized = true;
            spvReady.set(true);

            int spvHead = blockChain.getChainHead().getHeight();
            int offset = (apiBestChainHeight > 0 && spvHead > 0) ? (apiBestChainHeight - spvHead) : 0;

            Log.i(FASTBOOT,
                    "INIT: spvHeadHeight=" + spvHead +
                            ", explorerTipHeight=" + apiBestChainHeight +
                            ", offset=" + offset);

            if (lastBootstrapResult != null) {
                Log.i(FASTBOOT,
                        "INIT: lastBootstrapResult: success=" + lastBootstrapResult.success +
                                ", reason=" + lastBootstrapResult.failureReason +
                                ", tip=" + lastBootstrapResult.explorerTipHeight +
                                ", spvHead=" + lastBootstrapResult.spvHeadHeight);
            }

            log.info("initializeSpv(): completed; chainHeadHeight={}, hash={}, apiTipHeight={}",
                    blockChain.getChainHead().getHeight(),
                    blockChain.getChainHead().getHeader().getHashAsString(),
                    apiBestChainHeight);

            log.info(
                    "SPV continues even if FAST failed (selectedSyncMode={}, fastState={}, bootstrapOk={}, effectiveSpvMode={})",
                    selectedSyncMode, fastBootState, bootstrapOk, effectiveSpvMode);

            if (!Constants.isFullReplayAllowed() && config.isRestoringBackup()) {
                log.info("FAST_API_10POW: Forcing restoringBackup=false (no full replay in this mode).");
                config.setRestoringBackup(false);
            }
            log.info("SPV-INIT[sid={}] PeerGroup: calling startPeerGroup...", FASTBOOT_SESSION_ID);
            startPeerGroup();
            log.info("SPV-INIT[sid={}] PeerGroup: startPeerGroup returned peerGroupNotNull={} isRunning={}",
                    FASTBOOT_SESSION_ID, (peerGroup != null), (peerGroup != null && peerGroup.isRunning()));

            if (peerGroup != null && !peerGroup.isRunning()) {
                log.info("SPV-INIT[sid={}] PeerGroup: forcing manual start via peerGroup.start()", FASTBOOT_SESSION_ID);
                peerGroup.start();
                log.info("SPV-INIT[sid={}] PeerGroup: manual start complete isRunning={}",
                        FASTBOOT_SESSION_ID, peerGroup.isRunning());
            } else {
                if (peerGroup == null)
                    log.warn("SPV-INIT[sid={}] PeerGroup: peerGroup is NULL after startPeerGroup!",
                            FASTBOOT_SESSION_ID);
                else
                    log.info("SPV-INIT[sid={}] PeerGroup: already running isRunning={}",
                            FASTBOOT_SESSION_ID, peerGroup.isRunning());
            }

            log.info(
                    "SPV-INIT[sid={}] complete: spvInitialized={} chainHeadHeight={} peerGroupRunning={} mode={} fastState={} overlayEnabled={}",
                    FASTBOOT_SESSION_ID,
                    spvInitialized,
                    (blockChain != null ? blockChain.getChainHead().getHeight() : -1),
                    (peerGroup != null && peerGroup.isRunning()),
                    selectedSyncMode,
                    fastBootState,
                    (fastOverlayConfigured && !fastOverlayDisabled));

            /*
             * Manual test steps (framework refinement):
             * 1) Fresh install, FAST fails (pow-failed) -> SPV still starts and height
             * moves forward.
             * 2) Reopen app -> must NOT reset to block 0 due to FAST failure.
             * 3) Network monitor Blocks panel -> height eventually updates beyond
             * previously stuck height.
             */
        } finally {
            spvInitInProgress.set(false);
            log.info("INIT: initializeSpv(): FINALLY block reached. spvInitInProgress reset to false.");
        }
    }

    private int getChainHeadHeightSafe() {
        try {
            return blockStore != null ? blockStore.getChainHead().getHeight() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Checks if this is a fresh install by verifying BOTH:
     * 1) Wallet file does NOT exist
     * 2) Blockstore file does NOT exist
     *
     * If wallet exists but blockstore is missing, this is NOT a fresh install
     * (possibly corrupt/deleted state). Creating a new blockstore in this case
     * would reset to block 0 and lose sync progress.
     *
     * @return true if this is a fresh install, false otherwise
     */
    private boolean isFreshInstall() {
        File walletFile = getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        boolean walletExists = walletFile != null && walletFile.exists();
        boolean blockstoreExists = blockChainFile != null && blockChainFile.exists();

        // Fresh install: neither wallet nor blockstore exists
        boolean freshInstall = !walletExists && !blockstoreExists;

        log.info("SPV-INIT: isFreshInstall check: walletExists={} blockstoreExists={} freshInstall={}",
                walletExists, blockstoreExists, freshInstall);

        return freshInstall;
    }

    /**
     * Apply checkpoints with full diagnostics. Handles zero-checkpoint case
     * gracefully.
     * FAIL-OPEN: Checkpoint failure is non-fatal; SPV will sync from genesis if
     * needed.
     *
     * Logs: CHECKPOINT[sid=...] resource=... streamNull=... requestedTime=...
     * walletEarliestKeyTime=...
     *
     * @param walletEarliestKeyTime the wallet's earliest key creation time in
     *                              seconds
     */
    private void applyCheckpointsWithDiagnostics(long walletEarliestKeyTime) {
        final String checkpointResource = Constants.Files.CHECKPOINTS_FILENAME;
        InputStream checkpointsInputStream = null;

        try {
            checkpointsInputStream = getAssets().open(checkpointResource);
        } catch (IOException e) {
            log.warn("CHECKPOINT[sid={}] resource={} streamNull=true reason=IOException message={}",
                    FASTBOOT_SESSION_ID, checkpointResource, e.getMessage());
            log.info("CHECKPOINT[sid={}] no checkpoint file available; proceeding without (expected)",
                    FASTBOOT_SESSION_ID);
            return;
        }

        final boolean streamNull = (checkpointsInputStream == null);
        final String networkId = Constants.NETWORK_PARAMETERS.getId();

        log.info("CHECKPOINT[sid={}] resource={} streamNull={} requestedTime={} walletEarliestKeyTime={} networkId={}",
                FASTBOOT_SESSION_ID, checkpointResource, streamNull, walletEarliestKeyTime, walletEarliestKeyTime,
                networkId);

        if (streamNull) {
            log.info("CHECKPOINT[sid={}] no checkpoint stream; proceeding without (expected)", FASTBOOT_SESSION_ID);
            return;
        }

        try {
            final Stopwatch watch = Stopwatch.createStarted();
            CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS,
                    checkpointsInputStream, blockStore, walletEarliestKeyTime);
            watch.stop();
            log.info("CHECKPOINT[sid={}] success resource={} took={}", FASTBOOT_SESSION_ID, checkpointResource, watch);
        } catch (IllegalStateException ise) {
            // This occurs when checkpoints.txt has zero checkpoints
            // (checkState(numCheckpoints > 0))
            // This is expected for chains without published checkpoints
            log.info(
                    "CHECKPOINT[sid={}] no applicable checkpoint; proceeding without (expected for empty checkpoints file). "
                            +
                            "errorClass=IllegalStateException message={}",
                    FASTBOOT_SESSION_ID, ise.getMessage());
        } catch (IOException ioe) {
            log.warn("CHECKPOINT[sid={}] failed to apply checkpoints; continuing without. " +
                    "errorClass=IOException message={}", FASTBOOT_SESSION_ID, ioe.getMessage());
        } catch (Throwable t) {
            // Catch-all for unexpected errors (BlockStoreException, etc.)
            log.error("CHECKPOINT[sid={}] failed to apply checkpoints; continuing without. " +
                    "errorClass={} message={}", FASTBOOT_SESSION_ID, t.getClass().getSimpleName(), t.getMessage());
        } finally {
            if (checkpointsInputStream != null) {
                try {
                    checkpointsInputStream.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Discover blockstore in legacy locations and migrate to canonical path if
     * found.
     * This handles cases where the blockstore file exists at a different path due
     * to:
     * - Previous app version using different naming
     * - Backup/restore operations leaving .bak or .tmp files
     * - Network suffix changes
     *
     * CRITICAL: This method never deletes files, only renames/migrates.
     *
     * @return description of what was found/done: "existing", "migrated:<path>", or
     *         "not_found"
     */
    private String discoverAndMigrateBlockstore() {
        // Part C: Log canonical path status upfront with full diagnostics
        final long lastModified = (blockChainFile != null && blockChainFile.exists())
                ? blockChainFile.lastModified()
                : 0;
        final long sizeBytes = (blockChainFile != null && blockChainFile.exists())
                ? blockChainFile.length()
                : 0;
        log.info("SPV-DISCOVERY[sid={}] canonicalPath={} exists={} sizeBytes={} lastModified={}",
                FASTBOOT_SESSION_ID,
                blockChainFile != null ? blockChainFile.getAbsolutePath() : "null",
                blockChainFile != null && blockChainFile.exists(),
                sizeBytes, lastModified);

        // Check if canonical path already exists
        if (blockChainFile != null && blockChainFile.exists() && blockChainFile.length() > 0) {
            log.info("SPV-DISCOVERY[sid={}] result=existing canonicalPath={}",
                    FASTBOOT_SESSION_ID, blockChainFile.getAbsolutePath());
            return "existing";
        }

        // Part B: Multiple directories to check (in priority order)
        // 1. app_blockstore directory (current canonical)
        // 2. getFilesDir() (legacy location used by some older versions)
        // 3. getNoBackupFilesDir() (another potential legacy location)
        File blockstoreDir = getDir("blockstore", Context.MODE_PRIVATE);
        File filesDir = getFilesDir();
        File noBackupDir = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            noBackupDir = getNoBackupFilesDir();
        }

        File[] legacyDirs = (noBackupDir != null)
                ? new File[] { blockstoreDir, filesDir, noBackupDir }
                : new File[] { blockstoreDir, filesDir };

        String canonicalName = Constants.Files.BLOCKCHAIN_FILENAME;
        String[] legacyNames = {
                canonicalName, // Canonical name in non-canonical dir
                canonicalName + ".bak", // Backup file
                canonicalName + ".tmp", // Temp file from interrupted write
                "blockchain", // Base name without suffix
                "blockchain.bak",
                "blockchain.tmp",
                "blockchain-testnet", // Legacy testnet
                "blockchain-devnet", // Legacy devnet
                "pepepow-blockchain" // Potential historical name
        };

        // Search all legacy directories for any legacy file
        for (File dir : legacyDirs) {
            if (dir == null || !dir.exists()) {
                log.info("SPV-DISCOVERY[sid={}] legacyDir path={} exists=false (skipping)",
                        FASTBOOT_SESSION_ID, dir != null ? dir.getAbsolutePath() : "null");
                continue;
            }
            for (String legacyName : legacyNames) {
                // Skip if checking canonical name in canonical dir (already checked above)
                if (legacyName.equals(canonicalName) && dir.equals(blockstoreDir)) {
                    continue;
                }
                File legacyFile = new File(dir, legacyName);
                log.info("SPV-DISCOVERY[sid={}] legacyCandidate path={} exists={}",
                        FASTBOOT_SESSION_ID, legacyFile.getAbsolutePath(), legacyFile.exists());

                if (legacyFile.exists() && legacyFile.length() > 0) {
                    log.info("SPV-DISCOVERY[sid={}] found legacy store at {} (size={})",
                            FASTBOOT_SESSION_ID, legacyFile.getAbsolutePath(), legacyFile.length());

                    // Attempt atomic rename to canonical path
                    boolean renamed = legacyFile.renameTo(blockChainFile);
                    if (renamed) {
                        log.info("SPV-DISCOVERY[sid={}] migrating legacy -> canonical result=success from={} to={}",
                                FASTBOOT_SESSION_ID, legacyFile.getAbsolutePath(), blockChainFile.getAbsolutePath());
                        return "migrated:" + legacyFile.getAbsolutePath();
                    } else {
                        // Rename failed (maybe cross-filesystem); use legacy path directly
                        log.warn(
                                "SPV-DISCOVERY[sid={}] migrating legacy -> canonical result=failure (using legacy path directly) path={}",
                                FASTBOOT_SESSION_ID, legacyFile.getAbsolutePath());
                        blockChainFile = legacyFile;
                        return "migrated:" + legacyFile.getAbsolutePath();
                    }
                }
            }
        }

        log.info("SPV-DISCOVERY[sid={}] result=not_found canonicalPath={} checkedDirs={}",
                FASTBOOT_SESSION_ID,
                blockChainFile != null ? blockChainFile.getAbsolutePath() : "null",
                legacyDirs.length);
        return "not_found";
    }

    /**
     * Attempts to open SPVBlockStore with retry logic for transient errors.
     * NEVER deletes the blockstore file. If open fails after all retries, returns
     * null and the caller should disable SPV for the session.
     *
     * BEHAVIOR on missing blockstore:
     * - Fresh install: Create new blockstore (normal)
     * - Non-fresh install: CONTROLLED REBUILD with loud warning (allows SPV to
     * sync)
     *
     * Transient errors (retry with backoff):
     * - OverlappingFileLockException
     * - ClosedByInterruptException
     * - ClosedChannelException
     * - FileLockInterruptionException
     *
     * Non-transient errors (retry few times for race conditions, then give up):
     * - "Header bytes do not equal SPVB" (corrupt file)
     *
     * @return SPVBlockStore if successful, null if failed after all retries
     */
    private SPVBlockStore openBlockStoreWithRetry() {
        final int MAX_RETRIES = 5;
        final long[] BACKOFF_MS = { 200, 400, 800, 1600, 3200 };

        // STEP 1: Attempt to discover and migrate legacy blockstore
        String discoveryResult = discoverAndMigrateBlockstore();
        log.info("SPV-INIT: Blockstore discovery result: {}", discoveryResult);

        // Re-check existence after discovery attempt
        final boolean blockstoreExists = blockChainFile != null && blockChainFile.exists();
        final boolean freshInstall = isFreshInstall();

        log.info(
                "SPV-INIT: openBlockStoreWithRetry: blockstoreExists={} freshInstall={} discoveryResult={} blockChainFile={}",
                blockstoreExists, freshInstall, discoveryResult,
                blockChainFile != null ? blockChainFile.getAbsolutePath() : "null");

        if (!blockstoreExists) {
            if (freshInstall) {
                log.info(
                        "SPV-INIT: Fresh install detected. Allowing new SPVBlockStore creation. decision=create_fresh");
            } else {
                // CONTROLLED FALLBACK: Blockstore missing on non-fresh install
                // Instead of refusing to start (which blocks SPV forever), allow controlled
                // rebuild.
                // The wallet keys are preserved; only sync progress is lost.
                log.warn("SPV-INIT: CONTROLLED_REBUILD: SPV blockstore missing on non-fresh install. " +
                        "Creating new blockstore WITHOUT deleting wallet data. " +
                        "Prior sync progress lost; SPV will sync from checkpoints. " +
                        "decision=rebuild_controlled canonicalPath={}",
                        blockChainFile != null ? blockChainFile.getAbsolutePath() : "null");
                // Fall through to allow creation
            }
        } else {
            log.info("SPV-INIT: Using {} blockstore. decision=use_{}",
                    discoveryResult.startsWith("migrated") ? "migrated" : "existing",
                    discoveryResult.startsWith("migrated") ? "migrated" : "existing");
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            // Clear interrupt flag before each attempt to prevent
            // ClosedByInterruptException
            boolean wasInterrupted = Thread.interrupted();
            if (wasInterrupted) {
                log.warn("[blockchain-init] SPV_BLOCKSTORE_OPEN: Cleared interrupt flag attempt={}/{} thread={}",
                        attempt, MAX_RETRIES, Thread.currentThread().getName());
            }

            try {
                SPVBlockStore store = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
                if (attempt > 1) {
                    log.info("[blockchain-init] SPV_BLOCKSTORE_OPEN: succeeded on attempt={}/{}", attempt, MAX_RETRIES);
                }

                // Part C: Sanity check - verify file was persisted
                if (blockChainFile != null && blockChainFile.exists()) {
                    final long persistedSize = blockChainFile.length();
                    final long persistedLastMod = blockChainFile.lastModified();
                    log.info("SPV-DISCOVERY[sid={}] storeCreated=success path={} sizeBytes={} lastModified={}",
                            FASTBOOT_SESSION_ID, blockChainFile.getAbsolutePath(), persistedSize, persistedLastMod);
                    if (persistedSize == 0) {
                        log.warn(
                                "SPV-DISCOVERY[sid={}] WARNING: blockstore created but sizeBytes=0 (may fail on next restart)",
                                FASTBOOT_SESSION_ID);
                    }
                } else {
                    log.warn(
                            "SPV-DISCOVERY[sid={}] WARNING: blockstore created but file does not exist (may fail on next restart) path={}",
                            FASTBOOT_SESSION_ID, blockChainFile != null ? blockChainFile.getAbsolutePath() : "null");
                }

                return store;
            } catch (BlockStoreException e) {
                Throwable cause = e.getCause();
                boolean isTransient = isTransientBlockStoreError(e);

                log.warn("SPV-INIT[sid={}] openBlockStore: attempt={}/{} failed " +
                        "exceptionClass={} causeClass={} message={} isTransient={}",
                        FASTBOOT_SESSION_ID, attempt, MAX_RETRIES,
                        e.getClass().getSimpleName(),
                        cause != null ? cause.getClass().getSimpleName() : "null",
                        e.getMessage(),
                        isTransient);

                if (!isTransient) {
                    // Non-transient error (e.g., corrupt header)
                    // Retry a few times anyway in case of race/partial write from prior bad
                    // recovery
                    if (attempt >= 3) {
                        log.error("SPV-INIT[sid={}] openBlockStore: FATAL blockstore corrupt " +
                                "reason=non_transient_error file={} error={}",
                                FASTBOOT_SESSION_ID, blockChainFile.getAbsolutePath(), e.getMessage());
                        return null;
                    }
                }

                if (attempt < MAX_RETRIES) {
                    long backoffMs = BACKOFF_MS[attempt - 1];
                    log.info("[blockchain-init] SPV_BLOCKSTORE_OPEN: retrying in {}ms (attempt {}/{})",
                            backoffMs, attempt + 1, MAX_RETRIES);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[blockchain-init] SPV_BLOCKSTORE_OPEN: interrupted during backoff; aborting retries");
                        return null;
                    }
                }
            }
        }

        log.error("SPV-INIT[sid={}] openBlockStore: FATAL all {} retries exhausted " +
                "reason=retries_exhausted file={}",
                FASTBOOT_SESSION_ID, MAX_RETRIES, blockChainFile.getAbsolutePath());
        return null;
    }

    /**
     * Checks if a BlockStoreException is due to a transient error that may resolve
     * on retry.
     */
    private boolean isTransientBlockStoreError(BlockStoreException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            // Check message for header corruption (non-transient)
            String msg = e.getMessage();
            if (msg != null && msg.contains("Header bytes do not equal")) {
                return false; // Corrupt file - not transient (but still retry few times)
            }
            return true; // Unknown cause, assume transient
        }

        String causeName = cause.getClass().getName();
        // Transient: lock contention, closed channel due to interrupt
        if (causeName.contains("OverlappingFileLockException") ||
                causeName.contains("ClosedByInterruptException") ||
                causeName.contains("ClosedChannelException") ||
                causeName.contains("FileLockInterruptionException")) {
            return true;
        }

        return false;
    }

    private boolean reopenBlockStoreFile() {
        // FIX: Clear interrupt flag before re-open attempt
        boolean wasInterrupted = Thread.interrupted();
        if (wasInterrupted) {
            log.warn(
                    "SPV_BLOCKSTORE_REOPEN: Cleared stale interrupt flag before reopen (thread={} wasInterrupted=true)",
                    Thread.currentThread().getName());
        }
        try {
            if (blockStore != null) {
                try {
                    blockStore.close();
                } catch (Exception closeEx) {
                    log.warn("Error while closing blockstore before re-open", closeEx);
                }
            }
            blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
            log.info("Re-opened SPVBlockStore from file: {}", blockChainFile.getAbsolutePath());
            return true;
        } catch (BlockStoreException reopenEx) {
            log.error("SPV_BLOCKSTORE_REOPEN_FAILED (NO DELETE): Re-open of SPVBlockStore failed. " +
                    "exceptionClass={} message={} thread={} isInterrupted={}",
                    reopenEx.getClass().getSimpleName(),
                    reopenEx.getMessage(),
                    Thread.currentThread().getName(),
                    Thread.currentThread().isInterrupted());

            // Transition FAST_BOOT_STATE to DISABLED_SESSION
            if (selectedSyncMode == SyncMode.FAST_API_10POW) {
                FastBootState oldState = FASTBOOT_SESSION_STATE.getAndSet(FastBootState.DISABLED_SESSION);
                fastBootState = FastBootState.DISABLED_SESSION;
                log.error(
                        "FASTBOOT[sid={}] FAST_BOOT_STATE transition: {} -> DISABLED_SESSION reason=spv_blockstore_reopen_failed",
                        FASTBOOT_SESSION_ID, oldState);
            }
            return false;
        }
    }

    private void startPeerGroup() {
        if (connectivityReceiverRegistered)
            return;

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        registerReceiver(connectivityReceiver, intentFilter); // implicitly start PeerGroup
        connectivityReceiverRegistered = true;

        application.getWallet().addCoinsReceivedEventListener(Threading.SAME_THREAD, walletEventListener);
        application.getWallet().addCoinsSentEventListener(Threading.SAME_THREAD, walletEventListener);
        application.getWallet().addChangeEventListener(Threading.SAME_THREAD, walletEventListener);
        walletListenersRegistered = true;

        registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
        tickReceiverRegistered = true;

        log.info("Starting PeerGroup. LLMQ enabled = {}", Constants.NETWORK_PARAMETERS.isLlmqEnabled());
        application.getWallet().getContext().initDashSync(getDir("masternode", MODE_PRIVATE).getAbsolutePath());

        peerDiscoveryList.add(dnsDiscovery);

        // Task 3: Start P2P download from the bootstrapped head
        if (blockChain != null) {
            int localHeight = blockChain.getChainHead().getHeight();
            log.info("PEPEPOW SyncMode={} starting P2P download from localHeight={} bestPeerHeight={}",
                    selectedSyncMode, localHeight,
                    peerGroup != null ? peerGroup.getMostCommonChainHeight() : "unknown");

            if (selectedSyncMode == SyncMode.FAST_API_10POW || selectedSyncMode == SyncMode.API_1000POW) {
                // TASK 2: Disable Historical P2P Download
                // We trust the snapshot. We do NOT want to download history.
                // By doing nothing here (except logging), we allow the PeerGroup's
                // fastCatchupTime
                // (set in updatePeerGroup) to govern the download start point.

                log.info("FAST_API_10POW: P2P download starting normally. Governed by fastCatchupTime.");
                log.info("FAST-BOOT: P2P_START locatorHeight={} expectedNearTip=true", localHeight);
            }
        }

        // Force update to start peer group immediately if network is available
        updatePeerGroup();
    }

    private void runWalletSnapshotIfNeeded(Wallet wallet, ApiHeaderClient apiClient, int apiTipHeight) {
        if (walletSnapshotAttempted || wallet == null || apiClient == null) {
            return;
        }
        walletSnapshotAttempted = true;
        int snapshotHeight = apiTipHeight > 0 ? apiTipHeight
                : (blockChain != null ? blockChain.getChainHead().getHeight() : 0);
        if (snapshotHeight <= 0) {
            log.warn("FAST-BOOT: wallet snapshot skipped because apiTipHeight is unknown.");
            walletSnapshotAttempted = false;
            return;
        }
        ApiWalletClient walletClient = new ApiWalletClient(config.getApiBaseUrl(), apiClient);
        ApiWalletSnapshotBootstrapper snapshotBootstrapper = new ApiWalletSnapshotBootstrapper(walletClient, apiClient,
                config, Constants.NETWORK_PARAMETERS);
        log.info("FAST-BOOT: wallet snapshot kick-off. apiTipHeight={}", snapshotHeight);
        try {
            lastWalletSnapshotResult = snapshotBootstrapper.runWalletSnapshot(wallet, snapshotHeight);

            // Persist status
            config.setWalletSnapshotStatus(lastWalletSnapshotResult.status);

            if (lastWalletSnapshotResult.success) { // logic for boolean success is still true for EMPTY_OK
                log.info("FAST-BOOT: wallet snapshot result imported={} walletBalance={} apiBalance={} status={}",
                        lastWalletSnapshotResult.importedTxs,
                        lastWalletSnapshotResult.walletBalance.toFriendlyString(),
                        lastWalletSnapshotResult.apiBalance.toFriendlyString(),
                        lastWalletSnapshotResult.status);
                application.saveWallet();
                // config.setFastApiSyncFailed(false); // Do not touch global flag here
            } else {
                log.warn("FAST-BOOT: wallet snapshot failed: {}. Wallet may miss historical transactions.",
                        lastWalletSnapshotResult.failureReason);
                // Non-fatal: Do NOT set fastApiSyncFailed(true).
                // Just update status for detailed UI inspection if needed.
                application.publishApiStatus(ApiStatus.State.DEGRADED,
                        "TX snapshot failed; syncing via P2P", 500);
            }
        } catch (Exception e) {
            log.error("FAST-BOOT: wallet snapshot crashed", e);
            config.setWalletSnapshotStatus(org.dash.wallet.common.data.WalletSnapshotStatus.FAILED);
            // Non-fatal
            application.publishApiStatus(ApiStatus.State.DEGRADED,
                    "TX snapshot crashed; syncing via P2P", 500);
        }
    }

    private void alignBlockStoreHeadWithBootstrapResult(ApiPowBootstrapper.BootstrapResult result) {
        // Stability hard-guard:
        // FULL_SPV is the only canonical chain and the only mode allowed to mutate
        // blockstore/chainHead.
        // FAST_API_10POW is overlay-only (UI tip/snapshot) and MUST NOT modify bitcoinj
        // core state.
        log.info(
                "FASTBOOT[sid={}] FAST overlay: alignBlockStoreHeadWithBootstrapResult disabled (no blockstore/chainHead writes)",
                FASTBOOT_SESSION_ID);
    }

    @Nullable
    private StoredBlock findApiTipHeadFromResult() throws BlockStoreException {
        if (blockStore == null || lastBootstrapResult == null || !lastBootstrapResult.success) {
            return null;
        }

        StoredBlock apiTip = null;
        if (lastBootstrapResult.chainHeadHash != null) {
            apiTip = blockStore.get(lastBootstrapResult.chainHeadHash);
        }
        if (apiTip == null && lastBootstrapResult.chainHeadHeight > 0) {
            StoredBlock head = blockStore.getChainHead();
            if (head != null && head.getHeight() == lastBootstrapResult.chainHeadHeight) {
                apiTip = head;
            }
        }
        return apiTip;
    }

    private void updateApiTipMetadata(long timeSeconds, int height) {
        if (height > 0) {
            apiBestChainHeight = height;
            config.maybeIncrementBestChainHeightEver(apiBestChainHeight);
        }
        if (timeSeconds > 0) {
            apiBestChainDate = new Date(TimeUnit.SECONDS.toMillis(timeSeconds));
        } else if (height > 0 && apiBestChainDate.getTime() == 0) {
            apiBestChainDate = new Date();
        }
        if (height > 0) {
            apiSyncPercentage = 100;
        }
    }

    private void ensureChainHeadAlignedWithApiTip() {
        // Stability hard-guard: Never mutate bitcoinj chainHead from FAST overlay.
        log.info("FASTBOOT[sid={}] FAST overlay: ensureChainHeadAlignedWithApiTip disabled (no chainHead mutation)",
                FASTBOOT_SESSION_ID);
    }

    private void broadcastBlockchainState() {
        final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
        broadcast.setPackage(getPackageName());
        BlockchainState blockchainState = getBlockchainState();
        blockchainState.putExtras(broadcast);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Handle Ongoing notification state
            boolean syncing = blockchainState.bestChainDate
                    .getTime() < (Utils.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS); // 1 hour
            if (!syncing && blockchainState.bestChainHeight == config.getBestChainHeightEver()) {
                // Remove ongoing notification if blockchain sync finished
                stopForeground(true);
                nm.cancel(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC);
            } else if (blockchainState.replaying || syncing) {
                // Shows ongoing notification when synchronizing the blockchain
                Notification notification = createNetworkSyncNotification(blockchainState);
                if (notification != null) {
                    nm.notify(Constants.NOTIFICATION_ID_BLOCKCHAIN_SYNC, notification);
                }
            }
        }
    }

    private int percentageSync() {
        if (isApiMode()) {
            return apiSyncPercentage;
        }

        if (blockChain == null) {
            return 0;
        }

        int chainHeadHeight = blockChain.getChainHead().getHeight();
        int mostCommonChainHeight;
        if (peerGroup == null) {
            return 0;
        }
        if (peerGroup.getMostCommonChainHeight() > 0) {
            mostCommonChainHeight = peerGroup.getMostCommonChainHeight();
        } else {
            mostCommonChainHeight = chainHeadHeight;
        }
        float percentage = ((float) chainHeadHeight / (float) mostCommonChainHeight) * 100;
        log.info("mostCommonChainHeight: " + mostCommonChainHeight + "\tchainHeadHeight: " + chainHeadHeight + "\t"
                + percentage + "%\t" + config.getBestChainHeightEver());
        return (int) percentage;
    }

    @Override
    public void onDestroy() {
        log.info(".onDestroy()");
        log.debug(".onDestroy()");
        spvReady.set(false);
        stopSyncWatchdog(); // Stop watchdog on service destroy

        if (bootstrapThread != null && bootstrapThread.isAlive()) {
            log.info("Interrupting bootstrap thread");
            bootstrapThread.interrupt();
        }

        WalletApplication.scheduleStartBlockchainService(this);

        if (tickReceiverRegistered) {
            unregisterReceiver(tickReceiver);
        }

        if (walletListenersRegistered && application.getWallet() != null) {
            application.getWallet().removeChangeEventListener(walletEventListener);
            application.getWallet().removeCoinsSentEventListener(walletEventListener);
            application.getWallet().removeCoinsReceivedEventListener(walletEventListener);
        }

        if (connectivityReceiverRegistered) {
            unregisterReceiver(connectivityReceiver);
        }

        stopPeerGroup(application.getWallet());

        if (peerConnectivityListener != null)
            peerConnectivityListener.stop();

        delayHandler.removeCallbacksAndMessages(null);

        if (blockStore != null) {
            try {
                blockStore.close();
            } catch (final BlockStoreException x) {
                throw new RuntimeException(x);
            }
        }

        if (!deleteWalletFileOnShutdown && application.getWallet() != null) {
            application.saveWallet();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            log.debug("wakelock still held, releasing");
            wakeLock.release();
        }

        if (resetBlockchainOnShutdown || deleteWalletFileOnShutdown) {
            log.info("removing blockchain");
            if (blockChainFile != null)
                blockChainFile.delete();
            if (application.getWallet() != null) {
                SimplifiedMasternodeListManager manager = application.getWallet().getContext().masternodeListManager;
                if (manager != null) {
                    manager.resetMNList(true, false);
                }
            }
            if (deleteWalletFileOnShutdown) {
                log.info("removing wallet file and app data");
                application.finalizeWipe();
            }
        }

        if (bootStrapStream != null) {
            try {
                bootStrapStream.close();
            } catch (IOException x) {
            }
        }

        initExecutor.shutdownNow();
        super.onDestroy();

        log.info("service was up for " + ((System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60) + " minutes");
    }

    private void stopPeerGroup(final Wallet wallet) {
        if (peerGroup != null) {
            if (peerConnectivityListener != null) {
                peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
                peerGroup.removeConnectedEventListener(peerConnectivityListener);
            }
            if (wallet != null)
                peerGroup.removeWallet(wallet);
            peerGroup.stop();
            peerGroup = null;

            log.info("peergroup stopped");
            log.info("SPV[sid={}] peerGroup=stop", FASTBOOT_SESSION_ID);
        }
    }

}
