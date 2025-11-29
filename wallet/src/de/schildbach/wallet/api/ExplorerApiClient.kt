package de.schildbach.wallet.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.bitcoinj.core.Coin
import java.io.IOException
import java.util.concurrent.TimeUnit

class ExplorerApiClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchBlockCount(): Long? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/api/getblockcount")
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
                .url("$BASE_URL/api/getblockhash?index=$height")
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
                .url("$BASE_URL/ext/getbalance/$address")
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

    companion object {
        private const val BASE_URL = "https://explorer.pepepow.net"
    }
}
