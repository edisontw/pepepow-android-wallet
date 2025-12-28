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

package de.schildbach.wallet;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.multidex.MultiDexApplication;
import androidx.lifecycle.MutableLiveData;

import com.google.common.base.Stopwatch;
import com.jakewharton.processphoenix.ProcessPhoenix;

import org.bitcoinj.core.CoinDefinition;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.crypto.LinuxSecureRandom;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.dash.wallet.common.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import javax.annotation.Nullable;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.ui.LockScreenActivity;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet.ui.ShortcutComponentActivity;
import de.schildbach.wallet.ui.WalletUriHandlerActivity;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.ui.security.SecurityGuard;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.util.CrashReporter;
import org.pepepow.wallet.BuildConfig;
import org.pepepow.wallet.R;
import de.schildbach.wallet.data.api.ApiStatus;
import de.schildbach.wallet.data.api.ExplorerApiStatsRepository;
import de.schildbach.wallet.data.api.NetworkStats;

import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;

/**
 * @author Andreas Schildbach
 */
public class WalletApplication extends MultiDexApplication implements ViewModelStoreOwner {
    private static WalletApplication instance;
    private Configuration config;
    private ActivityManager activityManager;

    private boolean basicWalletInitalizationFinished = false;

    private Intent blockchainServiceIntent;

    private File walletFile;
    private Wallet wallet;
    private PackageInfo packageInfo;
    private org.bitcoinj.core.Context bitcoinContext;

    public org.bitcoinj.core.Context getBitcoinContext() {
        return bitcoinContext;
    }

    private boolean backupDisclaimerDismissed = false;

    public static final String ACTION_WALLET_REFERENCE_CHANGED = WalletApplication.class.getPackage().getName()
            + ".wallet_reference_changed";

    public enum WalletState {
        NOT_LOADED,
        LOADING,
        LOADED,
        FAILED
    }

    public static final int VERSION_CODE_SHOW_BACKUP_REMINDER = 205;

    public static final long TIME_CREATE_APPLICATION = System.currentTimeMillis();

    private static final Logger log = LoggerFactory.getLogger(WalletApplication.class);

    private boolean deviceWasLocked = false;

    private AutoLogout autoLogout;
    private final MutableLiveData<ApiStatus> apiStatusLiveData = new MutableLiveData<>();
    private final MutableLiveData<NetworkStats> networkStatsLiveData = new MutableLiveData<>();
    private ExplorerApiStatsRepository explorerApiStatsRepository;
    private int lastCheckpointHeight = 0;
    private String lastCheckpointHash;
    private volatile WalletState walletState = WalletState.NOT_LOADED;
    private Throwable lastWalletLoadError;
    private final MutableLiveData<WalletState> walletStateLiveData = new MutableLiveData<>(WalletState.NOT_LOADED);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long loadingStartTime = 0;

    private de.schildbach.wallet.data.api.ApiPowBootstrapper apiPowBootstrapper;

    private final ViewModelStore viewModelStore = new ViewModelStore();

    @androidx.annotation.NonNull
    @Override
    public ViewModelStore getViewModelStore() {
        return viewModelStore;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        instance = this;
    }

    public boolean walletFileExists() {
        return walletFile.exists();
    }

    private boolean isSpecialActivity(Activity activity) {
        return (activity instanceof OnboardingActivity)
                || (activity instanceof SendCoinsActivity)
                || (activity instanceof WalletUriHandlerActivity)
                || (activity instanceof ScanActivity)
                || (activity instanceof ShortcutComponentActivity);
    }

    private void initApiTracking() {
        ApiStatus initialStatus = new ApiStatus(ApiStatus.State.DEGRADED, 0, null, 0, lastCheckpointHeight,
                lastCheckpointHash, config.getApiBaseUrl());
        apiStatusLiveData.setValue(initialStatus);
    }

    public MutableLiveData<ApiStatus> getApiStatusLiveData() {
        return apiStatusLiveData;
    }

    public MutableLiveData<NetworkStats> getNetworkStatsLiveData() {
        return networkStatsLiveData;
    }

    public synchronized ExplorerApiStatsRepository getExplorerApiStatsRepository() {
        if (explorerApiStatsRepository == null) {
            explorerApiStatsRepository = new ExplorerApiStatsRepository(config.getApiBaseUrl(), apiStatusLiveData,
                    networkStatsLiveData);
            explorerApiStatsRepository.setCheckpointInfo(lastCheckpointHeight, lastCheckpointHash);
        } else {
            explorerApiStatsRepository.setBaseUrl(config.getApiBaseUrl());
        }
        return explorerApiStatsRepository;
    }

    public void refreshExplorerStats(boolean force) {
        getExplorerApiStatsRepository().refresh(force);
    }

    public synchronized void updateApiCheckpoint(int height, String hash) {
        if (height > 0) {
            lastCheckpointHeight = height;
        }
        if (hash != null && !hash.isEmpty()) {
            lastCheckpointHash = hash;
        }
        if (explorerApiStatsRepository != null) {
            explorerApiStatsRepository.setCheckpointInfo(lastCheckpointHeight, lastCheckpointHash);
        }
        ApiStatus current = apiStatusLiveData.getValue();
        if (current != null) {
            ApiStatus refreshed = new ApiStatus(current.getState(), current.getLastCheckedMillis(),
                    current.getLastErrorMessage(), current.getLastHttpCode(), lastCheckpointHeight, lastCheckpointHash,
                    current.getBaseUrl());
            apiStatusLiveData.postValue(refreshed);
        }
    }

    public synchronized void publishApiStatus(ApiStatus.State state, String errorMessage, int httpCode) {
        ApiStatus status = new ApiStatus(state, System.currentTimeMillis(), errorMessage, httpCode,
                lastCheckpointHeight,
                lastCheckpointHash, config.getApiBaseUrl());
        apiStatusLiveData.postValue(status);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log.info("WalletApplication.onCreate() thread={}", Thread.currentThread().getName());
        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this), getResources());
        autoLogout = new AutoLogout(config);
        initApiTracking();

        registerActivityLifecycleCallbacks(new ActivitiesTracker() {

            @Override
            protected void onStartedFirst(Activity activity) {
                autoLogout.setAppInBackground(false);
                if (config.getAutoLogoutEnabled() && (deviceWasLocked || autoLogout.shouldLogout())) {
                    lockTheApp(WalletApplication.this, activity);
                    if (autoLogout.isTimerActive()) {
                        autoLogout.stopTimer();
                    }
                }
            }

            @Override
            protected void onStoppedLast() {
                autoLogout.setAppInBackground(true);
            }
        });
        walletFile = getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);

        // Initialize the single shared Context for the application
        bitcoinContext = new org.bitcoinj.core.Context(Constants.NETWORK_PARAMETERS);
        org.bitcoinj.core.Context.propagate(bitcoinContext);

        // Delay wallet loading until BlockchainService requests it
        // if (walletFileExists()) {
        // fullInitialization();
        // }
        initEnvironment();
        registerDeviceInteractiveReceiver();

        // BUG FIX #6: Log if there's a pending explorer change from previous session
        de.schildbach.wallet.util.ExplorerConfig.logPendingExplorerChangeOnStartup(this);
    }

    public void loadWallet() {
        // Check state without locking first to avoid blocking Main Thread if background
        // thread is loading
        if (wallet != null || walletState == WalletState.LOADED) {
            log.info("loadWallet(): wallet already loaded (state={})", walletState);
            return;
        }
        if (walletState == WalletState.LOADING) {
            long elapsed = System.currentTimeMillis() - loadingStartTime;
            log.info("loadWallet(): already loading on another thread (elapsed={}ms)", elapsed);

            // Safety timeout check
            if (elapsed > 15000) { // 15 seconds
                log.error("WalletApplication: wallet load appears stuck for >15s; marking FAILED and notifying UI.");
                updateWalletState(WalletState.FAILED, new RuntimeException("Wallet load timed out"));
            }
            return;
        }

        synchronized (this) {
            // Double-check inside lock
            if (wallet != null || walletState == WalletState.LOADED) {
                return;
            }
            if (walletState == WalletState.LOADING) {
                return;
            }

            log.info("loadWallet(): starting wallet load on thread {}", Thread.currentThread().getName());
            loadingStartTime = System.currentTimeMillis();
            updateWalletState(WalletState.LOADING, null);
        }

        try {
            loadWalletFromProtobuf();
        } catch (final Exception e) {
            log.error("loadWallet(): unexpected failure", e);
            updateWalletState(WalletState.FAILED, e);
        }
    }

    public void fullInitialization() {
        initEnvironment();
        loadWallet();
    }

    public void initEnvironmentIfNeeded() {
        if (!basicWalletInitalizationFinished) {
            initEnvironment();
        }
    }

    private void initEnvironment() {
        basicWalletInitalizationFinished = true;

        new LinuxSecureRandom(); // init proper random number generator
        initLogging();

        if (!Constants.IS_PROD_BUILD) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads()
                    .permitDiskWrites().penaltyLog().build());
        }

        Threading.throwOnLockCycles();
        org.bitcoinj.core.Context.enableStrictMode();
        org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

        log.info("=== starting app using configuration: {}, {}", BuildConfig.FLAVOR,
                Constants.NETWORK_PARAMETERS.getId());

        packageInfo = packageInfoFromContext(this);

        CrashReporter.init(getCacheDir());

        Threading.uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread thread, final Throwable throwable) {
                log.info(CoinDefinition.coinName + "j uncaught exception", throwable);
                CrashReporter.saveBackgroundTrace(throwable, packageInfo);
            }
        };

        initMnemonicCode();

        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        blockchainServiceIntent = new Intent(this, BlockchainServiceImpl.class);

        // Initialize ApiPowBootstrapper
        de.schildbach.wallet.data.api.ApiHeaderClient apiClient = new de.schildbach.wallet.data.api.ApiHeaderClient(
                config.getApiBaseUrl());
        de.schildbach.wallet.data.api.PowVerifier powVerifier = new de.schildbach.wallet.data.api.PowVerifier(
                Constants.NETWORK_PARAMETERS);
        apiPowBootstrapper = new de.schildbach.wallet.data.api.ApiPowBootstrapper(this, apiClient, powVerifier,
                Constants.NETWORK_PARAMETERS);
    }

    public synchronized void setWallet(Wallet newWallet) {
        this.wallet = newWallet;
        if (!newWallet.hasKeyChain(Constants.BIP44_PATH)) {
            newWallet.addKeyChain(Constants.BIP44_PATH);
        }
        updateWalletState(WalletState.LOADED, null);
        broadcastWalletReferenceChanged();
    }

    private void broadcastWalletReferenceChanged() {
        final Intent broadcast = new Intent(ACTION_WALLET_REFERENCE_CHANGED);
        broadcast.setPackage(getPackageName());
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    public void saveWallet() {
        if (wallet == null || walletState != WalletState.LOADED) {
            log.warn("saveWallet(): wallet is null or not LOADED (state={}); skipping save", walletState);
            return;
        }

        try {
            protobufSerializeWallet(wallet);
        } catch (IOException x) {
            log.error("problem saving wallet", x);
        }
    }

    public void saveWalletAndFinalizeInitialization() {
        saveWallet();
        backupWallet();

        config.armBackupReminder();

        finalizeInitialization();
    }

    public void finalizeInitialization() {
        wallet.getContext().initDash(true, true);
        updateWalletState(WalletState.LOADED, null);

        if (config.versionCodeCrossed(packageInfo.versionCode, VERSION_CODE_SHOW_BACKUP_REMINDER)
                && !wallet.getImportedKeys().isEmpty()) {
            log.info("showing backup reminder once, because of imported keys being present");
            config.armBackupReminder();
        }

        config.updateLastVersionCode(packageInfo.versionCode);

        afterLoadWallet();

        cleanupFiles();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels();
        }

        autoLogout.setOnLogoutListener(new AutoLogout.OnLogoutListener() {
            @Override
            public void onLogout(boolean isAppInBackground) {
                if (!isAppInBackground) {
                    lockTheApp(WalletApplication.this, null);
                }
            }
        });
    }

    public void maybeStartAutoLogoutTimer() {
        autoLogout.setup();
    }

    public void resetAutoLogoutTimer() {
        autoLogout.resetTimerIfActive();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        // Transactions
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS,
                R.string.notification_transactions_channel_name,
                R.string.notification_transactions_channel_description,
                NotificationManager.IMPORTANCE_HIGH);
        // Synchronization
        createNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID_ONGOING,
                R.string.notification_synchronization_channel_name,
                R.string.notification_synchronization_channel_description,
                NotificationManager.IMPORTANCE_LOW);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId, @StringRes int channelName,
            @StringRes int channelDescription, int importance) {
        CharSequence name = getString(channelName);
        String description = getString(channelDescription);

        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        channel.setDescription(description);

        if (Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS.equals(channelId)) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received);
            channel.setSound(soundUri, attributes);
        }

        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void afterLoadWallet() {
        wallet.autosaveToFile(walletFile, Constants.Files.WALLET_AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS, null);

        // clean up spam
        try {
            wallet.cleanup();
        } catch (IllegalStateException x) {
            // Catch an inconsistent exception here and reset the blockchain. This is for
            // loading older wallets that had
            // txes with fees that were too low or dust that were stuck and could not be
            // sent. In a later version
            // the fees were fixed, then those stuck transactions became inconsistant and
            // the exception is thrown.
            if (x.getMessage().contains("Inconsistent spent tx:")) {
                File blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE),
                        Constants.Files.BLOCKCHAIN_FILENAME);
                blockChainFile.delete();
            } else
                throw x;
        }

        // make sure there is at least one recent backup
        if (!getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF).exists())
            backupWallet();
    }

    private void initLogging() {
        // create log dir
        final File logDir = new File(getFilesDir(), "log");
        logDir.mkdir();

        // migrate old logs
        final File oldLogDir = getDir("log", MODE_PRIVATE);
        if (oldLogDir.exists()) {
            for (final File logFile : oldLogDir.listFiles())
                if (logFile.isFile() && logFile.length() > 0)
                    logFile.renameTo(new File(logDir, logFile.getName()));
            oldLogDir.delete();
        }

        final File logFile = new File(logDir, "wallet.log");

        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
        filePattern.setContext(context);
        filePattern.setPattern("%d{HH:mm:ss,UTC} [%thread] %logger{0} - %msg%n");
        filePattern.start();

        final RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
        fileAppender.setContext(context);
        fileAppender.setFile(logFile.getAbsolutePath());

        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<ILoggingEvent>();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%d{yyyy-MM-dd,UTC}.log.gz");
        rollingPolicy.setMaxHistory(7);
        rollingPolicy.start();

        PreferenceManager.setDefaultValues(this, R.xml.preference_settings, false);
        fileAppender.setEncoder(filePattern);
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.start();

        final PatternLayoutEncoder logcatTagPattern = new PatternLayoutEncoder();
        logcatTagPattern.setContext(context);
        logcatTagPattern.setPattern("%logger{0}");
        logcatTagPattern.start();

        final PatternLayoutEncoder logcatPattern = new PatternLayoutEncoder();
        logcatPattern.setContext(context);
        logcatPattern.setPattern("[%thread] %msg%n");
        logcatPattern.start();

        final LogcatAppender logcatAppender = new LogcatAppender();
        logcatAppender.setContext(context);
        logcatAppender.setTagEncoder(logcatTagPattern);
        logcatAppender.setEncoder(logcatPattern);
        logcatAppender.start();

        final ch.qos.logback.classic.Logger log = context.getLogger(Logger.ROOT_LOGGER_NAME);
        log.addAppender(fileAppender);
        log.addAppender(logcatAppender);
        log.setLevel(Level.INFO);
    }

    private static final String BIP39_WORDLIST_FILENAME = "bip39-wordlist.txt";

    private void initMnemonicCode() {
        try {
            final Stopwatch watch = Stopwatch.createStarted();
            MnemonicCode.INSTANCE = new MnemonicCode(getAssets().open(BIP39_WORDLIST_FILENAME), null);
            watch.stop();
            log.info("BIP39 wordlist loaded from: '{}', took {}", BIP39_WORDLIST_FILENAME, watch);
        } catch (final IOException x) {
            throw new Error(x);
        }
    }

    public Configuration getConfiguration() {
        return config;
    }

    public synchronized Wallet getWallet() {
        return wallet;
    }

    private volatile BlockchainService blockchainService;

    public void setBlockchainService(BlockchainService service) {
        this.blockchainService = service;
    }

    public BlockchainService getBlockchainService() {
        return blockchainService;
    }

    public Wallet getActiveWallet() {
        // Since ApiSessionWallet is no longer a bitcoinj.Wallet, we always return the
        // canonical wallet here.
        // UI components that need the session data should access the
        // WalletUsabilityState or BlockchainService directly.
        return getWallet();
    }

    public synchronized Wallet getWalletOrNull() {
        return wallet;
    }

    public WalletState getWalletState() {
        return walletState;
    }

    public MutableLiveData<WalletState> getWalletStateLiveData() {
        return walletStateLiveData;
    }

    private synchronized void updateWalletState(WalletState newState, @Nullable Throwable error) {
        walletState = newState;
        if (newState == WalletState.FAILED) {
            lastWalletLoadError = error;
        } else {
            lastWalletLoadError = null;
        }
        log.info("Wallet state changed to {} (thread={})", newState, Thread.currentThread().getName());
        walletStateLiveData.postValue(newState);
    }

    public de.schildbach.wallet.data.api.ApiPowBootstrapper getBootstrapper() {
        return apiPowBootstrapper;
    }

    private boolean isContextMismatch(Throwable t) {
        while (t != null) {
            if (t instanceof IllegalStateException) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("Context does not match implicit network context")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private void loadWalletFromProtobuf() {
        FileInputStream walletStream = null;
        Wallet loadedWallet = null;
        log.info("loadWalletFromProtobuf(): begin (thread={})", Thread.currentThread().getName());
        log.info("[main] loadWalletFromProtobuf(): using params = " + Constants.NETWORK_PARAMETERS.getId());
        if (org.bitcoinj.core.Context.get() != null) {
            log.info("[main] current Context params = " + org.bitcoinj.core.Context.get().getParams().getId());
        }

        try {
            final Stopwatch watch = Stopwatch.createStarted();
            walletStream = new FileInputStream(walletFile);

            // Ensure dashj Context is in place
            org.bitcoinj.core.Context.propagate(bitcoinContext);

            loadedWallet = new WalletProtobufSerializer().readWallet(walletStream);

            if (!loadedWallet.getParams().equals(Constants.NETWORK_PARAMETERS))
                throw new UnreadableWalletException(
                        "bad wallet network parameters: " + loadedWallet.getParams().getId());

            log.info("wallet loaded from: '{}', took {}", walletFile, watch);
        } catch (final FileNotFoundException x) {
            log.error("problem loading wallet", x);

            showToastSafe(x.getClass().getName());

            loadedWallet = restoreWalletFromBackup();
        } catch (final UnreadableWalletException x) {
            if (isContextMismatch(x)) {
                // This is NOT a corrupted wallet; it means our app/network config is wrong.
                log.error(
                        "[main] loadWalletFromProtobuf(): context mismatch when reading wallet-protobuf, refusing to delete wallet or create new one",
                        x);
                // Re-throw or wrap as a runtime to abort startup; do NOT try backup or new
                // wallet here.
                throw new RuntimeException(
                        "Wallet network context mismatch; please fix NetworkParameters configuration", x);
            }

            log.error("problem loading wallet", x);

            showToastSafe(x.getClass().getName());

            loadedWallet = restoreWalletFromBackup();
        } catch (final IllegalStateException x) {
            if (isContextMismatch(x)) {
                log.error("[main] loadWalletFromProtobuf(): context mismatch (ISE), refusing to delete wallet", x);
                throw new RuntimeException(
                        "Wallet network context mismatch; please fix NetworkParameters configuration", x);
            }
            throw x;
        } finally {
            if (walletStream != null) {
                try {
                    walletStream.close();
                } catch (final IOException x) {
                    // swallow
                }
            }
        }

        if (loadedWallet == null) {
            updateWalletState(WalletState.FAILED, new IllegalStateException("Loaded wallet is null"));
            return;
        }

        if (!loadedWallet.isConsistent()) {
            showToastSafe("inconsistent wallet: " + walletFile);

            loadedWallet = restoreWalletFromBackup();
        }

        if (!loadedWallet.getParams().equals(Constants.NETWORK_PARAMETERS))
            throw new Error("bad wallet network parameters: " + loadedWallet.getParams().getId());

        setWallet(loadedWallet);
        finalizeInitialization();
        log.info("loadWalletFromProtobuf(): finished successfully (thread={})",
                Thread.currentThread().getName());
    }

    private Wallet restoreWalletFromBackup() {
        InputStream is = null;

        try {
            is = openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);

            org.bitcoinj.core.Context.propagate(bitcoinContext);

            final Wallet restoredWallet = new WalletProtobufSerializer().readWallet(is, true, null);

            if (!restoredWallet.isConsistent())
                throw new Error("inconsistent backup");

            restoredWallet.addKeyChain(Constants.BIP44_PATH);

            resetBlockchain();

            showToastSafe(getString(R.string.toast_wallet_reset));

            log.info("wallet restored from backup: '" + Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "'");

            return restoredWallet;
        } catch (final IOException | UnreadableWalletException | Error x) {
            if (isContextMismatch(x)) {
                log.error("[main] restoreWalletFromBackup(): context mismatch, refusing to delete backup", x);
                // Do NOT delete backup; rethrow to abort startup.
                throw new RuntimeException(
                        "Backup wallet network context mismatch; please fix NetworkParameters configuration", x);
            } else if (x instanceof IllegalStateException && isContextMismatch(x)) {
                log.error("[main] restoreWalletFromBackup(): context mismatch (ISE), refusing to delete backup", x);
                throw new RuntimeException(
                        "Backup wallet network context mismatch; please fix NetworkParameters configuration", x);
            }

            log.error("cannot read backup, creating new wallet instead", x);

            // Delete the corrupted backup to prevent future issues
            File backupFile = getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
            if (backupFile.exists()) {
                boolean deleted = backupFile.delete();
                log.info("Deleted corrupted backup file: " + deleted);
            }

            showToastSafe("Backup corrupted, creating new wallet");

            // Fallback to creating a new wallet
            Wallet fallbackWallet = new Wallet(Constants.NETWORK_PARAMETERS);
            fallbackWallet.addKeyChain(Constants.BIP44_PATH);
            return fallbackWallet;
        } finally {
            try {
                if (is != null)
                    is.close();
            } catch (final IOException x) {
                // swallow
            }
        }
    }

    private void protobufSerializeWallet(final Wallet wallet) throws IOException {
        if (wallet == null) {
            log.warn("protobufSerializeWallet(): wallet is null; skipping save");
            return;
        }
        final Stopwatch watch = Stopwatch.createStarted();
        wallet.saveToFile(walletFile);
        watch.stop();

        log.info("wallet saved to: '{}', took {}", walletFile, watch);
    }

    public void backupWallet() {
        final Stopwatch watch = Stopwatch.createStarted();
        final Protos.Wallet.Builder builder = new WalletProtobufSerializer().walletToProto(wallet).toBuilder();

        // strip redundant
        builder.clearTransaction();
        builder.clearLastSeenBlockHash();
        builder.setLastSeenBlockHeight(-1);
        builder.clearLastSeenBlockTimeSecs();
        final Protos.Wallet walletProto = builder.build();

        OutputStream os = null;

        try {
            os = openFileOutput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, Context.MODE_PRIVATE);
            walletProto.writeTo(os);
            watch.stop();
            log.info("wallet backed up to: '{}', took {}", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, watch);
        } catch (final IOException x) {
            log.error("problem writing wallet backup", x);
        } finally {
            try {
                os.close();
            } catch (final IOException x) {
                // swallow
            }
        }
    }

    private void cleanupFiles() {
        for (final String filename : fileList()) {
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_BASE58)
                    || filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.')
                    || filename.endsWith(".tmp")) {
                final File file = new File(getFilesDir(), filename);
                log.info("removing obsolete file: '{}'", file);
                file.delete();
            }
        }
    }

    public void startBlockchainService(final boolean cancelCoinsReceived) {
        if (cancelCoinsReceived) {
            Intent blockchainServiceCancelCoinsReceivedIntent = new Intent(
                    BlockchainService.ACTION_CANCEL_COINS_RECEIVED, null,
                    this, BlockchainServiceImpl.class);
            startService(blockchainServiceCancelCoinsReceivedIntent);
        } else {
            startService(blockchainServiceIntent);
        }
    }

    public void stopBlockchainService() {
        stopService(blockchainServiceIntent);
    }

    public void resetBlockchain() {
        // implicitly stops blockchain service
        Intent blockchainServiceResetBlockchainIntent = new Intent(BlockchainService.ACTION_RESET_BLOCKCHAIN, null,
                this,
                BlockchainServiceImpl.class);
        startService(blockchainServiceResetBlockchainIntent);
    }

    public void replaceWallet(final Wallet newWallet) {
        resetBlockchain();
        if (wallet != null) {
            wallet.shutdownAutosaveAndWait();
        }

        setWallet(newWallet);
        config.maybeIncrementBestChainHeightEver(newWallet.getLastBlockSeenHeight());
        afterLoadWallet();
    }

    public void processDirectTransaction(final Transaction tx) throws VerificationException {
        if (wallet.isTransactionRelevant(tx)) {
            wallet.receivePending(tx, null);
            broadcastTransaction(tx);
        }
    }

    public void broadcastTransaction(final Transaction tx) {
        final Intent intent = new Intent(BlockchainService.ACTION_BROADCAST_TRANSACTION, null, this,
                BlockchainServiceImpl.class);
        intent.putExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH, tx.getHash().getBytes());
        startService(intent);
    }

    public static PackageInfo packageInfoFromContext(final Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (final NameNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    public PackageInfo packageInfo() {
        return packageInfo;
    }

    public final String applicationPackageFlavor() {
        final String packageName = getPackageName();
        final int index = packageName.lastIndexOf('_');

        if (index != -1)
            return packageName.substring(index + 1);
        else
            return null;
    }

    public static String httpUserAgent(final String versionName) {
        final VersionMessage versionMessage = new VersionMessage(Constants.NETWORK_PARAMETERS, 0);
        versionMessage.appendToSubVer(Constants.USER_AGENT, versionName, null);
        return versionMessage.subVer;
    }

    public String httpUserAgent() {
        return httpUserAgent(packageInfo().versionName);
    }

    public boolean isLowRamDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            return activityManager.isLowRamDevice();
        else
            return activityManager.getMemoryClass() <= Constants.MEMORY_CLASS_LOWEND;
    }

    public int maxConnectedPeers() {
        return isLowRamDevice() ? 4 : 6;
    }

    /**
     * Low memory devices (currently 1GB or less) and 32 bit devices will require
     * fewer scrypt hashes on the PIN+salt (handled by dashj)
     *
     * @return The number of scrypt interations
     */
    public int scryptIterationsTarget() {
        boolean is64bitABI = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? Build.SUPPORTED_64_BIT_ABIS.length != 0
                : false;
        return (isLowRamDevice() || !is64bitABI) ? Constants.SCRYPT_ITERATIONS_TARGET_LOWRAM
                : Constants.SCRYPT_ITERATIONS_TARGET;
    }

    public static void scheduleStartBlockchainService(final Context context) {
        final Configuration config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context),
                context.getResources());
        final long lastUsedAgo = config.getLastUsedAgo();

        // apply some backoff
        final long alarmInterval;
        if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_JUST_MS)
            alarmInterval = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
        else if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS)
            alarmInterval = AlarmManager.INTERVAL_HALF_DAY;
        else
            alarmInterval = AlarmManager.INTERVAL_DAY;

        log.info("last used {} minutes ago, rescheduling blockchain sync in roughly {} minutes",
                lastUsedAgo / DateUtils.MINUTE_IN_MILLIS, alarmInterval / DateUtils.MINUTE_IN_MILLIS);

        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent alarmIntent;

        Intent serviceIntent = new Intent(context, BlockchainServiceImpl.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            serviceIntent.putExtra(BlockchainServiceImpl.START_AS_FOREGROUND_EXTRA, true);
            alarmIntent = PendingIntent.getForegroundService(context, 0, serviceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            alarmIntent = PendingIntent.getService(context, 0, serviceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
        alarmManager.cancel(alarmIntent);

        // workaround for no inexact set() before KitKat
        final long now = System.currentTimeMillis();
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + alarmInterval, AlarmManager.INTERVAL_DAY,
                alarmIntent);
    }

    /**
     * Removes all the data and restarts the app showing onboarding screen.
     */
    public void triggerWipe(final Context context) {
        log.info("Removing all the data and restarting the app.");

        startService(new Intent(BlockchainService.ACTION_WIPE_WALLET, null, this, BlockchainServiceImpl.class));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void finalizeWipe() {
        if (walletFile.exists()) {
            if (wallet != null) {
                wallet.shutdownAutosaveAndWait();
            }
            walletFile.delete();
        }
        if (walletFile.exists()) {
            walletFile.delete();
        }
        cleanupFiles();
        config.clear();
        PinRetryController.getInstance().clearPinFailPrefs();
        try {
            new SecurityGuard().removeKeys();
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            log.warn("error occurred when removing security keys", e);
        }

        File walletBackupFile = getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);
        if (walletBackupFile.exists()) {
            walletBackupFile.delete();
        }
        wallet = null;
        updateWalletState(WalletState.NOT_LOADED, null);
        ProcessPhoenix.triggerRebirth(this);
    }

    public boolean isBackupDisclaimerDismissed() {
        return backupDisclaimerDismissed;
    }

    public void setBackupDisclaimerDismissed(boolean backupDisclaimerDismissed) {
        this.backupDisclaimerDismissed = backupDisclaimerDismissed;
    }

    public static WalletApplication getInstance() {
        return instance;
    }

    private void registerDeviceInteractiveReceiver() {

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                KeyguardManager myKM = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                deviceWasLocked |= Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ? myKM.isDeviceLocked()
                        : myKM.inKeyguardRestrictedInputMode();
            }
        }, filter);
    }

    private void lockTheApp(Context context, Activity activity) {
        boolean useActivity = activity != null && !activity.isFinishing();
        Context ctx = useActivity ? activity : this;

        if (!isSpecialActivity(activity)) {
            Intent lockScreenIntent = LockScreenActivity.createIntent(ctx);
            if (useActivity) {
                activity.startActivity(lockScreenIntent);
            } else {
                lockScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(lockScreenIntent);
            }
        }
        deviceWasLocked = false;
    }

    private void showToastSafe(final CharSequence text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(WalletApplication.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
