package de.schildbach.wallet.data.api;

import android.os.SystemClock;

import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ExplorerApiStatsRepository {
    private static final Logger log = LoggerFactory.getLogger(ExplorerApiStatsRepository.class);
    private static final long CACHE_WINDOW_MS = TimeUnit.SECONDS.toMillis(30);

    private final OkHttpClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<ApiStatus> apiStatusLiveData;
    private final MutableLiveData<NetworkStats> networkStatsLiveData;

    private volatile long lastFetchElapsedMs = -CACHE_WINDOW_MS;
    private volatile int lastCheckpointHeight = 0;
    private volatile String lastCheckpointHash;
    private volatile String baseUrl;

    public ExplorerApiStatsRepository(String baseUrl, MutableLiveData<ApiStatus> apiStatusLiveData,
            MutableLiveData<NetworkStats> networkStatsLiveData) {
        this.baseUrl = trimBaseUrl(baseUrl);
        this.apiStatusLiveData = apiStatusLiveData;
        this.networkStatsLiveData = networkStatsLiveData;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public MutableLiveData<ApiStatus> getApiStatus() {
        return apiStatusLiveData;
    }

    public MutableLiveData<NetworkStats> getNetworkStats() {
        return networkStatsLiveData;
    }

    public void refresh(boolean force) {
        final long now = SystemClock.elapsedRealtime();
        if (!force && now - lastFetchElapsedMs < CACHE_WINDOW_MS) {
            log.debug("Skipping explorer stats refresh; using cached result");
            return;
        }
        lastFetchElapsedMs = now;
        executor.execute(this::fetchAndPublish);
    }

    public void setCheckpointInfo(int height, String hash) {
        this.lastCheckpointHeight = height;
        this.lastCheckpointHash = hash;
    }

    public void setBaseUrl(String newBaseUrl) {
        this.baseUrl = trimBaseUrl(newBaseUrl);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void fetchAndPublish() {
        ApiStatus.State state = ApiStatus.State.HEALTHY;
        String error = null;
        int lastHttpCode = 0;

        long tipHeight = 0;
        double difficulty = Double.NaN;
        double networkHashrate = Double.NaN;
        int masternodeCount = 0;
        String priceUsd = null;
        int connections = -1;

        // 1. Get Network Hashrate
        try {
            FetchResult<Double> hashrateResult = fetchDouble("/api/getnetworkhashps");
            networkHashrate = hashrateResult.value;
            lastHttpCode = hashrateResult.httpCode;
        } catch (Exception e) {
            log.warn("Hashrate fetch failed: {}", e.toString());
            // Don't fail completely if just hashrate is missing
        }

        // 2. Get Masternode Count
        try {
            FetchResult<String> mnResult = fetchString("/api/getmasternodecount");
            if (mnResult.value != null) {
                // Use regex: "Total:\\s*(\\d+)" and "Enabled:\\s*(\\d+)"
                Pattern enabledPattern = Pattern.compile("Enabled:\\s*(\\d+)");
                Matcher enabledMatcher = enabledPattern.matcher(mnResult.value);
                if (enabledMatcher.find()) {
                    masternodeCount = Integer.parseInt(enabledMatcher.group(1));
                } else {
                    // Fallback or try Total if needed, but instructions say "Display Enabled"
                    Pattern totalPattern = Pattern.compile("Total:\\s*(\\d+)");
                    Matcher totalMatcher = totalPattern.matcher(mnResult.value);
                    if (totalMatcher.find()) {
                        // If enabled not found, maybe just use 0 or log?
                        // Instructions say: "Display Enabled as the Masternode count."
                        // If regex fails, we keep 0.
                    }
                }
            }
            lastHttpCode = mnResult.httpCode;
        } catch (Exception e) {
            log.warn("Masternode count fetch failed: {}", e.toString());
        }

        // 3. Get Price
        try {
            // Read json[0].price from /ext/summary
            FetchResult<String> priceResult = fetchString("/ext/summary");
            if (priceResult.value != null) {
                JSONArray jsonArray = new JSONArray(priceResult.value);
                if (jsonArray.length() > 0) {
                    JSONObject obj = jsonArray.getJSONObject(0);
                    // "price" field
                    double priceVal = obj.optDouble("price", Double.NaN);
                    if (!Double.isNaN(priceVal)) {
                        BigDecimal priceBd = BigDecimal.valueOf(priceVal);
                        // Format to EXACTLY 8 decimals
                        priceUsd = priceBd.setScale(8, RoundingMode.HALF_UP).toPlainString();
                    }
                }
            }
            lastHttpCode = priceResult.httpCode;
        } catch (Exception e) {
            log.warn("Price fetch failed: {}", e.toString());
        }

        // 4. Get Block Count (Tip Height)
        try {
            FetchResult<Long> blockCount = fetchLong("/api/getblockcount");
            tipHeight = blockCount.value;
            lastHttpCode = blockCount.httpCode;
        } catch (Exception e) {
            log.warn("Block count fetch failed: {}", e.toString());
            state = ApiStatus.State.OFFLINE;
            error = e.getMessage();
        }

        // 5. Get Difficulty
        try {
            FetchResult<Double> diffResult = fetchDouble("/api/getdifficulty");
            difficulty = diffResult.value;
            lastHttpCode = diffResult.httpCode;
        } catch (Exception e) {
            log.warn("Difficulty fetch failed: {}", e.toString());
        }

        // 6. Get Connection Count
        try {
            FetchResult<Long> connResult = fetchLong("/api/getconnectioncount");
            connections = connResult.value.intValue();
            lastHttpCode = connResult.httpCode;
        } catch (Exception e) {
            log.warn("Connection count fetch failed: {}", e.toString());
        }

        networkStatsLiveData.postValue(new NetworkStats(tipHeight, difficulty, networkHashrate, masternodeCount,
                priceUsd, connections, System.currentTimeMillis()));
        postStatus(state, error, lastHttpCode);
    }

    private FetchResult<Long> fetchLong(String path) throws IOException, NumberFormatException {
        Request request = new Request.Builder().url(url(path)).build();
        try (Response response = client.newCall(request).execute()) {
            final int httpCode = response.code();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + httpCode + " - " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            return new FetchResult<>(Long.parseLong(body.trim()), httpCode);
        }
    }

    private FetchResult<Double> fetchDouble(String path) throws IOException, NumberFormatException {
        Request request = new Request.Builder().url(url(path)).build();
        try (Response response = client.newCall(request).execute()) {
            final int httpCode = response.code();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + httpCode + " - " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            return new FetchResult<>(Double.parseDouble(body.trim()), httpCode);
        }
    }

    private FetchResult<String> fetchString(String path) throws IOException {
        Request request = new Request.Builder().url(url(path)).build();
        try (Response response = client.newCall(request).execute()) {
            final int httpCode = response.code();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + httpCode + " - " + response.message());
            }
            String body = response.body() != null ? response.body().string() : "";
            return new FetchResult<>(body, httpCode);
        }
    }

    private void postStatus(ApiStatus.State state, String error, int httpCode) {
        ApiStatus status = new ApiStatus(state, System.currentTimeMillis(), error, httpCode, lastCheckpointHeight,
                lastCheckpointHash, baseUrl);
        apiStatusLiveData.postValue(status);
    }

    private String url(String path) {
        if (path == null || path.isEmpty()) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private String trimBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static class FetchResult<T> {
        final T value;
        final int httpCode;

        FetchResult(T value, int httpCode) {
            this.value = value;
            this.httpCode = httpCode;
        }
    }
}
