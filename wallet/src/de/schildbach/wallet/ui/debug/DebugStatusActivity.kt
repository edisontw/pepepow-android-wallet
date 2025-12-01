package de.schildbach.wallet.ui.debug

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.api.ExplorerApiClient
import de.schildbach.wallet.data.BlockchainStateLiveData
import de.schildbach.wallet.service.BlockchainState
import kotlinx.android.synthetic.main.activity_debug_status.*
import androidx.appcompat.widget.Toolbar
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.pepepow.wallet.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.text.Charsets

class DebugStatusActivity : AppCompatActivity() {

    private lateinit var viewModel: de.schildbach.wallet.ui.ExplorerStatsViewModel
    private lateinit var wallet: Wallet
    private lateinit var blockchainStateLiveData: BlockchainStateLiveData
    private lateinit var pepewFormat: MonetaryFormat
    // explorerClient removed
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var latestExplorerHeight: Long? = null
    private var trackedAddress: String = ""
    private var currentBlockchainState: BlockchainState? = null
    private val logHighlightKeywords = listOf("error", "warn", "block", "sync", "exception", "peer")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_status)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = getString(R.string.debug_dashboard_title)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        val walletApp = WalletApplication.getInstance()
        walletApp.startBlockchainService(false)
        val wallet = walletApp.walletOrNull
        if (wallet == null) {
            Log.w(TAG, "Wallet not initialized yet (state=${walletApp.walletState}), finishing to avoid crash.")
            Toast.makeText(
                this,
                R.string.debug_status_wallet_not_ready,
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }
        this.wallet = wallet
        blockchainStateLiveData = BlockchainStateLiveData(walletApp)
        pepewFormat = walletApp.configuration.format.code(0, "PEPEW")

        val repo = walletApp.explorerApiStatsRepository
        val factory = de.schildbach.wallet.ui.ExplorerStatsViewModel.Factory(application, repo)
        viewModel = androidx.lifecycle.ViewModelProvider(this, factory).get(de.schildbach.wallet.ui.ExplorerStatsViewModel::class.java)

        // debug_spv_balance_value removed
        trackedAddress = wallet.currentReceiveAddress().toString()
        // debug_test_address_value.text = trackedAddress

        // Sync Mode Selector
        // Sync Mode Selector (Hidden)
        /*
        val config = walletApp.configuration
        when (config.syncMode) {
            org.dash.wallet.common.data.SyncMode.FAST_API_10POW -> radio_fast_api.isChecked = true
            org.dash.wallet.common.data.SyncMode.API_1000POW -> radio_api_1000.isChecked = true
            org.dash.wallet.common.data.SyncMode.FULL_SPV -> radio_full_spv.isChecked = true
        }

        debug_sync_mode_group.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radio_fast_api -> org.dash.wallet.common.data.SyncMode.FAST_API_10POW
                R.id.radio_api_1000 -> org.dash.wallet.common.data.SyncMode.API_1000POW
                R.id.radio_full_spv -> org.dash.wallet.common.data.SyncMode.FULL_SPV
                else -> org.dash.wallet.common.data.SyncMode.FAST_API_10POW
            }
            if (config.syncMode != newMode) {
                config.syncMode = newMode
                android.widget.Toast.makeText(this, "Sync mode changed to $newMode. Restarting service...", android.widget.Toast.LENGTH_SHORT).show()
                
                // Restart BlockchainService
                val intent = android.content.Intent(this, de.schildbach.wallet.service.BlockchainServiceImpl::class.java)
                stopService(intent)
                handler.postDelayed({
                    walletApp.startBlockchainService(false)
                }, 1000)
            }
        }
        */

        // Display API Base URL
        // Display API Base URL
        debug_api_base_url_value.text = walletApp.configuration.apiBaseUrl

        // updateCheckpointHash(null) removed
        blockchainStateLiveData.observe(this, Observer { state ->
            currentBlockchainState = state
            debug_spv_header_height_value.text = formatHeight(state?.bestChainHeight ?: 0)
            debug_spv_wallet_height_value.text = formatHeight(wallet.lastBlockSeenHeight)
            updateSourceNote()
        })

        viewModel.apiStatus.observe(this, Observer { status ->
             if (status != null) {
                 debug_api_status_value.text = status.state.name
             }
        })

        viewModel.networkStats.observe(this, Observer { stats ->
            if (stats != null) {
                debug_explorer_height_value.text = stats.explorerTipHeight.toString()
                latestExplorerHeight = stats.explorerTipHeight
                // debug_explorer_balance_value removed 
                updateSourceNote()
            }
        })

        debug_refresh_button.setOnClickListener {
            viewModel.refresh(true)
            loadRecentLogs()
        }

        viewModel.refresh(false)
        loadRecentLogs()
    }



    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }



    private fun updateSourceNote() {
        val blockchainState = currentBlockchainState
        latestExplorerHeight?.let { explorerHeight ->
            if (blockchainState != null) {
                debug_data_source_label.text = getString(
                    R.string.debug_dashboard_source_label_explorer,
                    explorerHeight,
                    formatHeight(blockchainState.bestChainHeight)
                )
                return
            }
        }

        if (blockchainState != null) {
            debug_data_source_label.text = getString(
                R.string.debug_dashboard_source_label_local,
                formatHeight(blockchainState.bestChainHeight)
            )
        } else {
            debug_data_source_label.text = getString(R.string.debug_dashboard_status_loading)
        }
    }

    // Checkpoint verification removed

    private fun formatHeight(raw: Int): String {
        return if (raw > 0) raw.toString() else getString(R.string.debug_dashboard_value_unknown)
    }

    private fun loadRecentLogs() {
        debug_recent_logs_value.text = getString(R.string.debug_dashboard_logs_loading)
        executor.execute {
            val latestLogs = readRecentLogs()
            handler.post {
                debug_recent_logs_value.text = latestLogs
            }
        }
    }

    private fun readRecentLogs(): String {
        val logFile = File(filesDir, "log/wallet.log")
        if (!logFile.exists() || logFile.length() == 0L) {
            return getString(R.string.debug_dashboard_logs_empty)
        }

        return try {
            val lines = loadLogTail(logFile)
            if (lines.isEmpty()) {
                getString(R.string.debug_dashboard_logs_empty)
            } else {
                val important = lines.filter { line ->
                    logHighlightKeywords.any { keyword -> line.contains(keyword, ignoreCase = true) }
                }
                val selection = if (important.isNotEmpty()) important else lines
                selection.takeLast(60).joinToString("\n")
            }
        } catch (e: IOException) {
            val reason = e.localizedMessage ?: getString(R.string.debug_dashboard_value_unknown)
            getString(R.string.debug_dashboard_logs_error, reason)
        }
    }

    @Throws(IOException::class)
    private fun loadLogTail(file: File): List<String> {
        val maxBytes = 256 * 1024L
        val fileLength = file.length()
        if (fileLength == 0L) {
            return emptyList()
        }
        val start = (fileLength - maxBytes).coerceAtLeast(0L)
        val bytesToRead = (fileLength - start).toInt()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            return buffer.toString(Charsets.UTF_8)
                .lines()
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() }
        }
    }

    companion object {
        private const val TAG = "DebugStatusActivity"
    }

}
