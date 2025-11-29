package de.schildbach.wallet.data.api;

public class NetworkStats {
    public final long explorerTipHeight;
    public final double difficulty;
    public final double networkHashrate;
    public final int masternodeCount;
    public final String priceUsd;
    public final int connectionCount;
    public final long lastUpdatedMillis;

    public NetworkStats(long explorerTipHeight, double difficulty, double networkHashrate, int masternodeCount,
            String priceUsd, int connectionCount, long lastUpdatedMillis) {
        this.explorerTipHeight = explorerTipHeight;
        this.difficulty = difficulty;
        this.networkHashrate = networkHashrate;
        this.masternodeCount = masternodeCount;
        this.priceUsd = priceUsd;
        this.connectionCount = connectionCount;
        this.lastUpdatedMillis = lastUpdatedMillis;
    }
}
