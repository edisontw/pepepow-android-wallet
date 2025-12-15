package de.schildbach.wallet.data.api;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.bitcoinj.core.Coin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Explorer client focused on wallet/address data for FAST_API_10POW snapshots.
 */
public class ApiWalletClient {
    private static final Logger log = LoggerFactory.getLogger(ApiWalletClient.class);
    private final OkHttpClient client;
    private final String baseUrl;
    @Nullable
    private final ApiHeaderClient headerClient;

    public ApiWalletClient(String baseUrl) {
        this(baseUrl, null);
    }

    public ApiWalletClient(String baseUrl, @Nullable ApiHeaderClient headerClient) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.headerClient = headerClient;
    }

    public ApiAddressInfo fetchAddressInfo(String address) throws IOException, ApiSyncException {
        final String url = buildUrl("/ext/getaddress/" + address);
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                return parseAddressResponse(address, body);
            }
            if (response.code() == 404) {
                log.info("FAST-BOOT-API: /ext/getaddress not available, trying /api/addr fallback.");
                return fetchAddressInfoViaFallback(address);
            }
            log.warn("FAST-BOOT-API: Address fetch failed for {} (HTTP {}). body preview={}", address,
                    response.code(), preview(body));
            throw new ApiSyncException("HTTP " + response.code() + " while fetching address " + address);
        } catch (JSONException e) {
            log.error("FAST-BOOT-API: Invalid JSON while parsing address {}: {}", address, previewFromException(e));
            throw new ApiSyncException("Invalid JSON for address " + address, e);
        }
    }

    public ApiTxDetail fetchTransactionDetail(String txId) throws IOException, ApiSyncException {
        final String url = buildUrl("/api/getrawtransaction?txid=" + txId + "&decrypt=1");
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        String body = "";
        try (Response response = client.newCall(request).execute()) {
            body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("FAST-BOOT-API: tx fetch failed for {} (HTTP {}). body preview={}", txId, response.code(),
                        preview(body));
                throw new ApiSyncException("HTTP " + response.code() + " while fetching tx " + txId);
            }
            return parseTxDetailResponse(txId, body);
        } catch (JSONException e) {
            log.warn("FAST-BOOT-API: JSON parse failed for {} ({}). Attempting hex-only fallback.", txId,
                    previewFromException(e));
            String hex = fetchRawTransactionHex(txId);
            return new ApiTxDetail(txId, hex, -1, 0L, null);
        }
    }

    ApiAddressInfo parseAddressResponse(String requestedAddress, String body) throws JSONException {
        JSONObject obj = new JSONObject(body);
        String resolvedAddress = firstNonEmpty(obj.optString("addrStr", null), obj.optString("address", null),
                requestedAddress);
        ApiAddressInfo info = new ApiAddressInfo(resolvedAddress);
        info.balance = parseBalance(obj);

        Map<String, ApiTxRef> refs = new LinkedHashMap<>();
        JSONArray transactions = obj.optJSONArray("transactions");
        if (transactions == null) {
            transactions = obj.optJSONArray("txs");
        }
        collectTxRefs(transactions, refs);
        collectTxRefs(obj.optJSONArray("last_txs"), refs);
        for (ApiTxRef ref : refs.values()) {
            info.addOrMerge(ref);
        }
        int declaredTxCount = obj.optInt("txApperances", obj.optInt("tx_appearances", 0));
        info.txCount = declaredTxCount > 0 ? declaredTxCount : info.getTransactions().size();
        return info;
    }

    private ApiAddressInfo fetchAddressInfoViaFallback(String address) throws IOException, ApiSyncException {
        final String url = buildUrl("/api/addr/" + address);
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ApiSyncException("HTTP " + response.code() + " while fetching address " + address);
            }
            return parseAddressResponse(address, body);
        } catch (JSONException e) {
            log.error("FAST-BOOT-API: Invalid fallback JSON for address {}: {}", address, previewFromException(e));
            throw new ApiSyncException("Invalid JSON for address " + address, e);
        }
    }

    ApiTxDetail parseTxDetailResponse(String txId, String body) throws JSONException, ApiSyncException {
        String trimmed = body != null ? body.trim() : "";
        if (trimmed.isEmpty()) {
            throw new ApiSyncException("Empty transaction response for " + txId);
        }
        if (!trimmed.startsWith("{")) {
            return new ApiTxDetail(txId, trimmed, -1, 0L, null);
        }
        JSONObject obj = new JSONObject(trimmed);
        String hex = firstNonEmpty(obj.optString("hex", null), obj.optString("rawtx", null),
                obj.optString("raw", null), obj.optString("rawtransaction", null));
        if (hex == null || hex.isEmpty()) {
            throw new ApiSyncException("Missing tx hex for " + txId);
        }
        int height = parseHeight(obj);
        long time = parseTime(obj);
        String blockHash = obj.optString("blockhash", null);
        if ((height <= 0 || time <= 0) && blockHash != null && headerClient != null) {
            try {
                HeaderDto header = headerClient.fetchHeaderByHash(blockHash);
                if (header != null) {
                    if (height <= 0 && header.height > 0) {
                        height = (int) header.height;
                    }
                    if (time <= 0 && header.time > 0) {
                        time = header.time;
                    }
                }
            } catch (Exception e) {
                log.warn("FAST-BOOT-API: Unable to resolve header {} for tx {}: {}", blockHash, txId, e.toString());
            }
        }
        return new ApiTxDetail(txId, hex, height, time, blockHash);
    }

    private void collectTxRefs(@Nullable JSONArray array, Map<String, ApiTxRef> dest) throws JSONException {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            Object entry = array.get(i);
            ApiTxRef ref = null;
            if (entry instanceof String) {
                ref = new ApiTxRef((String) entry);
            } else if (entry instanceof JSONObject) {
                ref = parseTxRef((JSONObject) entry);
            }
            if (ref != null && ref.txId != null) {
                ApiTxRef existing = dest.get(ref.txId);
                dest.put(ref.txId, existing != null ? existing.merge(ref) : ref);
            }
        }
    }

    private ApiTxRef parseTxRef(JSONObject obj) {
        String txId = firstNonEmpty(obj.optString("txid", null), obj.optString("txId", null),
                obj.optString("hash", null), obj.optString("tx_hash", null));
        if (txId == null || txId.isEmpty()) {
            return null;
        }
        int height = parseHeight(obj);
        long time = parseTime(obj);
        Coin value = parseValue(obj);
        Boolean incoming = null;
        String category = obj.optString("category", null);
        if (category != null) {
            incoming = !"send".equalsIgnoreCase(category);
        }
        return new ApiTxRef(txId, height, time, value, incoming);
    }

    private int parseHeight(JSONObject obj) {
        int height = obj.optInt("blockheight", -1);
        if (height <= 0) {
            height = obj.optInt("height", -1);
        }
        if (height <= 0) {
            height = obj.optInt("block_height", -1);
        }
        return height;
    }

    private long parseTime(JSONObject obj) {
        long time = obj.optLong("time", 0L);
        if (time <= 0) {
            time = obj.optLong("blocktime", 0L);
        }
        if (time <= 0) {
            time = obj.optLong("timestamp", 0L);
        }
        return time;
    }

    private Coin parseBalance(JSONObject obj) {
        if (obj.has("balanceSat")) {
            return Coin.valueOf(obj.optLong("balanceSat", 0));
        }
        String balanceStr = firstNonEmpty(obj.optString("balance", null), obj.optString("confirmed", null));
        if (balanceStr != null) {
            try {
                return Coin.parseCoin(balanceStr);
            } catch (Exception e) {
                log.warn("FAST-BOOT-API: Failed to parse balance '{}': {}", balanceStr, e.toString());
            }
        }
        return Coin.ZERO;
    }

    private Coin parseValue(JSONObject obj) {
        if (obj.has("valueSat")) {
            return Coin.valueOf(obj.optLong("valueSat", 0));
        }
        if (obj.has("satoshis")) {
            return Coin.valueOf(obj.optLong("satoshis", 0));
        }
        String valueStr = firstNonEmpty(obj.optString("value", null), obj.optString("amount", null));
        if (valueStr != null) {
            try {
                return Coin.parseCoin(valueStr);
            } catch (Exception e) {
                // Some explorers return integer satoshis as strings; handle manually.
                try {
                    BigDecimal satoshis = new BigDecimal(valueStr);
                    return Coin.valueOf(satoshis.longValue());
                } catch (Exception ignored) {
                    log.debug("FAST-BOOT-API: Unable to parse tx value '{}'", valueStr);
                }
            }
        }
        return null;
    }

    private String fetchRawTransactionHex(String txId) throws IOException, ApiSyncException {
        if (headerClient != null) {
            return headerClient.fetchTransactionHex(txId);
        }
        final String url = buildUrl("/api/getrawtransaction?txid=" + txId + "&decrypt=0");
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiSyncException("HTTP " + response.code() + " while fetching tx hex " + txId);
            }
            String body = response.body() != null ? response.body().string().trim() : "";
            if (body.isEmpty()) {
                throw new ApiSyncException("Empty tx hex for " + txId);
            }
            return body;
        }
    }

    private String buildUrl(String path) {
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

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 160 ? body.substring(0, 160) + "..." : body;
    }

    private String previewFromException(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
