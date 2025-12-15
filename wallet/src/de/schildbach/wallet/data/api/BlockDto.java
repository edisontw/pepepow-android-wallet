package de.schildbach.wallet.data.api;

import com.squareup.moshi.Json;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal block DTO that carries either full hex or header fields.
 * Falls back to header-only construction if raw hex is missing.
 */
public class BlockDto {
    private static final Logger log = LoggerFactory.getLogger(BlockDto.class);

    @Json(name = "hash")
    public String hash;

    @Json(name = "previousblockhash")
    public String previousBlockHash;

    @Json(name = "merkleroot")
    public String merkleRoot;

    @Json(name = "bits")
    public String bits;

    @Json(name = "nonce")
    public long nonce;

    @Json(name = "height")
    public long height;

    @Json(name = "version")
    public long version;

    @Json(name = "time")
    public long time;

    @Json(name = "hex")
    public String hex;

    public HeaderDto toHeaderDto() {
        HeaderDto dto = new HeaderDto();
        dto.hash = hash;
        dto.previousBlockHash = previousBlockHash;
        dto.merkleRoot = merkleRoot;
        dto.bits = bits;
        dto.nonce = nonce;
        dto.height = height;
        dto.version = version;
        dto.time = time;
        return dto;
    }

    public Block toBlock(NetworkParameters params) {
        if (hex != null && !hex.isEmpty()) {
            try {
                return new Block(params, Utils.HEX.decode(hex));
            } catch (Exception e) {
                log.warn("Failed to parse raw block hex for {}. Falling back to header-only block.", hash, e);
            }
        }
        return toHeaderDto().toBlock(params);
    }
}
