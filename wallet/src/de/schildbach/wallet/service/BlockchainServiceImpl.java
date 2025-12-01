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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
import de.schildbach.wallet.data.api.PowVerifier;
import de.schildbach.wallet.data.api.ApiSyncManager;
import de.schildbach.wallet.data.api.ApiStatus;
import de.schildbach.wallet.data.api.ApiPowBootstrapper;
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

    private boolean connectivityReceiverRegistered = false;
    private boolean tickReceiverRegistered = false;
    private boolean walletListenersRegistered = false;
    private boolean spvInitialized = false;
    private final AtomicBoolean spvReady = new AtomicBoolean(false);

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

    private static final Logger log = LoggerFactory.getLogger(BlockchainServiceImpl.class);

    public static final String START_AS_FOREGROUND_EXTRA = "start_as_foreground";

    private boolean isApiMode() {
        return !spvInitialized
                && (selectedSyncMode == SyncMode.FAST_API_10POW || selectedSyncMode == SyncMode.API_1000POW);
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
            changed(peerCount);
        }

        @Override
        public void onPeerDisconnected(final Peer peer, final int peerCount) {
            this.peerCount = peerCount;
            changed(peerCount);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
            if (Configuration.PREFS_KEY_CONNECTIVITY_NOTIFICATION.equals(key))
                changed(peerCount);
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
        protected void progress(double pct, int blocksLeft, Date date) {
            super.progress(pct, blocksLeft, date);
            if (pct < 0) {
                pct = 0;
            }
            final SyncProgressEvent event = new SyncProgressEvent(pct);
            log.info(event.toString());
            EventBus.getDefault().postSticky(event);

        }

        @Override
        protected void doneDownload() {
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
        if (isApiMode()) {
            return;
        }

        final Wallet wallet = application.getWallet();

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

            log.info("starting peergroup");
            peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain);
            peerGroup.setRequiredServices(VersionMessage.NODE_NETWORK);

            if (selectedSyncMode == SyncMode.FAST_API_10POW || selectedSyncMode == SyncMode.API_1000POW) {
                boolean isFastApi = (selectedSyncMode == SyncMode.FAST_API_10POW);
                AbstractBlockChain.FAST_API_10POW_ENABLED = isFastApi;
                Constants.FAST_API_10POW_ENABLED_FOR_CORE = isFastApi;
                log.info("Disabling downloadTxDependencies for FAST_API_10POW/API_1000POW");
                peerGroup.setDownloadTxDependencies(0);
                long fastCatchupTime = blockChain.getChainHead().getHeader().getTimeSeconds();
                log.info("Setting PeerGroup fast catchup time to: {}", fastCatchupTime);
                peerGroup.setFastCatchupTimeSecs(fastCatchupTime);

                log.info("Enabling relaxed verification for FAST_API_10POW");
                AbstractBlockChain.ALLOW_MISSING_PARENTS = true;
                peerGroup.setStallDetectionEnabled(false);
            } else {
                AbstractBlockChain.ALLOW_MISSING_PARENTS = false;
                peerGroup.setDownloadTxDependencies(0); // recursive implementation causes StackOverflowError
            }

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
            peerGroup.startAsync();

            log.info("Starting P2P BlockChain Download...");
            peerGroup.startBlockChainDownload(blockchainDownloadListener);
            schedulePeerGroupConnectTimeout();
        } else if (!impediments.isEmpty() && peerGroup != null) {
            stopPeerGroup(wallet);
        }

        broadcastBlockchainState();
    }

    // startApiSync removed

    private void showApiFallbackToast() {
        Runnable toastRunnable = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(BlockchainServiceImpl.this,
                        "API sync failed - falling back to SPV headers", Toast.LENGTH_LONG).show();
            }
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            toastRunnable.run();
        } else {
            handler.post(toastRunnable);
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
                application.updateApiCheckpoint(head.getHeight(), head.getHeader().getHashAsString());
                application.publishApiStatus(ApiStatus.State.HEALTHY, null, 200);
            }
        } catch (BlockStoreException e) {
            log.error("Failed to read API chain head", e);
        }
    }

    private void stopPeerGroup(final Wallet wallet) {
        if (peerGroup == null)
            return;

        log.info("stopping peergroup");
        peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
        peerGroup.removeConnectedEventListener(peerConnectivityListener);
        peerGroup.removeWallet(wallet);
        peerGroup.stopAsync();
        peerGroup = null;
        cancelPeerGroupConnectTimeout();

        if (wakeLock != null && wakeLock.isHeld()) {
            log.debug("releasing wakelock");
            wakeLock.release();
        }
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
        selectedSyncMode = config.getSyncMode();
        log.info("Initializing sync pipeline. Mode: {} (thread={})", selectedSyncMode,
                Thread.currentThread().getName());

        // Set global flags based on mode
        if (selectedSyncMode == SyncMode.FAST_API_10POW) {
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = true;
            AbstractBlockChain.FAST_API_10POW_ENABLED = true;
        } else {
            Constants.FAST_API_10POW_ENABLED_FOR_CORE = false;
            AbstractBlockChain.FAST_API_10POW_ENABLED = false;
        }

        if (selectedSyncMode == SyncMode.FAST_API_10POW || selectedSyncMode == SyncMode.API_1000POW) {
            new Thread(() -> {
                // Ensure dashj Context is consistent on this thread
                org.bitcoinj.core.Context.propagate(((WalletApplication) getApplication()).getBitcoinContext());
                try {
                    log.info("initSyncPipeline(): starting bootstrap on thread {}", Thread.currentThread().getName());
                    runBootstrapIfNeeded();
                } finally {
                    log.info("initSyncPipeline(): bootstrap finished, calling startAfterBootstrapAsync()");
                    handler.post(this::startAfterBootstrapAsync);
                }
            }, "bootstrap-runner").start();
        } else {
            // FULL_SPV or other modes
            startAfterBootstrapAsync();
        }
    }

    private void runBootstrapIfNeeded() {
        try {
            final de.schildbach.wallet.data.api.ApiPowBootstrapper bootstrapper = application.getBootstrapper();

            // Open blockStore if not already open
            if (blockStore == null) {
                blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
            }

            log.info("Running bootstrap for mode {}", selectedSyncMode);
            boolean success = bootstrapper.runBootstrapIfNeeded(blockStore, Constants.NETWORK_PARAMETERS);

            // Capture the tip from the store if bootstrap was successful or if we have
            // headers
            try {
                StoredBlock head = blockStore.getChainHead();
                apiBestChainHeight = head.getHeight();
                apiBestChainDate = head.getHeader().getTime();
            } catch (Exception e) {
                log.warn("Failed to read chain head after bootstrap", e);
            }

            // IMPORTANT: Do NOT close blockStore. It is passed to initializeSpv open.
            // blockStore.close();
            // blockStore = null;

        } catch (Exception e) {
            log.error("Bootstrap failed", e);
            // If bootstrap failed, we might want to close it, or just leave it for
            // initializeSpv to handle/reset
            // But to be safe and follow "open ONCE in initializeSpv" (if we consider this
            // the one time),
            // we keep it open.
            // However, if we want initializeSpv to be the canonical opener, we might need
            // to close it here?
            // User said: "The blockStore is NEVER closed by ApiPowBootstrapper."
            // User said: "Ensure runBootstrapIfNeeded now assumes blockStore is open and
            // will NOT close it."
            // So we keep it open.
        } finally {
            // We do NOT call startAfterBootstrap here anymore, it is called in
            // initSyncPipeline's finally block
            // handler.post(this::startAfterBootstrap);
        }
    }

    private void startAfterBootstrapAsync() {
        if (initExecutor.isShutdown()) {
            log.warn("startAfterBootstrapAsync: initExecutor is shutdown, ignoring.");
            return;
        }
        initExecutor.execute(() -> {
            Thread.currentThread().setName("blockchain-init");
            // Ensure dashj Context is consistent on this thread
            org.bitcoinj.core.Context.propagate(((WalletApplication) getApplication()).getBitcoinContext());

            log.info("startAfterBootstrap: Loading wallet on {}", Thread.currentThread().getName());
            application.loadWallet();
            final Wallet wallet = application.getWallet();

            if (wallet == null) {
                log.error("Wallet failed to load! Cannot start blockchain service.");
                spvReady.set(false);
                stopSelf();
                return;
            }

            initializeSpv(wallet);

            if (!spvInitialized) {
                log.warn("SPV initialization did not complete successfully; skipping peer start for now.");
                spvReady.set(false);
            } else {
                spvReady.set(true);
            }
        });
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
    public void onDestroy() {
        log.info(".onDestroy()");
        log.debug(".onDestroy()");
        spvReady.set(false);

        WalletApplication.scheduleStartBlockchainService(this); // disconnect feature

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

        if (peerGroup != null) {
            peerGroup.removeDisconnectedEventListener(peerConnectivityListener);
            peerGroup.removeConnectedEventListener(peerConnectivityListener);
            if (application.getWallet() != null)
                peerGroup.removeWallet(application.getWallet());
            peerGroup.stop();

            log.info("peergroup stopped");
        }

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

        // Dash Specific

        // Constants.NETWORK_PARAMETERS.masternodeDB.write(Constants.NETWORK_PARAMETERS.masternodeManager);
        // application.saveMasternodes();

        // Dash Specific

        if (wakeLock != null && wakeLock.isHeld()) {
            log.debug("wakelock still held, releasing");
            wakeLock.release();
        }

        if (resetBlockchainOnShutdown || deleteWalletFileOnShutdown) {
            log.info("removing blockchain");
            // noinspection ResultOfMethodCallIgnored
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
                // do nothing
            }
        }

        initExecutor.shutdownNow();
        super.onDestroy();

        log.info("service was up for " + ((System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60) + " minutes");
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
        if (isApiMode()) {
            return new BlockchainState(apiBestChainDate, apiBestChainHeight, false, impediments, 0, 0,
                    apiSyncPercentage);
        }

        if (blockChain == null) {
            return new BlockchainState(new Date(0), 0, false, impediments, 0, 0, 0);
        }

        final StoredBlock chainHead = blockChain.getChainHead();
        final Date bestChainDate = chainHead.getHeader().getTime();
        final int bestChainHeight = chainHead.getHeight();
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

        return new BlockchainState(bestChainDate, bestChainHeight, replaying, impediments, chainLockHeight,
                mnListHeight, percentageSync());
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
        spvReady.set(false);
        log.info("initializeSpv(): begin (thread={})", Thread.currentThread().getName());
        // Ensure we always use the latest sync mode from config
        selectedSyncMode = config.getSyncMode();
        Constants.FAST_API_10POW_ENABLED_FOR_CORE = (selectedSyncMode == SyncMode.FAST_API_10POW);
        log.info("PEPEPOW-FAST-API: syncMode=" + selectedSyncMode +
                ", FAST_API_10POW_ENABLED_FOR_CORE=" + Constants.FAST_API_10POW_ENABLED_FOR_CORE);

        final boolean blockChainFileExists = blockChainFile.exists();

        try {
            bootStrapStream = getAssets().open(Constants.Files.MNLIST_BOOTSTRAP_FILENAME);
            SimplifiedMasternodeListManager.setBootStrapStream(bootStrapStream, null, 0);
        } catch (IOException x) {
            log.info("cannot load the boot strap stream.  " + x.getMessage());
        }

        if (!blockChainFileExists) {
            final SyncMode syncMode = config.getSyncMode();
            if (syncMode == SyncMode.FAST_API_10POW || syncMode == SyncMode.API_1000POW) {
                log.info(
                        "Blockchain file missing, but FAST_API_10POW enabled. Creating new store WITHOUT resetting wallet.");
            } else {
                log.info("blockchain does not exist, resetting wallet");
                wallet.reset();
                try {
                    SimplifiedMasternodeListManager manager = wallet.getContext().masternodeListManager;
                    if (manager != null)
                        manager.resetMNList(true, true);
                } catch (RuntimeException x) {
                    // swallow this exception. It is thrown when there is not a bootstrap mnlist
                    // file
                    // there is not a bootstrap mnlist file for testnet
                }
            }
        }

        try {
            // Open SPVBlockStore ONCE
            if (blockStore == null) {
                log.info("Initializing SPVBlockStore from file: {}", blockChainFile.getAbsolutePath());
                try {
                    blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
                } catch (BlockStoreException e) {
                    log.error("Failed to open blockstore, resetting...", e);
                    blockChainFile.delete();
                    blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
                }
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
                    log.error("Failed to read chain head after re-open, disabling sync for this session.", retryEx);
                    stopSelf();
                    return;
                }
            }
            int localHeight = chainHead.getHeight();
            log.error("DEBUG: initializeSpv(): localHeight=" + localHeight);
            log.info("initializeSpv(): existing localHeight=" + localHeight);

            // Fetch apiTipHeight via ApiHeaderClient (or reuse the value if bootstrap
            // already fetched it)
            // We initialize ApiHeaderClient here to fetch tip if needed
            ApiHeaderClient apiClient = new ApiHeaderClient(config.getApiBaseUrl());
            // We cannot run network on main thread.
            // If apiBestChainHeight is 0 (not set by bootstrap), we might skip this check
            // or rely on cached value?
            // For now, we rely on apiBestChainHeight being set by bootstrap or previous
            // runs.
            // If it is 0, we might want to skip the reset logic to avoid false positives.
            int apiTipHeight = apiBestChainHeight;
            log.error("DEBUG: initializeSpv(): apiTipHeight=" + apiTipHeight);

            // RESET LOGIC
            if (apiTipHeight > 0 && localHeight > apiTipHeight + 10) {
                log.error("DEBUG: RESET triggered! localHeight=" + localHeight +
                        " > apiTipHeight=" + apiTipHeight + " + 10");
                log.warn("initializeSpv(): localHeight=" + localHeight +
                        " ahead of apiTipHeight=" + apiTipHeight +
                        ". Resetting SPV blockstore.");

                blockStore.close();
                if (blockChainFile.exists()) {
                    blockChainFile.delete();
                }
                // Recreate a fresh blockstore
                if (!reopenBlockStoreFile()) {
                    log.error("Unable to recreate SPV blockstore after reset, disabling sync for this session.");
                    stopSelf();
                    return;
                }
                chainHead = blockStore.getChainHead();
                localHeight = chainHead.getHeight(); // likely 0 or genesis
            }

            log.info("SPVBlockStore initialized. Chain head height: {}, hash: {}",
                    chainHead.getHeight(), chainHead.getHeader().getHashAsString());

            final long earliestKeyCreationTime = wallet.getEarliestKeyCreationTime();

            if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                final SyncMode syncMode = config.getSyncMode();
                if (syncMode == SyncMode.FAST_API_10POW || syncMode == SyncMode.API_1000POW) {
                    log.info("API Sync enabled. Skipping legacy checkpoints.");
                } else if (!config.isFullSyncEnabled()) {
                    try {
                        final Stopwatch watch = Stopwatch.createStarted();
                        final InputStream checkpointsInputStream = getAssets()
                                .open(Constants.Files.CHECKPOINTS_FILENAME);
                        CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore,
                                earliestKeyCreationTime);
                        watch.stop();
                        log.info("checkpoints loaded from '{}', took {}", Constants.Files.CHECKPOINTS_FILENAME, watch);
                    } catch (final IOException x) {
                        log.error("problem reading checkpoints, continuing without", x);
                    }
                } else {
                    log.info("Full validation sync enabled. Skipping checkpoints and validating entire chain.");
                    Toast.makeText(this, R.string.preferences_full_sync_active_toast, Toast.LENGTH_LONG).show();
                }
            }
        } catch (final BlockStoreException x) {
            blockChainFile.delete();
            SimplifiedMasternodeListManager manager = application.getWallet().getContext().masternodeListManager;
            if (manager != null) {
                manager.resetMNList(true, true);
            }

            final String msg = "blockstore cannot be created";
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
            blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
        } catch (final BlockStoreException x) {
            throw new Error("blockchain cannot be created", x);
        }
        Log.i("SPV", "chain head height=" + blockChain.getChainHead().getHeight());

        spvInitialized = true;
        spvReady.set(true);
        log.info("initializeSpv(): completed; chainHeadHeight={}, apiTipHeight={}",
                blockChain.getChainHead().getHeight(),
                apiBestChainHeight);

        if (!Constants.isFullReplayAllowed() && config.isRestoringBackup()) {
            log.info("FAST_API_10POW: Forcing restoringBackup=false (no full replay in this mode).");
            config.setRestoringBackup(false);
        }
        startPeerGroup();
    }

    private boolean reopenBlockStoreFile() {
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
            log.error("Re-open of SPVBlockStore failed", reopenEx);
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
                // Ensure fast catchup is set to the current head time
                long fastCatchupTime = blockChain.getChainHead().getHeader().getTimeSeconds();
                log.info("Setting fast catchup time to: {}", fastCatchupTime);
                // We can't set this on peerGroup directly before it's created, but we can
                // ensure
                // it's used when peerGroup is created in updatePeerGroup()
            }
        }

        // Force update to start peer group immediately if network is available
        updatePeerGroup();
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
}
