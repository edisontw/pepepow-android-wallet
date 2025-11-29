/*
 * Copyright 2013-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.Observer;
import androidx.viewpager.widget.ViewPager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockchainStateLiveData;
import de.schildbach.wallet.data.api.ApiStatus;
import de.schildbach.wallet.data.api.NetworkStats;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.data.api.ExplorerApiStatsRepository;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.SyncMode;
import org.pepepow.wallet.R;

/**
 * @author Andreas Schildbach
 */
public final class NetworkMonitorActivity extends AbstractBindServiceActivity {

    private PeerListFragment peerListFragment;
    private BlockListFragment blockListFragment;
    private ViewPager pager;
    private CheckBox peersCheckBox;
    private CheckBox blocksCheckBox;
    private TextView syncModeValueView;
    private TextView spvHeightView;
    private TextView peerCountView;
    private TextView syncProgressView;
    private TextView warningView;
    private TextView apiStateView;
    private TextView apiHeightView;
    private TextView heightDiffView;
    private TextView apiDifficultyView;
    private TextView apiHashrateView;
    private TextView apiMasternodesView;
    private TextView apiPriceView;
    private TextView apiUpdatedView;
    private TextView apiSourceView;
    private static final long AHEAD_OF_EXPLORER_THRESHOLD = 25;

    private WalletApplication walletApplication;
    private Configuration config;
    private BlockchainStateLiveData blockchainStateLiveData;
    private BlockchainState latestBlockchainState;
    private ApiStatus latestApiStatus;
    private NetworkStats latestNetworkStats;
    private long latestExplorerHeight = 0;
    private int latestPeerCount = 0;

    private final BroadcastReceiver peerStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            latestPeerCount = intent.getIntExtra(BlockchainService.ACTION_PEER_STATE_NUM_PEERS, 0);
            updateSpvSection();
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.network_monitor_content);
        walletApplication = WalletApplication.getInstance();
        config = walletApplication.getConfiguration();
        blockchainStateLiveData = new BlockchainStateLiveData(walletApplication);

        bindStatusViews();

        pager = findViewById(R.id.network_monitor_pager);
        final FragmentManager fm = getSupportFragmentManager();

        peersCheckBox = findViewById(R.id.peers_checkbox);
        blocksCheckBox = findViewById(R.id.blocks_checkbox);

        if (pager != null) {
            peersCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);
            blocksCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);

            String peersTitle = getString(R.string.network_monitor_peer_list_title);
            String blocksTitle = getString(R.string.network_monitor_block_list_title);
            final PagerAdapter pagerAdapter = new PagerAdapter(fm, peersTitle, blocksTitle);

            pager.setAdapter(pagerAdapter);
            pager.setPageMargin(2);
            pager.setPageMarginDrawable(R.color.bg_less_bright);
            pager.addOnPageChangeListener(onPageChangeListener);

            peerListFragment = new PeerListFragment();
            blockListFragment = new BlockListFragment();
        } else {
            peerListFragment = (PeerListFragment) fm.findFragmentById(R.id.peer_list_fragment);
            blockListFragment = (BlockListFragment) fm.findFragmentById(R.id.block_list_fragment);
        }

        blockchainStateLiveData.observe(this, new Observer<BlockchainState>() {
            @Override
            public void onChanged(BlockchainState blockchainState) {
                latestBlockchainState = blockchainState;
                updateSpvSection();
            }
        });
        ExplorerApiStatsRepository repo = walletApplication.getExplorerApiStatsRepository();
        de.schildbach.wallet.ui.ExplorerStatsViewModel.Factory factory = new de.schildbach.wallet.ui.ExplorerStatsViewModel.Factory(
                getApplication(), repo);
        de.schildbach.wallet.ui.ExplorerStatsViewModel viewModel = new androidx.lifecycle.ViewModelProvider(this,
                factory).get(de.schildbach.wallet.ui.ExplorerStatsViewModel.class);

        viewModel.getApiStatus().observe(this, new Observer<ApiStatus>() {
            @Override
            public void onChanged(ApiStatus apiStatus) {
                latestApiStatus = apiStatus;
                updateApiSection();
            }
        });
        walletApplication.getNetworkStatsLiveData().observe(this, new Observer<NetworkStats>() {
            @Override
            public void onChanged(NetworkStats networkStats) {
                latestNetworkStats = networkStats;
                updateApiSection();
                updateWarning();
            }
        });
        updateSpvSection();
        updateApiSection();
    }

    private ViewPager.OnPageChangeListener onPageChangeListener = new ViewPager.OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

        }

        @Override
        public void onPageSelected(int position) {
            peersCheckBox.setOnCheckedChangeListener(null);
            blocksCheckBox.setOnCheckedChangeListener(null);

            switch (position) {
                case 0:
                    peersCheckBox.setChecked(true);
                    blocksCheckBox.setChecked(false);
                    break;
                case 1:
                    peersCheckBox.setChecked(false);
                    blocksCheckBox.setChecked(true);
                    break;
            }

            peersCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);
            blocksCheckBox.setOnCheckedChangeListener(onCheckedChangeListener);
        }

        @Override
        public void onPageScrollStateChanged(int state) {

        }
    };

    private CheckBox.OnCheckedChangeListener onCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (buttonView == peersCheckBox) {
                pager.setCurrentItem(0, true);
            } else {
                pager.setCurrentItem(1, true);
            }
        }
    };

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(peerStateReceiver,
                new IntentFilter(BlockchainService.ACTION_PEER_STATE));
        walletApplication.refreshExplorerStats(false);
    }

    @Override
    protected void onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(peerStateReceiver);
        super.onPause();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left);
    }

    private void bindStatusViews() {
        syncModeValueView = findViewById(R.id.network_status_mode_value);
        spvHeightView = findViewById(R.id.network_status_height_value);
        peerCountView = findViewById(R.id.network_status_peers_value);
        syncProgressView = findViewById(R.id.network_status_progress_value);
        warningView = findViewById(R.id.network_status_warning);
        apiStateView = findViewById(R.id.network_status_api_state_value);
        apiHeightView = findViewById(R.id.network_status_api_height_value);
        heightDiffView = findViewById(R.id.network_status_height_diff_value);
        apiDifficultyView = findViewById(R.id.network_status_api_difficulty_value);
        apiHashrateView = findViewById(R.id.network_status_api_hashrate_value);
        apiMasternodesView = findViewById(R.id.network_status_api_masternodes_value);
        apiPriceView = findViewById(R.id.network_status_api_price_value);
        apiUpdatedView = findViewById(R.id.network_status_api_updated_value);
        apiSourceView = findViewById(R.id.network_status_api_source_value);
    }

    private void updateSpvSection() {
        if (syncModeValueView != null) {
            syncModeValueView.setText(getSyncModeLabel(config.getSyncMode()));
        }
        if (peerCountView != null) {
            peerCountView.setText(String.valueOf(latestPeerCount));
        }
        if (spvHeightView != null) {
            int height = latestBlockchainState != null ? latestBlockchainState.bestChainHeight : 0;
            spvHeightView
                    .setText(height > 0 ? String.valueOf(height) : getString(R.string.network_monitor_sync_unknown));
        }
        if (syncProgressView != null) {
            syncProgressView.setText(formatSyncProgress());
        }
        updateHeightDiff();
        updateBlockListState();
        updateWarning();
    }

    private void updateWarning() {
        if (warningView == null) {
            return;
        }
        warningView.setVisibility(View.GONE);
        if (latestBlockchainState != null) {
            long explorerHeight = resolveExplorerHeight();
            long diff = explorerHeight - latestBlockchainState.bestChainHeight;
            if (diff > 10) {
                warningView.setText(getString(R.string.network_monitor_chain_warning, diff));
                warningView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateApiSection() {
        if (apiStateView != null) {
            apiStateView.setText(formatApiState(latestApiStatus));
        }
        if (apiSourceView != null) {
            apiSourceView.setText(getString(R.string.network_monitor_api_source_label, config.getApiBaseUrl()));
        }
        latestExplorerHeight = resolveExplorerHeight();
        if (apiHeightView != null) {
            apiHeightView.setText(latestExplorerHeight > 0 ? String.valueOf(latestExplorerHeight)
                    : getString(R.string.network_monitor_stat_unavailable));
        }
        if (apiDifficultyView != null) {
            double difficulty = latestNetworkStats != null ? latestNetworkStats.difficulty : Double.NaN;
            apiDifficultyView.setText(formatDifficulty(difficulty));
        }
        if (apiHashrateView != null) {
            double hashrate = latestNetworkStats != null ? latestNetworkStats.networkHashrate : Double.NaN;
            apiHashrateView.setText(formatHashrate(hashrate));
        }
        if (apiMasternodesView != null) {
            int mn = latestNetworkStats != null ? latestNetworkStats.masternodeCount : 0;
            apiMasternodesView.setText(
                    mn > 0 ? String.valueOf(mn) : getString(R.string.network_monitor_stat_unavailable));
        }
        if (apiPriceView != null) {
            String price = latestNetworkStats != null ? latestNetworkStats.priceUsd : null;
            apiPriceView.setText(formatPrice(price));
        }
        if (apiUpdatedView != null) {
            long updated = latestNetworkStats != null ? latestNetworkStats.lastUpdatedMillis : 0;
            if (updated <= 0 && latestApiStatus != null) {
                updated = latestApiStatus.getLastCheckedMillis();
            }
            apiUpdatedView.setText(formatUpdatedTime(updated));
        }
        updateHeightDiff();
        updateBlockListState();
    }

    private long resolveExplorerHeight() {
        long explorerHeight = latestNetworkStats != null ? latestNetworkStats.explorerTipHeight : 0;
        if (explorerHeight <= 0 && latestApiStatus != null && latestApiStatus.getLastCheckpointHeight() > 0) {
            explorerHeight = latestApiStatus.getLastCheckpointHeight();
        }
        return explorerHeight;
    }

    private void updateHeightDiff() {
        if (heightDiffView == null) {
            return;
        }
        latestExplorerHeight = resolveExplorerHeight();
        int spvHeight = latestBlockchainState != null ? latestBlockchainState.bestChainHeight : 0;
        if (spvHeight <= 0 || latestExplorerHeight <= 0) {
            heightDiffView.setText(getString(R.string.network_monitor_sync_unknown));
            return;
        }
        long diff = spvHeight - latestExplorerHeight;
        if (diff > 0) {
            if (diff > AHEAD_OF_EXPLORER_THRESHOLD) {
                heightDiffView.setText(
                        getString(R.string.network_monitor_height_diff_wallet_ahead_delay, diff));
            } else {
                heightDiffView.setText(getString(R.string.network_monitor_height_diff_wallet_ahead, diff));
            }
        } else if (diff < 0) {
            heightDiffView.setText(getString(R.string.network_monitor_height_diff_wallet_behind, -diff));
        } else {
            heightDiffView.setText(getString(R.string.network_monitor_height_diff_in_sync));
        }
    }

    private void updateBlockListState() {
        if (blockListFragment != null) {
            blockListFragment.updateNetworkData(latestExplorerHeight, config.getSyncMode());
        }
    }

    private String getSyncModeLabel(SyncMode mode) {
        if (mode == null) {
            return getString(R.string.network_monitor_sync_unknown);
        }
        switch (mode) {
            case FAST_API_10POW:
                return getString(R.string.sync_mode_fast_api_10pow);
            case API_1000POW:
                return getString(R.string.sync_mode_secure_api_1000pow);
            case FULL_SPV:
                return getString(R.string.sync_mode_full_spv);
            default:
                return mode.name();
        }
    }

    private String formatSyncProgress() {
        if (latestBlockchainState != null && latestBlockchainState.percentageSync > 0) {
            return latestBlockchainState.percentageSync + "%";
        }
        if (config.getSyncMode() == SyncMode.FAST_API_10POW) {
            if (latestBlockchainState == null || latestBlockchainState.bestChainHeight == 0) {
                return getString(R.string.network_monitor_progress_bootstrap);
            }
            if (latestApiStatus == null || latestApiStatus.getState() != ApiStatus.State.HEALTHY) {
                return getString(R.string.network_monitor_progress_bootstrap);
            }
        }
        return getString(R.string.network_monitor_sync_unknown);
    }

    private String formatApiState(ApiStatus status) {
        if (status == null) {
            return getString(R.string.api_status_unknown);
        }
        switch (status.getState()) {
            case HEALTHY:
                return getString(R.string.api_status_healthy);
            case DEGRADED:
                return getString(R.string.api_status_degraded);
            case OFFLINE:
            default:
                return getString(R.string.api_status_offline);
        }
    }

    private String formatDifficulty(double difficulty) {
        if (Double.isNaN(difficulty) || difficulty <= 0) {
            return getString(R.string.network_monitor_stat_unavailable);
        }
        if (difficulty >= 1000) {
            return String.format(Locale.US, "%.2f", difficulty);
        }
        return String.format(Locale.US, "%.4f", difficulty);
    }

    private String formatHashrate(double hashrate) {
        if (Double.isNaN(hashrate) || hashrate <= 0) {
            return getString(R.string.network_monitor_stat_unavailable);
        }
        final String[] units = new String[] { getString(R.string.network_monitor_hashrate_unit), "kH/s", "MH/s", "GH/s",
                "TH/s", "PH/s" };
        double value = hashrate;
        int unitIndex = 0;
        while (value >= 1000 && unitIndex < units.length - 1) {
            value /= 1000.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.2f %s", value, units[unitIndex]);
    }

    private String formatPrice(String priceUsd) {
        if (priceUsd == null || priceUsd.isEmpty()) {
            return getString(R.string.network_monitor_stat_unavailable);
        }
        return "$" + priceUsd;
    }

    private String formatUpdatedTime(long timestamp) {
        if (timestamp <= 0) {
            return getString(R.string.preferences_api_last_check_summary);
        }
        DateFormat formatter = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault());
        return formatter.format(new Date(timestamp));
    }

    private class PagerAdapter extends FragmentStatePagerAdapter {

        private final String peersTitle;
        private final String blocksTitle;

        public PagerAdapter(final FragmentManager fm, String peersTitle, String blocksTitle) {
            super(fm);
            this.peersTitle = peersTitle;
            this.blocksTitle = blocksTitle;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public Fragment getItem(final int position) {
            if (position == 0)
                return peerListFragment;
            else
                return blockListFragment;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return peersTitle;
                case 1:
                    return blocksTitle;
            }
            return super.getPageTitle(position);
        }
    }
}
