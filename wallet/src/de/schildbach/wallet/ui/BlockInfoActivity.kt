/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockInfo
import org.pepepow.wallet.R
import kotlinx.android.synthetic.main.activity_block_info.*

class BlockInfoActivity : BaseMenuActivity() {

    companion object {

        private const val BLOCK_INFO_EXTRA = "block_info"

        @JvmStatic
        fun createIntent(context: Context, blockInfo: BlockInfo): Intent {
            val intent = Intent(context, BlockInfoActivity::class.java)
            intent.putExtra(BLOCK_INFO_EXTRA, blockInfo)
            return intent
        }
    }

    override fun getLayoutId(): Int {
        return R.layout.activity_block_info
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.block_info)

        val blockInfo = intent.getSerializableExtra(BLOCK_INFO_EXTRA) as BlockInfo
        block_height.text = "${blockInfo.height}"
        block_time.text = blockInfo.time
        block_hash.text = blockInfo.hash

        val explorerHeight = resolveExplorerHeight()
        val isAheadOfExplorer = explorerHeight > 0 && blockInfo.height > explorerHeight
        if (isAheadOfExplorer) {
            view_on_explorer.alpha = 0.5f
        }

        view_on_explorer.setOnClickListener {
            if (isAheadOfExplorer) {
                Toast.makeText(
                    this,
                    getString(R.string.network_monitor_block_not_on_explorer_toast),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val explorer = Uri.parse("https://explorer.pepepow.net")
                val blockPath = "block/${blockInfo.hash}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(explorer, blockPath)))
            }
        }
    }

    private fun resolveExplorerHeight(): Long {
        val networkStats = WalletApplication.getInstance().networkStatsLiveData.value
        var explorerHeight = networkStats?.explorerTipHeight ?: 0L
        if (explorerHeight <= 0) {
            val apiStatus = WalletApplication.getInstance().apiStatusLiveData.value
            if (apiStatus != null && apiStatus.lastCheckpointHeight > 0) {
                explorerHeight = apiStatus.lastCheckpointHeight.toLong()
            }
        }
        return explorerHeight
    }

}
