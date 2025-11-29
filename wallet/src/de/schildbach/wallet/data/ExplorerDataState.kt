package de.schildbach.wallet.data

import android.os.SystemClock
import org.bitcoinj.core.Coin

data class ExplorerDataState(
    val address: String,
    val explorerHeight: Long?,
    val balance: Coin?,
    val timestamp: Long = SystemClock.elapsedRealtime(),
    val errorMessage: String? = null
) {
    fun isSuccessful(): Boolean = explorerHeight != null && balance != null && errorMessage == null

    fun isFresh(): Boolean = SystemClock.elapsedRealtime() - timestamp < FRESHNESS_WINDOW_MS

    companion object {
        private const val FRESHNESS_WINDOW_MS = 30_000L
    }
}
