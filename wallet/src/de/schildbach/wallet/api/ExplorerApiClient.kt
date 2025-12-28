package de.schildbach.wallet.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.bitcoinj.core.Coin
import de.schildbach.wallet.util.ExplorerConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

class ExplorerApiClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getBaseUrl(): String {
        return ExplorerConfig.getExplorerBaseUrl()
    }

    fun fetchBlockCount(): Long? {
        return try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/api/getblockcount")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.trim()?.toLongOrNull()
            }
        } catch (_: IOException) {
            null
        }
    }

    fun fetchBlockHash(height: Long): String? {
        return try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/api/getblockhash?index=$height")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.trim()
            }
        } catch (_: IOException) {
            null
        }
    }

    fun fetchBalance(address: String): Coin? {
        return try {
            val request = Request.Builder()
                .url("${getBaseUrl()}/ext/getbalance/$address")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim() ?: return null
                Coin.parseCoin(body)
            }
        } catch (_: Exception) {
            null
        }
    }
}
