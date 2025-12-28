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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.SyncMode;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.BlockInfo;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.util.ExplorerConfig;
import org.pepepow.wallet.R;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;

/**
 * @author Andreas Schildbach
 */
public final class BlockListFragment extends Fragment implements BlockListAdapter.OnClickListener {
	private AbstractWalletActivity activity;
	private WalletApplication application;
	private Configuration config;
	@Nullable
	private Wallet wallet;
	private LoaderManager loaderManager;

	private BlockchainService service;

	private ViewAnimator viewGroup;
	private RecyclerView recyclerView;
	private BlockListAdapter adapter;
	private TextView modeDescriptionView;

	private long explorerTipHeight = 0;
	private SyncMode syncMode;
	private boolean walletMissingWarningShown = false;
	@Nullable
	private Boolean lastForcedSpvUiSource = null;

	private static final int ID_BLOCK_LOADER = 0;
	private static final int ID_TRANSACTION_LOADER = 1;

	private static final int MAX_BLOCKS = 64;

	private static final Logger log = LoggerFactory.getLogger(BlockListFragment.class);

	@Override
	public void onAttach(final Activity activity) {
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
		this.application = this.activity.getWalletApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWalletOrNull();
		this.loaderManager = getLoaderManager();
		this.syncMode = config.getSyncMode();
		if (this.wallet == null && application.getWalletState() != WalletApplication.WalletState.LOADING) {
			log.info("BlockListFragment: wallet not yet loaded; requesting load.");
			application.loadWallet();
		}
	}

	@Override
	public void onActivityCreated(final Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		activity.bindService(new Intent(activity, BlockchainServiceImpl.class), serviceConnection,
				Context.BIND_AUTO_CREATE);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (wallet != null) {
			adapter = new BlockListAdapter(activity, wallet, this);
			adapter.setFormat(config.getFormat());
			adapter.setExplorerTipHeight(explorerTipHeight);
		} else {
			log.info("BlockListFragment: wallet is null onCreate, waiting for load before creating adapter.");
		}
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
			final Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.block_list_fragment, container, false);

		viewGroup = (ViewAnimator) view.findViewById(R.id.block_list_group);

		modeDescriptionView = view.findViewById(R.id.block_list_mode_description);
		updateModeDescription();

		recyclerView = (RecyclerView) view.findViewById(R.id.block_list);
		recyclerView.setLayoutManager(new LinearLayoutManager(activity));
		if (adapter != null) {
			recyclerView.setAdapter(adapter);
			adapter.setExplorerTipHeight(explorerTipHeight);
		} else {
			viewGroup.setDisplayedChild(0);
		}

		return view;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		application.getWalletStateLiveData().observe(getViewLifecycleOwner(),
				new Observer<WalletApplication.WalletState>() {
					@Override
					public void onChanged(WalletApplication.WalletState state) {
						if (state == WalletApplication.WalletState.LOADED) {
							onWalletReady();
						} else if (state == WalletApplication.WalletState.FAILED) {
							showWalletNotReadyMessage();
						}
					}
				});
	}

	private boolean resumed = false;

	@Override
	public void onResume() {
		super.onResume();

		activity.registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
		wallet = application.getWalletOrNull();
		if (wallet == null) {
			log.info("BlockListFragment: wallet still null onResume; waiting for state update.");
			showWalletNotReadyMessage();
			resumed = true;
			return;
		}
		onWalletReady();
		if (adapter != null) {
			adapter.notifyDataSetChanged();
			adapter.setExplorerTipHeight(explorerTipHeight);
		}
		syncMode = config.getSyncMode();
		updateModeDescription();
		loaderManager.initLoader(ID_TRANSACTION_LOADER, null, transactionLoaderCallbacks);
		initLoadersIfReady();
		resumed = true;
	}

	@Override
	public void onPause() {
		// workaround: under high load, it can happen that onPause() is called twice
		// (recursively via
		// destroyLoader)
		if (resumed) {
			resumed = false;

			loaderManager.destroyLoader(ID_TRANSACTION_LOADER);
			activity.unregisterReceiver(tickReceiver);
		} else {
			log.warn("onPause() called without onResume(), appending stack trace", new RuntimeException());
		}

		super.onPause();
	}

	@Override
	public void onDestroy() {
		activity.unbindService(serviceConnection);

		super.onDestroy();
	}

	@Override
	public void onBlockMenuClick(final View view, final StoredBlock block) {
		Context wrapper = new ContextThemeWrapper(activity, R.style.My_PopupOverlay);
		final PopupMenu popupMenu = new PopupMenu(wrapper, view);
		popupMenu.inflate(R.menu.blocks_context);

		popupMenu.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(final MenuItem item) {
				switch (item.getItemId()) {
					case R.id.blocks_context_browse:
						if (explorerTipHeight > 0 && block.getHeight() > explorerTipHeight) {
							Toast.makeText(getContext(),
									getString(R.string.network_monitor_block_not_on_explorer_toast),
									Toast.LENGTH_SHORT).show();
							return true;
						}
						// BUG FIX #5: Use ExplorerConfig for dynamic explorer URL
						String blockUrl = ExplorerConfig.getBlockBrowserUrl(block.getHeader().getHashAsString());
						startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(blockUrl)));
						return true;
				}
				return false;
			}
		});
		popupMenu.show();
	}

	@Override
	public void onBlockClicked(BlockInfo blockInfo) {
		startActivity(BlockInfoActivity.createIntent(getContext(), blockInfo));
		getActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay);
	}

	private boolean shouldForceSpvUiSource() {
		final SyncMode mode = config != null ? config.getSyncMode() : null;
		final boolean disabledSession = mode == SyncMode.FAST_API_10POW && config != null
				&& config.isFastApiSyncFailed();
		if (lastForcedSpvUiSource == null || lastForcedSpvUiSource.booleanValue() != disabledSession) {
			lastForcedSpvUiSource = disabledSession;
			final String fastBootState = disabledSession ? "DISABLED_SESSION" : "ACTIVE";
			log.info("BLOCKS-UI: FAST_BOOT_STATE observed by UI = {} (mode={}, fastApiSyncFailed={})",
					fastBootState, mode, config != null && config.isFastApiSyncFailed());
			if (disabledSession) {
				log.info("UI source switched to SPV due to FAST_BOOT DISABLED_SESSION");
			}
		}
		return disabledSession;
	}

	public void updateNetworkData(long explorerHeight, @Nullable SyncMode syncMode) {
		final boolean forceSpv = shouldForceSpvUiSource();
		this.explorerTipHeight = forceSpv ? 0 : explorerHeight;
		if (syncMode != null) {
			this.syncMode = syncMode;
		}
		if (adapter != null) {
			adapter.setExplorerTipHeight(explorerTipHeight);
		}
		log.info("BLOCKS-UI: listSource=SPV (forceSpv={}, explorerTipHeight={})", forceSpv, this.explorerTipHeight);
		updateModeDescription();

		// Refresh the loader to respect the new effective tip height
		if (loaderManager != null && loaderManager.getLoader(ID_BLOCK_LOADER) != null) {
			loaderManager.getLoader(ID_BLOCK_LOADER).forceLoad();
		}
	}

	private int getEffectiveDisplayTipHeight(int spvTip, int explorerTip) {
		// Always show all blocks we have, even if ahead of explorer.
		// This ensures the list is populated during sync.
		return spvTip;
	}

	private final ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(final ComponentName name, final IBinder binder) {
			service = ((BlockchainServiceImpl.LocalBinder) binder).getService();
			initLoadersIfReady();
		}

		@Override
		public void onServiceDisconnected(final ComponentName name) {
			loaderManager.destroyLoader(ID_BLOCK_LOADER);

			service = null;
		}
	};

	private final BroadcastReceiver tickReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			adapter.notifyDataSetChanged();
		}
	};

	private void updateModeDescription() {
		if (modeDescriptionView == null) {
			return;
		}
		if (syncMode == null) {
			syncMode = config.getSyncMode();
		}
		final boolean forceSpv = shouldForceSpvUiSource();
		String description;
		if (syncMode == null) {
			description = getString(R.string.network_monitor_blocks_hint_unknown);
		} else {
			switch (syncMode) {
				case FAST_API_10POW:
					if (forceSpv) {
						description = getString(R.string.network_monitor_blocks_hint_full_spv);
					} else {
						org.dash.wallet.common.data.WalletSnapshotStatus status = config.getWalletSnapshotStatus();
						if (status == org.dash.wallet.common.data.WalletSnapshotStatus.FAILED) {
							description = getString(R.string.network_monitor_blocks_hint_fast_api_snapshot_failed);
						} else if (status == org.dash.wallet.common.data.WalletSnapshotStatus.EMPTY_OK) {
							description = getString(R.string.network_monitor_blocks_hint_fast_api)
									+ " (Empty snapshot)";
						} else {
							description = getString(R.string.network_monitor_blocks_hint_fast_api);
						}
					}
					break;
				case API_1000POW:
					description = getString(R.string.network_monitor_blocks_hint_secure_api);
					break;
				case FULL_SPV:
					description = getString(R.string.network_monitor_blocks_hint_full_spv);
					break;
				default:
					description = getString(R.string.network_monitor_blocks_hint_unknown);
					break;
			}
		}
		modeDescriptionView.setText(description);
	}

	private void onWalletReady() {
		if (wallet == null) {
			wallet = application.getWalletOrNull();
		}
		if (wallet == null) {
			showWalletNotReadyMessage();
			return;
		}
		if (adapter == null) {
			adapter = new BlockListAdapter(activity, wallet, this);
			adapter.setFormat(config.getFormat());
		}
		adapter.setExplorerTipHeight(explorerTipHeight);
		if (recyclerView != null && recyclerView.getAdapter() == null) {
			recyclerView.setAdapter(adapter);
		}
		updateModeDescription();
		if (resumed) {
			loaderManager.initLoader(ID_TRANSACTION_LOADER, null, transactionLoaderCallbacks);
			initLoadersIfReady();
		}
	}

	private void initLoadersIfReady() {
		if (wallet == null || service == null) {
			return;
		}
		loaderManager.initLoader(ID_BLOCK_LOADER, null, blockLoaderCallbacks);
	}

	private void showWalletNotReadyMessage() {
		if (viewGroup != null) {
			viewGroup.setDisplayedChild(0);
		}
		if (!walletMissingWarningShown && getContext() != null) {
			Toast.makeText(getContext(), R.string.network_monitor_wallet_not_loaded, Toast.LENGTH_SHORT).show();
			walletMissingWarningShown = true;
		}
	}

	private static class BlockLoader extends AsyncTaskLoader<List<StoredBlock>> {
		private LocalBroadcastManager broadcastManager;
		private BlockchainService service;
		private final BlockListFragment fragment;

		private BlockLoader(final Context context, final BlockchainService service, BlockListFragment fragment) {
			super(context);

			this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
			this.service = service;
			this.fragment = fragment;
		}

		@Override
		protected void onStartLoading() {
			super.onStartLoading();

			broadcastManager.registerReceiver(broadcastReceiver,
					new IntentFilter(BlockchainService.ACTION_BLOCKCHAIN_STATE));

			forceLoad();
		}

		@Override
		protected void onStopLoading() {
			broadcastManager.unregisterReceiver(broadcastReceiver);

			super.onStopLoading();
		}

		@Override
		public List<StoredBlock> loadInBackground() {
			final SyncMode configuredMode = fragment != null && fragment.config != null ? fragment.config.getSyncMode()
					: WalletApplication.getInstance().getConfiguration().getSyncMode();
			final boolean forceSpv = configuredMode == SyncMode.FAST_API_10POW
					&& fragment != null && fragment.config != null && fragment.config.isFastApiSyncFailed();
			final SyncMode effectiveMode = forceSpv ? SyncMode.FULL_SPV : configuredMode;

			log.info("BLOCKS-UI: selecting listSource=SPV mode={} (configuredMode={}, forceSpv={})",
					effectiveMode, configuredMode, forceSpv);

			int limit = getNetworkMonitorBlockLimit(effectiveMode);
			List<StoredBlock> blocks = service.getRecentBlocks(limit);

			// Filter blocks based on effective tip height
			if (fragment.explorerTipHeight > 0) {
				int spvTip = blocks.isEmpty() ? 0 : blocks.get(0).getHeight();
				int effectiveTip = fragment.getEffectiveDisplayTipHeight(spvTip, (int) fragment.explorerTipHeight);

				java.util.Iterator<StoredBlock> iter = blocks.iterator();
				while (iter.hasNext()) {
					if (iter.next().getHeight() > effectiveTip) {
						iter.remove();
					}
				}
			}

			// Fix for FAST_API_10POW showing "Block 0" rows:
			// In this mode, we might not have a full chain history, so getting "0" might
			// indicate
			// missing headers or uninitialized blocks being returned as defaults.
			// We strictly filter out blocks with height 0 in this mode, unless it is
			// legally the genesis block
			// and that's all we have (which shouldn't happen for a synced wallet).
			if (effectiveMode == SyncMode.FAST_API_10POW || effectiveMode == SyncMode.API_1000POW) {
				java.util.Iterator<StoredBlock> iter = blocks.iterator();
				while (iter.hasNext()) {
					StoredBlock b = iter.next();
					if (b.getHeight() == 0) {
						// Log debug if needed, but for now just remove to clean UI
						iter.remove();
					}
				}
			}

			if (blocks.size() == 1 && blocks.get(0).getHeight() == 0) {
				// Only genesis block found, treat as empty
				blocks.clear();
			}

			// LOGGING for verification
			if (!blocks.isEmpty()) {
				log.info("BLOCKS-UI: Loaded {} blocks. Top: Height={}, Hash={}", blocks.size(),
						blocks.get(0).getHeight(), blocks.get(0).getHeader().getHashAsString());
			} else {
				log.info("BLOCKS-UI: Loaded 0 blocks (empty or filtered).");
			}

			return blocks;
		}

		private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(final Context context, final Intent intent) {
				try {
					forceLoad();
				} catch (final RejectedExecutionException x) {
					log.info("rejected execution: " + BlockLoader.this.toString());
				}
			}
		};
	}

	private static int getNetworkMonitorBlockLimit(SyncMode mode) {
		if (mode == null)
			return 1000;
		switch (mode) {
			case FAST_API_10POW:
				return 10;
			case API_1000POW:
				return 100;
			case FULL_SPV:
			default:
				return 1000;
		}
	}

	private final LoaderManager.LoaderCallbacks<List<StoredBlock>> blockLoaderCallbacks = new LoaderManager.LoaderCallbacks<List<StoredBlock>>() {
		@Override
		public Loader<List<StoredBlock>> onCreateLoader(final int id, final Bundle args) {
			// final SyncMode mode =
			// WalletApplication.getInstance().getConfiguration().getSyncMode();
			// Code removed (ExplorerBlockLoader is deleted)
			return new BlockLoader(activity, service, BlockListFragment.this);
		}

		@Override
		public void onLoadFinished(@NonNull Loader<List<StoredBlock>> loader, List<StoredBlock> blocks) {
			if (adapter == null) {
				log.info("BlockListFragment: block loader finished but adapter not ready.");
				showWalletNotReadyMessage();
				return;
			}
			adapter.replace(blocks);
			viewGroup.setDisplayedChild(1);

			final Loader<Set<Transaction>> transactionLoader = loaderManager.getLoader(ID_TRANSACTION_LOADER);
			if (transactionLoader != null && transactionLoader.isStarted())
				transactionLoader.forceLoad();
		}

		@Override
		public void onLoaderReset(@NonNull Loader<List<StoredBlock>> loader) {
			if (adapter != null) {
				adapter.clear();
			}
		}
	};

	private static class TransactionsLoader extends AsyncTaskLoader<Set<Transaction>> {
		private final Wallet wallet;

		private TransactionsLoader(final Context context, final Wallet wallet) {
			super(context);

			this.wallet = wallet;
		}

		@Override
		public Set<Transaction> loadInBackground() {
			if (wallet == null) {
				log.warn("TransactionsLoader: wallet is null, returning empty transaction set.");
				return java.util.Collections.emptySet();
			}

			org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

			final Set<Transaction> transactions = wallet.getTransactions(true);

			final Set<Transaction> filteredTransactions = new HashSet<Transaction>(transactions.size());
			for (final Transaction tx : transactions) {
				final Map<Sha256Hash, Integer> appearsIn = tx.getAppearsInHashes();
				if (appearsIn != null && !appearsIn.isEmpty()) // TODO filter by updateTime
					filteredTransactions.add(tx);
			}

			return filteredTransactions;
		}
	}

	private final LoaderManager.LoaderCallbacks<Set<Transaction>> transactionLoaderCallbacks = new LoaderManager.LoaderCallbacks<Set<Transaction>>() {

		@NonNull
		@Override
		public Loader<Set<Transaction>> onCreateLoader(int id, @Nullable Bundle args) {
			return new TransactionsLoader(activity, wallet);
		}

		@Override
		public void onLoadFinished(@NonNull Loader<Set<Transaction>> loader, Set<Transaction> transactions) {
			if (adapter != null) {
				adapter.replaceTransactions(transactions);
			}
		}

		@Override
		public void onLoaderReset(@NonNull Loader<Set<Transaction>> loader) {
			adapter.clearTransactions();
		}
	};
}
