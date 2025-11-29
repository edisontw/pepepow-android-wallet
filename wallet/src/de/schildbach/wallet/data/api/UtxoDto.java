package de.schildbach.wallet.data.api;

import com.squareup.moshi.Json;

public class UtxoDto {
    @Json(name = "txid")
    public String txId;

    @Json(name = "vout")
    public int outputIndex;

    @Json(name = "value")
    public long value; // in satoshis

    @Json(name = "script_hex")
    public String scriptHex;

    @Json(name = "height")
    public int height;
}
