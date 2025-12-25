package de.schildbach.wallet.data.api;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.data.api.BlockDto;

public class ApiHeaderClient {
    private static final Logger log = LoggerFactory.getLogger(ApiHeaderClient.class);

    // Process-lifetime detection: some explorer APIs do not return the PoW block hash in getblock()
    private static final AtomicBoolean getBlockMissingPowHashLogged = new AtomicBoolean(false);
    private static volatile boolean getBlockMissingPowHashDetected = false;

    private final OkHttpClient client;
    private final Moshi moshi;
    private final JsonAdapter<Object> anyJsonAdapter;
    private final String baseUrl;
    private volatile String sessionIdForLogs = "--------";

    public ApiHeaderClient(String baseUrl) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.moshi = new Moshi.Builder().build();
        this.anyJsonAdapter = moshi.adapter(Object.class);
    }

    /**
     * FASTBOOT session id is assigned by the overlay bootstrapper (process-lifetime).
     * This is only used for logging and does not affect network behavior.
     */
    public void setSessionIdForLogs(String sessionId) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            this.sessionIdForLogs = sessionId.trim();
        }
    }

    /**
     * True once per process if we detect that getblock() responses do not expose the
     * PoW block hash for the requested getblockhash() hash.
     */
    public boolean isGetBlockMissingPowHashDetected() {
        return getBlockMissingPowHashDetected;
    }

    public long fetchBlockCount() throws IOException, ApiSyncException {
        String url = buildUrl("/api/getblockcount");
        log.info("API Request: GET {}", url);
        Request request = new Request.Builder().url(url).build();
        // Use a shorter timeout for this specific call to avoid blocking for 30s
        OkHttpClient shortTimeoutClient = client.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        try (Response response = shortTimeoutClient.newCall(request).execute()) {
            ensureSuccess(response, url);
            String raw = response.body() != null ? response.body().string() : "";
            log.info("API Response ({}): {}", response.code(), raw.trim());
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new ApiSyncException("API returned invalid block count", e);
            }
        }
    }

    public String fetchBlockHash(long height) throws IOException, ApiSyncException {
        return fetchBlockHashInternal(height, false);
    }

    public String fetchBlockHashWithCacheBust(long height) throws IOException, ApiSyncException {
        return fetchBlockHashInternal(height, true);
    }

    private String fetchBlockHashInternal(long height, boolean cacheBust) throws IOException, ApiSyncException {
        String url = buildUrl("/api/getblockhash?index=" + height);
        if (cacheBust) {
            url += "&t=" + System.currentTimeMillis();
        }
        log.info("API Request: GET {}", url);
        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (cacheBust) {
            requestBuilder.header("Cache-Control", "no-cache");
        }
        Request request = requestBuilder.build();

        // Use a reasonable timeout for block hash fetch
        OkHttpClient hashClient = client.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

        try (Response response = hashClient.newCall(request).execute()) {
            ensureSuccess(response, url);
            String raw = response.body() != null ? response.body().string() : "";
            String hash = raw.trim();
            log.info("API Response ({}): hash={}", response.code(), hash);
            if (hash.isEmpty()) {
                throw new ApiSyncException("API returned empty block hash");
            }
            return hash;
        }
    }

    public String getBlockHashByHeight(long height) throws IOException, ApiSyncException {
        return fetchBlockHash(height);
    }

    public HeaderDto fetchHeaderAtHeight(long height) throws IOException, ApiSyncException {
        String hash = fetchBlockHash(height);
        HeaderDto header = fetchHeaderByHash(hash);
        if (header.height == 0) {
            header.height = height;
        }
        // Treat getblockhash(height) as canonical PoW hash for overlay logic.
        // Some APIs return non-PoW identifiers in getblock().
        header.hash = hash;
        return header;
    }

    public HeaderDto fetchHeaderByHash(String hash) throws IOException, ApiSyncException {
        String url = buildUrl("/api/getblock?hash=" + hash);
        log.info("API Request: GET {}", url);
        Request request = new Request.Builder().url(url).build();

        // Use a reasonable timeout for header fetch
        OkHttpClient headerClient = client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        try (Response response = headerClient.newCall(request).execute()) {
            ensureSuccess(response, url);
            String raw = response.body() != null ? response.body().string() : "";
            // Log concise info instead of full JSON
            log.info("API Response ({}): size={} bytes", response.code(), raw.length());

            HeaderDto header = moshi.adapter(HeaderDto.class).fromJson(raw);
            Object root = parseJsonTree(raw);
            if (root != null) {
                detectGetBlockMissingPowHash(hash, root);
                if (header == null) {
                    header = new HeaderDto();
                }
                populateHeaderDtoFromTreeIfMissing(header, root);
            }

            if (header == null || header.merkleRoot == null) {
                throw new ApiSyncException("API returned invalid JSON (missing merkleroot)");
            }
            if (header.hash == null) {
                header.hash = hash;
            }
            return header;
        } catch (com.squareup.moshi.JsonDataException e) {
            throw new ApiSyncException("API returned invalid JSON", e);
        }
    }

    public BlockDto fetchBlockAtHeight(long height) throws IOException, ApiSyncException {
        String hash = fetchBlockHash(height);
        BlockDto block = fetchBlockByHash(hash);
        if (block != null && block.height == 0) {
            block.height = height;
        }
        return block;
    }

    public BlockDto fetchBlockByHash(String hash) throws IOException, ApiSyncException {
        return fetchBlockByHashInternal(hash, false);
    }

    /**
     * Fetch block by hash with cache-bust to bypass CDN/proxy cache.
     * Used for retry requests in tolerant PoW verification.
     */
    public BlockDto fetchBlockByHashWithCacheBust(String hash) throws IOException, ApiSyncException {
        return fetchBlockByHashInternal(hash, true);
    }

    private BlockDto fetchBlockByHashInternal(String hash, boolean cacheBust) throws IOException, ApiSyncException {
        String url = buildUrl("/api/getblock?hash=" + hash);
        if (cacheBust) {
            url += "&t=" + System.currentTimeMillis();
        }
        log.info("API Request: GET {} cacheBust={}", url, cacheBust);

        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (cacheBust) {
            requestBuilder.header("Cache-Control", "no-cache");
        }
        Request request = requestBuilder.build();

        OkHttpClient blockClient = client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        try (Response response = blockClient.newCall(request).execute()) {
            ensureSuccess(response, url);
            String raw = response.body() != null ? response.body().string() : "";
            log.info("API Response ({}): size={} bytes", response.code(), raw.length());

            BlockDto block = moshi.adapter(BlockDto.class).fromJson(raw);
            Object root = parseJsonTree(raw);
            if (root != null) {
                detectGetBlockMissingPowHash(hash, root);
                if (block == null) {
                    block = new BlockDto();
                }
                populateBlockDtoFromTreeIfMissing(block, root);
            }

            if (block == null || block.merkleRoot == null) {
                throw new ApiSyncException("API returned invalid block JSON (missing merkleroot)");
            }
            if (block.hash == null) {
                block.hash = hash;
            }
            return block;
        } catch (com.squareup.moshi.JsonDataException e) {
            throw new ApiSyncException("API returned invalid JSON", e);
        }
    }

    public HeaderResponse fetchRecentHeaders(int count) throws IOException, ApiSyncException {
        long tipHeight = fetchBlockCount();
        long startHeight = Math.max(1, tipHeight - count + 1);
        List<HeaderDto> headers = new ArrayList<>();
        for (long h = startHeight; h <= tipHeight; h++) {
            headers.add(fetchHeaderAtHeight(h));
            if ((h - startHeight) % 100 == 0) {
                log.info("Fetched header {}/{} (height {})", (h - startHeight), (tipHeight - startHeight), h);
            }
        }
        HeaderResponse response = new HeaderResponse();
        response.tipHeight = tipHeight;
        response.headers = headers;
        log.info("Received {} headers. Tip height: {}", headers.size(), tipHeight);
        return response;
    }

    public List<UtxoDto> fetchUtxos(List<String> addresses) {
        log.warn("Explorer API does not expose direct UTXO set. Skipping UTXO fetch for {} addresses.",
                addresses.size());
        return Collections.emptyList();
    }

    public String fetchTransactionHex(String txid) throws IOException, ApiSyncException {
        String url = buildUrl("/api/getrawtransaction?txid=" + txid + "&decrypt=0");
        log.info("API Request: GET {}", url);

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            ensureSuccess(response, url);
            String hex = response.body() != null ? response.body().string().trim() : "";
            if (hex.isEmpty()) {
                throw new ApiSyncException("API returned empty transaction body");
            }
            log.debug("Fetched transaction hex for {}: {} bytes", txid, hex.length());
            return hex;
        } catch (java.net.SocketTimeoutException e) {
            log.error("Transaction fetch timeout for {}: {}", txid, e.getMessage());
            throw new IOException("Network timeout while fetching transaction", e);
        } catch (java.net.UnknownHostException e) {
            log.error("Transaction fetch unknown host for {}: {}", txid, e.getMessage());
            throw new IOException("Network unreachable: Unknown Host", e);
        }
    }

    private void ensureSuccess(Response response, String url) throws IOException {
        if (!response.isSuccessful()) {
            log.error("API Error: {} for URL {}", response.code(), url);
            throw new IOException("Unexpected API response: " + response.code() + " " + response.message());
        }
    }

    private Object parseJsonTree(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return anyJsonAdapter.fromJson(raw);
        } catch (Exception e) {
            // Keep typed parsing errors separate; tree parsing is best-effort for schema detection.
            return null;
        }
    }

    private void detectGetBlockMissingPowHash(String expectedPowHash, Object root) {
        if (getBlockMissingPowHashDetected) {
            return;
        }

        final String normExpected = normalizeHexHash(expectedPowHash);
        if (normExpected == null) {
            return;
        }

        // Task A: Explicitly detect that none of these fields equal getblockhash(height)
        final String[] candidatePaths = new String[]{
                "blockhash",
                "result.blockhash",
                "hash",
                "result.hash",
                "header.hash",
        };

        boolean anyMatches = false;
        for (String path : candidatePaths) {
            String candidate = normalizeHexHash(extractString(root, path));
            if (candidate == null) {
                continue;
            }
            if (normExpected.equals(candidate)) {
                anyMatches = true;
                break;
            }
        }

        if (!anyMatches) {
            getBlockMissingPowHashDetected = true;
            if (getBlockMissingPowHashLogged.compareAndSet(false, true)) {
                log.warn("FASTBOOT[sid={}] API-LIMITATION getblock_missing_pow_hash=true", sessionIdForLogs);
            }
        }
    }

    private void populateHeaderDtoFromTreeIfMissing(HeaderDto header, Object root) {
        if (header.previousBlockHash == null) {
            header.previousBlockHash = firstNonEmptyString(root,
                    "previousblockhash", "result.previousblockhash", "header.previousblockhash",
                    "result.header.previousblockhash");
        }
        if (header.merkleRoot == null) {
            header.merkleRoot = firstNonEmptyString(root,
                    "merkleroot", "result.merkleroot", "header.merkleroot", "header.merkleRoot",
                    "result.header.merkleroot", "result.header.merkleRoot");
        }
        if (header.bits == null) {
            header.bits = firstNonEmptyString(root,
                    "bits", "result.bits", "header.bits", "result.header.bits");
        }
        if (header.time <= 0) {
            Long time = extractLong(root, "time", "result.time", "header.time", "result.header.time");
            if (time != null) {
                header.time = time;
            }
        }
        if (header.version <= 0) {
            Long version = extractLong(root, "version", "result.version", "header.version", "result.header.version");
            if (version != null) {
                header.version = version;
            }
        }
        if (header.nonce == 0) {
            Long nonce = extractLong(root, "nonce", "result.nonce", "header.nonce", "result.header.nonce");
            if (nonce != null) {
                header.nonce = nonce;
            }
        }
        if (header.height == 0) {
            Long height = extractLong(root, "height", "result.height", "header.height", "result.header.height");
            if (height != null) {
                header.height = height;
            }
        }
        if (header.chainWork == null) {
            header.chainWork = firstNonEmptyString(root, "chainwork", "result.chainwork", "header.chainwork",
                    "result.header.chainwork");
        }
    }

    private void populateBlockDtoFromTreeIfMissing(BlockDto block, Object root) {
        if (block.previousBlockHash == null) {
            block.previousBlockHash = firstNonEmptyString(root,
                    "previousblockhash", "result.previousblockhash", "header.previousblockhash",
                    "result.header.previousblockhash");
        }
        if (block.merkleRoot == null) {
            block.merkleRoot = firstNonEmptyString(root,
                    "merkleroot", "result.merkleroot", "header.merkleroot", "header.merkleRoot",
                    "result.header.merkleroot", "result.header.merkleRoot");
        }
        if (block.bits == null) {
            block.bits = firstNonEmptyString(root,
                    "bits", "result.bits", "header.bits", "result.header.bits");
        }
        if (block.time <= 0) {
            Long time = extractLong(root, "time", "result.time", "header.time", "result.header.time");
            if (time != null) {
                block.time = time;
            }
        }
        if (block.version <= 0) {
            Long version = extractLong(root, "version", "result.version", "header.version", "result.header.version");
            if (version != null) {
                block.version = version;
            }
        }
        if (block.nonce == 0) {
            Long nonce = extractLong(root, "nonce", "result.nonce", "header.nonce", "result.header.nonce");
            if (nonce != null) {
                block.nonce = nonce;
            }
        }
        if (block.height == 0) {
            Long height = extractLong(root, "height", "result.height", "header.height", "result.header.height");
            if (height != null) {
                block.height = height;
            }
        }
        if (block.hex == null) {
            block.hex = firstNonEmptyString(root, "hex", "result.hex", "header.hex", "result.header.hex");
        }
        // Do not override block.hash: in some APIs this is a non-PoW identifier; callers may still set it to the
        // requested hash for overlay usage separately.
    }

    private String firstNonEmptyString(Object root, String... paths) {
        for (String path : paths) {
            String value = extractString(root, path);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private Long extractLong(Object root, String... paths) {
        for (String path : paths) {
            Object value = extractValue(root, path);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value instanceof String) {
                String s = ((String) value).trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    if (s.startsWith("0x") || s.startsWith("0X")) {
                        return Long.parseLong(s.substring(2), 16);
                    }
                    return Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                    // keep searching
                }
            }
        }
        return null;
    }

    private String extractString(Object root, String dottedPath) {
        Object value = extractValue(root, dottedPath);
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    private Object extractValue(Object root, String dottedPath) {
        if (!(root instanceof Map) || dottedPath == null || dottedPath.isEmpty()) {
            return null;
        }
        Object current = root;
        String[] parts = dottedPath.split("\\.");
        for (String part : parts) {
            if (!(current instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            if (!map.containsKey(part)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private String normalizeHexHash(String hash) {
        if (hash == null) {
            return null;
        }
        String normalized = hash.toLowerCase().trim();
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() != 64 || !normalized.matches("[0-9a-f]+")) {
            return null;
        }
        return normalized;
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
}
