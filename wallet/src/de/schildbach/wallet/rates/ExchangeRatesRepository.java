package de.schildbach.wallet.rates;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.schildbach.wallet.AppDatabase;

/**
 * @author Samuel Barbosa
 */
public class ExchangeRatesRepository {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesRepository.class);
    private static ExchangeRatesRepository instance;

    private AppDatabase appDatabase;
    private Executor executor;
    private Deque<ExchangeRatesClient> exchangeRatesClients = new ArrayDeque<>();
    private static final String PRIMARY_FIAT_CURRENCY = "USDT";

    private static final long UPDATE_FREQ_MS = TimeUnit.MINUTES.toMillis(10);
    private long lastUpdated;

    public MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
    public MutableLiveData<Boolean> hasError = new MutableLiveData<>();

    private boolean isRefreshing = false;

    private boolean isFiltered = false;

    private ExchangeRatesRepository() {
        appDatabase = AppDatabase.getAppDatabase();
        executor = Executors.newSingleThreadExecutor();

        populateExchangeRatesStack();
        startPolling();
    }

    // START POLLING LOGIC
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors
            .newScheduledThreadPool(1);

    private void startPolling() {
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Polling for exchange rates...");
                    refreshRates(true);
                } catch (Exception e) {
                    log.warn("Price poll failed (quietly)", e);
                }
            }
        }, 0, 10, TimeUnit.MINUTES);
    }

    public static ExchangeRatesRepository getInstance() {
        if (instance == null) {
            instance = new ExchangeRatesRepository();
        }
        return instance;
    }

    private void populateExchangeRatesStack() {
        exchangeRatesClients.clear();
        exchangeRatesClients.addLast(ExplorerPriceClient.getInstance());
    }

    private void refreshRates() {
        this.refreshRates(false);
    }

    private synchronized void refreshRates(boolean forceRefresh) {
        // Removed: if (!shouldRefresh()) { return; } since we are now scheduled.

        if (exchangeRatesClients.isEmpty()) {
            populateExchangeRatesStack();
        }
        if (!forceRefresh && isRefreshing) {
            return;
        }
        isRefreshing = true;
        final ExchangeRatesClient exchangeRatesClient = exchangeRatesClients.pollFirst();
        if (exchangeRatesClient == null) {
            isRefreshing = false;
            isLoading.postValue(false);
            return;
        }
        isLoading.postValue(true);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<ExchangeRate> rates;
                try {
                    rates = exchangeRatesClient.getRates();
                    if (rates != null && !rates.isEmpty()) {
                        appDatabase.exchangeRatesDao().deleteAllExcept(PRIMARY_FIAT_CURRENCY);
                        appDatabase.exchangeRatesDao().insertAll(rates);
                        lastUpdated = System.currentTimeMillis();
                        populateExchangeRatesStack();
                        hasError.postValue(false);
                        isRefreshing = false;
                        log.info("exchange rates updated successfully with {}", exchangeRatesClient);
                    } else if (!exchangeRatesClients.isEmpty()) {
                        refreshRates(true);
                    } else {
                        handleRefreshError();
                    }
                } catch (Exception e) {
                    log.warn("failed to fetch exchange rates with {} (quietly)", exchangeRatesClient);
                    // Suppress crash, just try next or stop
                    if (!exchangeRatesClients.isEmpty()) {
                        refreshRates(true);
                    } else {
                        handleRefreshError();
                    }
                } finally {
                    isLoading.postValue(false);
                }
            }
        });
    }

    private void handleRefreshError() {
        isRefreshing = false;
        if (appDatabase.exchangeRatesDao().count() == 0) {
            hasError.postValue(true);
        }
    }

    private boolean shouldRefresh() {
        // Deprecated by scheduler, but kept for any legacy external calls just in case
        long now = System.currentTimeMillis();
        return lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS;
    }

    public LiveData<List<ExchangeRate>> getRates() {
        // Removed: if (shouldRefresh()) { refreshRates(); }
        // We now rely on the background poller.
        return appDatabase.exchangeRatesDao().getAll();
    }

    public LiveData<ExchangeRate> getRate(String currencyCode) {
        // Removed: if (shouldRefresh()) { refreshRates(); }
        return appDatabase.exchangeRatesDao().getRate(currencyCode);
    }

    public LiveData<List<ExchangeRate>> searchRates(String query) {
        return appDatabase.exchangeRatesDao().searchRates(query);
    }
}
