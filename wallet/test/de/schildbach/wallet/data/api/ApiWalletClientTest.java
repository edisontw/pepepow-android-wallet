package de.schildbach.wallet.data.api;

import org.bitcoinj.core.Coin;
import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import de.schildbach.wallet.data.api.ApiSyncException;

public class ApiWalletClientTest {

    @Test
    public void parseAddressResponse_mergesTxRefs() throws Exception {
        String json = "{"
                + "\"addrStr\":\"yTestAddress\","
                + "\"balance\":\"1.2345\","
                + "\"txApperances\":2,"
                + "\"transactions\":[\"tx1\"],"
                + "\"last_txs\":[{\"hash\":\"tx2\",\"blockheight\":120,\"time\":1700000000,\"value\":\"0.10000000\"}]"
                + "}";

        ApiWalletClient client = new ApiWalletClient("https://example.com");
        ApiAddressInfo info = client.parseAddressResponse("fallbackAddress", json);

        assertEquals("yTestAddress", info.address);
        assertEquals(Coin.parseCoin("1.2345"), info.balance);
        assertEquals(2, info.txCount);
        assertEquals(2, info.getTransactions().size());
        assertEquals("tx1", info.getTransactions().get(0).txId);
        ApiTxRef second = info.getTransactions().get(1);
        assertEquals("tx2", second.txId);
        assertEquals(120, second.blockHeight);
        assertEquals(1700000000L, second.blockTimeSeconds);
        assertEquals(Coin.parseCoin("0.1"), second.value);
    }

    @Test
    public void parseTxDetailResponse_withJson() throws Exception {
        String json = "{"
                + "\"txid\":\"abc\","
                + "\"hex\":\"00ff\","
                + "\"blocktime\":1700001000,"
                + "\"blockheight\":321,"
                + "\"blockhash\":\"h1\""
                + "}";

        ApiWalletClient client = new ApiWalletClient("https://example.com");
        ApiTxDetail detail = client.parseTxDetailResponse("abc", json);

        assertEquals("abc", detail.txId);
        assertEquals("00ff", detail.rawHex);
        assertEquals(321, detail.blockHeight);
        assertEquals(1700001000L, detail.blockTimeSeconds);
        assertEquals("h1", detail.blockHash);
    }

    @Test
    public void parseTxDetailResponse_rawHexFallback() throws JSONException, ApiSyncException {
        ApiWalletClient client = new ApiWalletClient("https://example.com");
        ApiTxDetail detail = client.parseTxDetailResponse("rawTx", "deadbeef");

        assertEquals("rawTx", detail.txId);
        assertEquals("deadbeef", detail.rawHex);
        assertEquals(-1, detail.blockHeight);
        assertEquals(0L, detail.blockTimeSeconds);
        assertNotNull(detail);
    }

}
