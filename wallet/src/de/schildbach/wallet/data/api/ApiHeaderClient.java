package de.schildbach.wallet.data.api;

import com.squareup.moshi.Moshi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.data.api.BlockDto;

public class ApiHeaderClient {
    private static final Logger log = LoggerFactory.getLogger(ApiHeaderClient.class);
    private final OkHttpClient client;
    private final Moshi moshi;
    private final String baseUrl;

    public ApiHeaderClient(String baseUrl) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.moshi = new Moshi.Builder().build();
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
        String url = buildUrl("/api/getblockhash?index=" + height);
        log.info("API Request: GET {}", url);
        Request request = new Request.Builder().url(url).build();

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
            if (header == null || header.merkleRoot == null) {
                throw new ApiSyncException("API returned invalid JSON");
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
        String url = buildUrl("/api/getblock?hash=" + hash);
        log.info("API Request: GET {}", url);
        Request request = new Request.Builder().url(url).build();

        OkHttpClient blockClient = client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        try (Response response = blockClient.newCall(request).execute()) {
            ensureSuccess(response, url);
            String raw = response.body() != null ? response.body().string() : "";
            log.info("API Response ({}): size={} bytes", response.code(), raw.length());

            BlockDto block = moshi.adapter(BlockDto.class).fromJson(raw);
            if (block == null || block.merkleRoot == null) {
                throw new ApiSyncException("API returned invalid block JSON");
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
