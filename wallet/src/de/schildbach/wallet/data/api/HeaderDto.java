package de.schildbach.wallet.data.api;

import com.squareup.moshi.Json;

public class HeaderDto {
    @Json(name = "hash")
    public String hash;

    @Json(name = "previousblockhash")
    public String previousBlockHash;

    @Json(name = "merkleroot")
    public String merkleRoot;

    @Json(name = "time")
    public long time;

    @Json(name = "bits")
    public String bits;

    @Json(name = "nonce")
    public long nonce;

    @Json(name = "height")
    public long height;

    @Json(name = "version")
    public long version;

    @Json(name = "chainwork")
    public String chainWork; // Optional

    public org.bitcoinj.core.Block toBlock(org.bitcoinj.core.NetworkParameters params) {
        long parsedBits = parseBits(bits);
        String prev = previousBlockHash != null ? previousBlockHash : org.bitcoinj.core.Sha256Hash.ZERO_HASH.toString();
        org.bitcoinj.core.Block block = new org.bitcoinj.core.Block(params, version,
                org.bitcoinj.core.Sha256Hash.wrap(prev),
                org.bitcoinj.core.Sha256Hash.wrap(merkleRoot),
                time, parsedBits, nonce, java.util.Collections.emptyList());
        return block;
    }

    public java.math.BigInteger toChainWork(org.bitcoinj.core.NetworkParameters params,
            org.bitcoinj.core.StoredBlock prevStored) {
        if (chainWork != null && !chainWork.isEmpty()) {
            return new java.math.BigInteger(chainWork, 16);
        }
        org.bitcoinj.core.Block block = toBlock(params);
        if (prevStored != null) {
            return prevStored.getChainWork().add(block.getWork());
        }
        return block.getWork();
    }

    private long parseBits(String bitsValue) {
        if (bitsValue == null) {
            return 0L;
        }
        String normalized = bitsValue.startsWith("0x") ? bitsValue.substring(2) : bitsValue;
        try {
            return Long.parseLong(normalized, 16);
        } catch (NumberFormatException e) {
            return Long.parseLong(normalized);
        }
    }
}
