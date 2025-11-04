package org.bitcoinj.core;

/**
 * Minimal PEPEPOW-specific coin definition used by the Android wallet.
 * This keeps the legacy package so existing imports in the UI layer continue to work.
 */
public final class CoinDefinition {
    private CoinDefinition() {
    }

    public static final String coinName = "PEPEPOW";
    public static final String coinTicker = "PEPEW";
    public static final String coinURIScheme = "pepepow";

    public static final String PATTERN_PRIVATE_KEY_START_UNCOMPRESSED = "[7]";
    public static final String PATTERN_PRIVATE_KEY_START_COMPRESSED = "[Xx]";

    public static final String BLOCKEXPLORER_BASE_URL_PROD = "https://explorer.pepepow.org/";
    public static final String BLOCKEXPLORER_BASE_URL_TEST = "https://testnet.explorer.pepepow.org/";
    public static final String BLOCKEXPLORER_ADDRESS_PATH = "address/";
    public static final String BLOCKEXPLORER_TRANSACTION_PATH = "tx/";
    public static final String BLOCKEXPLORER_BLOCK_PATH = "block/";

    public static final String UNSPENT_API_URL = "https://explorer.pepepow.org/api/";
}
