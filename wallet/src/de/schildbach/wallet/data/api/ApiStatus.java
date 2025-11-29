package de.schildbach.wallet.data.api;

public class ApiStatus {
    public enum State {
        HEALTHY, DEGRADED, OFFLINE
    }

    private final State state;
    private final long lastCheckedMillis;
    private final String lastErrorMessage;
    private final int lastHttpCode;
    private final int lastCheckpointHeight;
    private final String lastCheckpointHash;
    private final String baseUrl;

    public ApiStatus(State state, long lastCheckedMillis, String lastErrorMessage, int lastHttpCode,
            int lastCheckpointHeight, String lastCheckpointHash, String baseUrl) {
        this.state = state;
        this.lastCheckedMillis = lastCheckedMillis;
        this.lastErrorMessage = lastErrorMessage;
        this.lastHttpCode = lastHttpCode;
        this.lastCheckpointHeight = lastCheckpointHeight;
        this.lastCheckpointHash = lastCheckpointHash;
        this.baseUrl = baseUrl;
    }

    public State getState() {
        return state;
    }

    public long getLastCheckedMillis() {
        return lastCheckedMillis;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public int getLastHttpCode() {
        return lastHttpCode;
    }

    public int getLastCheckpointHeight() {
        return lastCheckpointHeight;
    }

    public String getLastCheckpointHash() {
        return lastCheckpointHash;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
