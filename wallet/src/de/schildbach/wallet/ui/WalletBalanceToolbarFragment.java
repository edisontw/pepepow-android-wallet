/*
 * Copyright 2011-2015 the original author or authors.
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

import de.schildbach.wallet.service.BlockchainService;

import android.app.Activity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.MasternodeSync;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.utils.Fiat;
import org.dash.wallet.common.ui.CurrencyTextView;

import javax.annotation.Nullable;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.rates.ExchangeRate;
import de.schildbach.wallet.rates.ExchangeRatesViewModel;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.util.BlockchainStateUtils;
import org.pepepow.wallet.R;

/**
 * @author Andreas Schildbach
 */
public final class WalletBalanceToolbarFragment extends Fragment {
	private WalletApplication application;
	private AbstractBindServiceActivity activity;
	private Configuration config;
	private Wallet wallet;
	private LoaderManager loaderManager;

	private View viewBalance;
	private View progressView;
	private CurrencyTextView viewBalanceBtc;
	private View viewBalanceTooMuch;
	private CurrencyTextView viewBalanceLocal;
	private View appBarBottom;
	private TextView appBarMessageView;

	private boolean showLocalBalance;

	private String progressMessage;

	private ExchangeRatesViewModel exchangeRatesViewModel;

	@Nullable
	private Coin balance = null;
	@Nullable
	private ExchangeRate exchangeRate = null;
	@Nullable
	private BlockchainState blockchainState = null;
	@Nullable
	private int masternodeSyncStatus = MasternodeSync.MASTERNODE_SYNC_FINISHED;
	@Nullable
	private BlockchainService.WalletUsabilityState latestUsabilityState = null;

	private static final int ID_BALANCE_LOADER = 0;
	private static final int ID_BLOCKCHAIN_STATE_LOADER = 1;
	private static final int ID_MASTERNODE_SYNC_LOADER = 2;

	private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;
	private static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.multiply(30);

	private boolean initComplete = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		MenuItem walletLockMenuItem = menu.findItem(R.id.wallet_options_lock);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public void onAttach(final Activity activity) {
		super.onAttach(activity);

		this.activity = (AbstractBindServiceActivity) activity;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
		this.loaderManager = getLoaderManager();

		showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
	}

	@Override
	public void onActivityCreated(@androidx.annotation.Nullable Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		appBarMessageView = activity.findViewById(R.id.toolbar_message);
		appBarBottom = activity.findViewById(R.id.toolbar_bottom);
		exchangeRatesViewModel = ViewModelProviders.of(this).get(ExchangeRatesViewModel.class);
	}

	private final Observer<BlockchainService.WalletUsabilityState> usabilityObserver = new Observer<BlockchainService.WalletUsabilityState>() {
		@Override
		public void onChanged(BlockchainService.WalletUsabilityState state) {
			latestUsabilityState = state;
			updateView();
		}
	};

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
			final Bundle savedInstanceState) {
		return inflater.inflate(R.layout.wallet_balance_toolbar_fragment, container, false);
	}

	@Override
	public void onViewCreated(final View view, final Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		progressView = view.findViewById(R.id.progress);

		viewBalance = view.findViewById(R.id.wallet_balance);

		viewBalanceBtc = (CurrencyTextView) view.findViewById(R.id.wallet_balance_btc);
		viewBalanceBtc.setPrefixScaleX(0.9f);

		viewBalanceTooMuch = view.findViewById(R.id.wallet_balance_too_much_warning);

		viewBalanceLocal = (CurrencyTextView) view.findViewById(R.id.wallet_balance_local);
		viewBalanceLocal.setInsignificantRelativeSize(1);
		viewBalanceLocal.setStrikeThru(!Constants.IS_PROD_BUILD);

		viewBalance.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				showWarningIfBalanceTooMuch();
				if (!(getActivity() instanceof ExchangeRatesActivity))
					showExchangeRatesActivity();
			}
		});
	}

	@Override
	public void onResume() {
		super.onResume();

		loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
		if (!initComplete) {
			loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
			initComplete = true;
		} else
			loaderManager.restartLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);

		exchangeRatesViewModel.getRate(config.getExchangeCurrencyCode()).observe(this,
				new Observer<de.schildbach.wallet.rates.ExchangeRate>() {
					@Override
					public void onChanged(de.schildbach.wallet.rates.ExchangeRate rate) {
						if (rate != null) {
							exchangeRate = rate;
							updateView();
						}
					}
				});

		updateView();

		if (activity.getBlockchainService() != null) {
			activity.getBlockchainService().getWalletUsabilityLiveData().observe(this, usabilityObserver);
		}
	}

	@Override
	public void onPause() {
		loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
		loaderManager.destroyLoader(ID_BALANCE_LOADER);
		if (activity.getBlockchainService() != null) {
			activity.getBlockchainService().getWalletUsabilityLiveData().removeObserver(usabilityObserver);
		}
		// loaderManager.destroyLoader(ID_MASTERNODE_SYNC_LOADER);

		super.onPause();
	}

	private void updateView() {
		if (!isAdded())
			return;

		WalletReadiness.logUiGateWalletReadyOnlyOnce("WalletBalanceToolbarFragment");

		final boolean walletReady = WalletReadiness.isWalletReady(application, blockchainState);

		if (blockchainState != null && blockchainState.bestChainDate != null)
			progressMessage = BlockchainStateUtils.getSyncStateString(blockchainState, getActivity());
		else
			progressMessage = null;

		if (!walletReady) {
			showAppBarMessage(getString(R.string.sync_status_syncing_headers));
			progressView.setVisibility(View.VISIBLE);
			viewBalance.setVisibility(View.INVISIBLE);
			return;
		}

		// WalletReady is the only source of truth for UI usability.
		// Any "still syncing" message must never hide balance once WalletReady=true.
		if (progressMessage != null) {
			WalletReadiness.logIgnoredFlagOnce("SYNC_PROGRESS_MESSAGE");
			showAppBarMessage(getString(R.string.sync_status_spv_syncing_background));
		} else {
			showAppBarMessage(null);
		}
		progressView.setVisibility(View.GONE);
		viewBalance.setVisibility(View.VISIBLE);

		if (!showLocalBalance)
			viewBalanceLocal.setVisibility(View.GONE);

		final Coin displayBalance = determineDisplayBalance();
		if (displayBalance != null) {
			viewBalanceBtc.setVisibility(View.VISIBLE);
			viewBalanceBtc.setFormat(config.getFormat().noCode());
			viewBalanceBtc.setAmount(displayBalance);

			updateBalanceTooMuchWarning(displayBalance);

			if (showLocalBalance) {
				if (exchangeRate != null) {
					org.bitcoinj.utils.ExchangeRate rate = new org.bitcoinj.utils.ExchangeRate(Coin.COIN,
							exchangeRate.getFiat());
					final Fiat localValue = rate.coinToFiat(displayBalance);
					viewBalanceLocal.setVisibility(View.VISIBLE);
					viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0,
							org.dash.wallet.common.Constants.PREFIX_ALMOST_EQUAL_TO + exchangeRate.getCurrencyCode()));
					viewBalanceLocal.setAmount(localValue);
				} else {
					viewBalanceLocal.setVisibility(View.INVISIBLE);
				}
			}
		} else {
			viewBalanceBtc.setVisibility(View.INVISIBLE);
		}

		activity.invalidateOptionsMenu();
	}

	@Nullable
	private Coin determineDisplayBalance() {
		if (latestUsabilityState != null && "API_SESSION".equals(latestUsabilityState.balanceSource)) {
			return latestUsabilityState.sessionBalance;
		}
		if (WalletReadiness.isWalletReady(application, blockchainState)) {
			return balance;
		}
		return balance;
	}

	private void showAppBarMessage(CharSequence message) {
		if (message != null) {
			appBarBottom.setVisibility(View.VISIBLE);
			appBarMessageView.setText(message);
		} else {
			appBarBottom.setVisibility(View.GONE);
		}
	}

	private void updateBalanceTooMuchWarning(Coin displayBalance) {
		if (displayBalance == null)
			return;

		boolean tooMuch = displayBalance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD);
		viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);
	}

	private void showWarningIfBalanceTooMuch() {
		Coin displayBalance = determineDisplayBalance();
		if (displayBalance != null && displayBalance.isGreaterThan(TOO_MUCH_BALANCE_THRESHOLD)) {
			Toast.makeText(application, getString(R.string.wallet_balance_fragment_too_much),
					Toast.LENGTH_LONG).show();
		}
	}

	private void showExchangeRatesActivity() {
		Intent intent = new Intent(getActivity(), ExchangeRatesActivity.class);
		getActivity().startActivity(intent);
	}

	private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
		@Override
		public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
			return new BlockchainStateLoader(activity);
		}

		@Override
		public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
			WalletBalanceToolbarFragment.this.blockchainState = blockchainState;

			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<BlockchainState> loader) {
		}
	};

	private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
		@Override
		public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
			return new WalletBalanceLoader(activity, wallet);
		}

		@Override
		public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
			WalletBalanceToolbarFragment.this.balance = balance;

			updateView();
		}

		@Override
		public void onLoaderReset(final Loader<Coin> loader) {
		}
	};
}
