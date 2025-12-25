package de.schildbach.wallet.service;

import de.schildbach.wallet.data.api.ApiSessionWallet;
import de.schildbach.wallet.data.api.UtxoSnapshotRunner;
import de.schildbach.wallet.service.BlockchainService.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decouples the decision logic for which data source the UI should use.
 */
public class DataSourceRouter {
    private static final Logger log = LoggerFactory.getLogger(DataSourceRouter.class);
    private final String sessionId;

    // Sticky flag: once session was ever usable in this process, don't downgrade
    // during refresh
    private static boolean hasUsableSessionSnapshot = false;

    public DataSourceRouter(String sessionId) {
        this.sessionId = sessionId;
    }

    public DataSource determineDataSource(ApiSessionWallet sessionWallet,
            UtxoSnapshotRunner snapshotRunner,
            DataSource currentSource) {
        DataSource nextSource = DataSource.SPV_CANONICAL;

        UtxoSnapshotRunner.SnapshotState snapshotState = (snapshotRunner != null) ? snapshotRunner.getState()
                : UtxoSnapshotRunner.SnapshotState.IDLE;

        // RULE: Use API_SESSION when sessionWallet is ready AND snapshot is READY.
        //
        // SIMPLIFIED LOGIC: snapshotState==READY means the API snapshot completed
        // successfully. This is the authoritative signal that we have valid API data.
        // - If READY with UTXOs > 0: show them
        // - If READY with UTXOs == 0: that's "confirmed empty" from API view
        //
        // Either way, READY is the definitive state. Do NOT require additional checks
        // that would cause premature fallback to SPV_CANONICAL during
        // unlock/transitions.

        boolean sessionReady = (sessionWallet != null && sessionWallet.isReady());
        boolean snapshotReady = (snapshotState == UtxoSnapshotRunner.SnapshotState.READY);
        boolean hasFunds = (sessionWallet != null && sessionWallet.getBalance().signum() > 0);

        // Decision: snapshotReady && sessionReady -> API_SESSION
        // Removed overly strict (hasFunds || (checkedAll && emptyIsFinal)) requirement
        boolean switchToApi = sessionReady && snapshotReady;

        // Update sticky flag when we reach a usable state
        if (switchToApi) {
            hasUsableSessionSnapshot = true;
        }

        // STICKINESS RULE: If we are already on API_SESSION, and session is still ready
        // and has funds, DO NOT downgrade just because snapshotState became RUNNING
        // (re-check).
        // This prevents UI flicker during auto-refresh.
        boolean keepApi = (currentSource == DataSource.API_SESSION) && sessionReady && hasFunds;

        // ENHANCED STICKINESS: Keep API_SESSION during RUNNING refresh if we ever had
        // usable data
        // This prevents history from disappearing during READY -> RUNNING -> READY
        // transitions
        boolean stickyDuringRefresh = hasUsableSessionSnapshot && sessionReady
                && (snapshotState == UtxoSnapshotRunner.SnapshotState.RUNNING);

        if (switchToApi || keepApi || stickyDuringRefresh) {
            nextSource = DataSource.API_SESSION;
        }

        if (nextSource != currentSource) {
            log.info(
                    "UI-SRC[sid={}] {} -> {} reason=sessionReady={} snapshotState={} hasFunds={} hasUsableSession={}",
                    sessionId, currentSource, nextSource, sessionReady, snapshotState, hasFunds,
                    hasUsableSessionSnapshot);
        }

        return nextSource;
    }
}
