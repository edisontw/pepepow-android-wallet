package org.dash.wallet.common.data;

/**
 * Status of the explorer wallet snapshot fetch in FAST_API_10POW mode.
 */
public enum WalletSnapshotStatus {
    NOT_REQUESTED,
    SUCCESS,
    /**
     * Snapshot was requested but returned empty or API endpoint was missing (404).
     * This is considered an acceptable state for FAST_API_10POW (starts with empty
     * history).
     */
    EMPTY_OK,
    /**
     * A real error occurred (network error, parsing error) that requires user
     * attention.
     */
    FAILED,
    /**
     * Snapshot stopped due to time/address budget but can be resumed from cursor.
     */
    INCOMPLETE_RESUMABLE

}
