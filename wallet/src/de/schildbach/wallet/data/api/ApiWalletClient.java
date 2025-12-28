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
    private String sessionId = "UNKNOWN"; // Ensure sessionId is available and used in logs if needed

    public ApiWalletClient(String baseUrl) {
        this(baseUrl, null);
    }

    public void setSessionIdForLogs(String sessionId) {
        this.sessionId = sessionId;
        log.info("API_BASE_URL[sid={}] applied={}", this.sessionId, this.baseUrl);
    }

    public ApiWalletClient(String baseUrl, @Nullable ApiHeaderClient headerClient) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.headerClient = headerClient;
    }

    public String pushTransaction(String rawTxHex) throws IOException, ApiSyncException {
        final String url = buildUrl("/api/tx/send");
        log.info("FAST-BOOT-API: POST {} (hex length={})", url, rawTxHex.length());

        JSONObject json = new JSONObject();
        try {
            json.put("rawtx", rawTxHex);
        } catch (JSONException e) {
            throw new ApiSyncException("Failed to build JSON for transaction broadcast", e);
        }

        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json; charset=utf-8"), json.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful()) {
                log.info("FAST-BOOT-API: Transaction broadcast success: {}", responseBody);
                try {
                    JSONObject obj = new JSONObject(responseBody);
                    return firstNonEmpty(obj.optString("txid", null), obj.optString("txId", null), responseBody);
                } catch (JSONException e) {
                    return responseBody; // Return raw body if not JSON
                }
            } else {
                log.warn("FAST-BOOT-API: Transaction broadcast failed (HTTP {}). Body: {}", response.code(),
                        responseBody);
                throw new ApiSyncException("HTTP " + response.code() + " during broadcast: " + responseBody);
            }
        }
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
        // Default legacy behavior or inferred from usage?
        // For task compliance, we should control this from the caller (Runner).
        // For now, we keep this signature for existing callers (if any) and delegate.
        return fetchTransactionDetail(txId, true);
    }

    public ApiTxDetail fetchTransactionDetail(String txId, boolean useDecrypt1) throws IOException, ApiSyncException {
        if (!useDecrypt1) {
            return fetchTransactionDetailFallback(txId);
        }

        final String url = buildUrl("/api/getrawtransaction?txid=" + txId + "&decrypt=1");
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        String body = "";
        try (Response response = client.newCall(request).execute()) {
            body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                // Try fallback if 400/404?
                // No, strict boolean control.
                throw new ApiSyncException("HTTP " + response.code() + " while fetching tx " + txId);
            }
            return parseTxDetailResponse(txId, body);
        } catch (JSONException e) {
            throw new ApiSyncException("Invalid JSON for tx " + txId, e);
        }
    }

    private ApiTxDetail fetchTransactionDetailFallback(String txId) throws IOException, ApiSyncException {
        final String url = buildUrl("/ext/gettx/" + txId);
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ApiSyncException("HTTP " + response.code() + " while fetching tx " + txId);
            }
            // Parse Iquidus-style /ext/gettx/ response
            return parseTxDetailFallback(txId, body);
        } catch (JSONException e) {
            throw new ApiSyncException("Invalid JSON for tx " + txId, e);
        }
    }

    private ApiTxDetail parseTxDetailFallback(String txId, String body) throws JSONException {
        JSONObject obj = new JSONObject(body);
        // Iquidus /ext/gettx/ usually returns { hash, timestamp, ... outputs: [...] }
        // It might NOT contain the raw hex.
        // If we strictly need VIN/VOUT scripts, we might need raw hex.
        // Task 3 says: "Fetch /ext/gettx/<txid> ... parse vout[n].addresses[]"
        // So we don't necessarily need raw hex if we just trust the API's address
        // parsing.
        // However, ApiTxDetail structure seems to expect rawHex?
        // Let's check ApiTxDetail constructor.
        // (String txId, String rawHex, int blockHeight, long blockTimeSeconds, String
        // blockHash)
        // If rawHex is missing, validation might fail?
        // We can put empty hex if we trust the parsed data?
        String hex = obj.optString("hex", ""); // Some variants have it.
        int height = obj.optInt("blockheight", obj.optInt("height", -1));
        long time = obj.optLong("timestamp", 0);
        String blockHash = obj.optString("blockhash", null);

        // We also need VIN/VOUT data for reconstruction?
        // ApiTxDetail seems to be a wrapper for the *Result* of the fetch.
        // The Runner will parse the *Response* or the *Detail*?
        // Runner Task 3B: "parse vout[n].addresses[] (as provided by ext)"
        // This means the parsing logic should be IN THE RUNNER?
        // OR ApiTxDetail should hold the parsed JSON?
        // ApiTxDetail seems to hold metadata + hex.
        // If we want to support "address parsing", we might need the JSON object.
        // Modification: Return a structure that holds the JSON or parse it here.
        // But `ApiTxDetail` is an existing class.
        // Let's look at `ApiTxDetail`.
        // I'll return the object with what we have. If hex is missing, we might need to
        // fetch it separately OR
        // the Runner needs to handle "No Hex".
        return new ApiTxDetail(txId, hex, height, time, blockHash, obj); // Need to extend ApiTxDetail?
    }

    public boolean probeRawTxDecrypt() {
        try {
            // 1. Get a recent transaction ID from a known active address or just check a
            // genesis/early block tx if possible?
            // Since we don't have a known txid handy without querying, we might need to
            // query a block or an address.
            // Let's try to fetch a random transaction from a recent block if possible, or
            // just use a hardcoded one if we knew one.
            // Better: rely on the first address we scan in the runner to do the probe, OR
            // just try a known genesis txid?
            // PEPEPOW genesis txid: ...
            // Safest: The runner will call this. We just expose the method to probe a
            // specific TXID.
            return false; // usage: probeRawTxDecrypt(txid)
        } catch (Exception e) {
            return false;
        }
    }

    public boolean probeRawTxDecrypt(String sampleTxid) {
        final String url = buildUrl("/api/getrawtransaction?txid=" + sampleTxid + "&decrypt=1");
        log.info("FAST-BOOT-API: PROBE GET {}", url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful())
                return false;
            String body = response.body() != null ? response.body().string() : "";
            JSONObject obj = new JSONObject(body);
            // Check for vin/vout presence
            return obj.has("vin") && obj.has("vout");
        } catch (Exception e) {
            log.warn("FAST-BOOT-API: Probe failed for {}: {}", sampleTxid, e.toString());
            return false;
        }
    }

    public java.util.List<ApiTxRef> fetchAddressTransactions(String address, int start, int limit)
            throws IOException, ApiSyncException {
        // /ext/getaddresstxs/address/start/len
        final String url = buildUrl("/ext/getaddresstxs/" + address + "/" + start + "/" + limit);
        log.info("FAST-BOOT-API: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    return java.util.Collections.emptyList();
                }
                throw new ApiSyncException("HTTP " + response.code() + " while fetching addrtxs " + address);
            }
            return parseAddressTransactions(body);
        } catch (JSONException e) {
            log.error("FAST-BOOT-API: Invalid JSON for addrtxs {}: {}", address, previewFromException(e));
            // Treat as transient failure -> throw so runner can retry or decide
            throw new ApiSyncException("Invalid JSON for addrtxs " + address, e);
        }
    }

    private java.util.List<ApiTxRef> parseAddressTransactions(String body) throws JSONException {
        java.util.List<ApiTxRef> refs = new java.util.ArrayList<>();
        String bodyType = "UNKNOWN";
        String sampleFirst = "NONE";

        try {
            Object json = new org.json.JSONTokener(body).nextValue();

            if (json instanceof JSONArray) {
                JSONArray arr = (JSONArray) json;
                if (arr.length() > 0) {
                    Object firstItem = arr.get(0);
                    if (firstItem instanceof String) {
                        // Schema: ["txid", ...]
                        bodyType = "JSONArray<String>";
                        for (int i = 0; i < arr.length(); i++) {
                            // No timestamp in string only
                            refs.add(new ApiTxRef(arr.getString(i)));
                        }
                    } else if (firstItem instanceof JSONObject) {
                        // Schema: [{"timestamp":..., "txid":"..."}, ...]
                        bodyType = "JSONArray<Object>";
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject tx = arr.getJSONObject(i);
                            String txid = firstNonEmpty(tx.optString("txid", null), tx.optString("tx_hash", null));
                            if (txid != null) {
                                long ts = tx.optLong("timestamp", 0);
                                if (ts == 0)
                                    ts = tx.optLong("time", 0);
                                // A-2: Normalize timestamp immediately (stay as seconds in REF, consumer
                                // scales)
                                // Actually, let's normalize in the parser if needed?
                                // Users of ApiTxRef expect seconds usually?
                                // Task says: "Convert timestampSeconds -> txTimeMs = timestampSeconds * 1000L"
                                // IN RUNNER,
                                // but here we return ApiTxRef. ApiTxRef usually stores seconds?
                                // Let's keep it as seconds here for consistency with existing constructor.
                                refs.add(new ApiTxRef(txid, -1, ts, null, null));
                            }
                        }
                    } else {
                        bodyType = "JSONArray<Mixed>"; // Fallback
                    }
                } else {
                    bodyType = "JSONArray<Empty>";
                }
            } else if (json instanceof JSONObject) {
                bodyType = "JSONObject";
                JSONObject obj = (JSONObject) json;
                // Try known wrapper fields
                JSONArray data = obj.optJSONArray("data");
                if (data == null)
                    data = obj.optJSONArray("txs");
                if (data == null)
                    data = obj.optJSONArray("items");
                if (data == null)
                    data = obj.optJSONArray("result");
                if (data == null)
                    data = obj.optJSONArray("transactions");

                if (data != null) {
                    bodyType = "JSONObject_Wrapper";
                    for (int i = 0; i < data.length(); i++) {
                        Object item = data.get(i);
                        if (item instanceof String) {
                            refs.add(new ApiTxRef((String) item));
                        } else if (item instanceof JSONObject) {
                            JSONObject tx = (JSONObject) item;
                            String txid = firstNonEmpty(tx.optString("txid", null), tx.optString("tx_hash", null));
                            if (txid != null) {
                                long ts = tx.optLong("timestamp", 0);
                                if (ts == 0)
                                    ts = tx.optLong("time", 0);
                                refs.add(new ApiTxRef(txid, -1, ts, null, null));
                            }
                        }
                    }
                } else {
                    log.warn("FAST-BOOT-API: JSON Object with no known wrapper. Keys: {}", obj.names());
                }
            } else {
                log.warn("FAST-BOOT-API: Unknown JSON root type: {}", json.getClass().getName());
            }

            if (!refs.isEmpty()) {
                sampleFirst = refs.get(0).txId;
                if (sampleFirst.length() > 20)
                    sampleFirst = sampleFirst.substring(0, 20) + "...";
                if (refs.size() > 1) {
                    String second = refs.get(1).txId;
                    if (second.length() > 20)
                        second = second.substring(0, 20) + "...";
                    sampleFirst += ", " + second;
                }
            }

            // A-1: Add debug log
            log.info("FAST-BOOT-API[sid={}] Parsed addrtxs: type={} count={} sample=[{}]",
                    sessionId, bodyType, refs.size(), sampleFirst);

        } catch (Exception e) {
            // Requirement A: Never throw, log fully
            log.warn("FAST-BOOT-API: Parse failure for addrtxs. Type={} Msg={} BodyPrefix={}",
                    e.getClass().getSimpleName(), e.getMessage(), preview(body));
            // Requirement: "treat as transient failure"
            // We throw JSONException so the runner sees it as a failure (it catches
            // Exception).
            throw new JSONException("Schema unknown or parse failed: " + e.getMessage());
        }
        return refs;
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
        return new ApiTxDetail(txId, hex, height, time, blockHash, obj);
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
