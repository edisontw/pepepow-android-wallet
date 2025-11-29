package de.schildbach.wallet.data.api;

public class ApiSyncException extends Exception {
    public ApiSyncException(String message) {
        super(message);
    }

    public ApiSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
