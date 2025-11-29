/*
 * Copyright 2014-2015 the original author or authors.
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

package de.schildbach.wallet.ui.preference;

import java.net.InetAddress;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.ui.debug.DebugStatusActivity;
import org.pepepow.wallet.BuildConfig;
import org.pepepow.wallet.R;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceFragment;
import android.widget.Toast;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import de.schildbach.wallet.data.api.ApiStatus;

/**
 * @author Andreas Schildbach
 */
public final class SettingsFragment extends PreferenceFragment implements OnPreferenceChangeListener {
    private Activity activity;
    private WalletApplication application;
    private Configuration config;
    private PackageManager pm;
    private de.schildbach.wallet.ui.ExplorerStatsViewModel viewModel;

    private final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private Preference btcPrecisionPreference;
    private Preference trustedPeerPreference;
    private Preference trustedPeerOnlyPreference;
    private CheckBoxPreference developerModePreference;
    private android.preference.ListPreference syncModePreference;
    private Preference debugDashboardPreference;
    private Preference apiSyncModePreference;
    private Preference apiBaseUrlPreference;
    private Preference apiHealthPreference;
    private Preference apiLastCheckPreference;
    private Preference apiCheckpointPreference;
    private Preference apiErrorPreference;

    private static final Logger log = LoggerFactory.getLogger(SettingsFragment.class);
    private static final String PREF_KEY_DEBUG_DASHBOARD = "developer_debug_dashboard";

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = activity;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();
        this.pm = activity.getPackageManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.preference_settings);

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        btcPrecisionPreference = findPreference(Configuration.PREFS_KEY_BTC_PRECISION);
        btcPrecisionPreference.setOnPreferenceChangeListener(this);

        trustedPeerPreference = findPreference(Configuration.PREFS_KEY_TRUSTED_PEER);
        ((EditTextPreference) trustedPeerPreference).getEditText().setSingleLine();
        trustedPeerPreference.setOnPreferenceChangeListener(this);

        trustedPeerOnlyPreference = findPreference(Configuration.PREFS_KEY_TRUSTED_PEER_ONLY);
        trustedPeerOnlyPreference.setOnPreferenceChangeListener(this);

        final Preference dataUsagePreference = findPreference(Configuration.PREFS_KEY_DATA_USAGE);
        dataUsagePreference.setEnabled(pm.resolveActivity(dataUsagePreference.getIntent(), 0) != null);

        developerModePreference = (CheckBoxPreference) findPreference(Configuration.PREFS_KEY_DEVELOPER_MODE);
        syncModePreference = (android.preference.ListPreference) findPreference(Configuration.PREFS_KEY_SYNC_MODE);
        debugDashboardPreference = findPreference(PREF_KEY_DEBUG_DASHBOARD);
        apiSyncModePreference = findPreference("developer_api_sync_mode");
        apiBaseUrlPreference = findPreference("developer_api_base_url");
        apiHealthPreference = findPreference("developer_api_health");
        apiLastCheckPreference = findPreference("developer_api_last_check");
        apiCheckpointPreference = findPreference("developer_api_checkpoint");
        apiErrorPreference = findPreference("developer_api_error");

        config.setDeveloperModeEnabled(true);
        if (developerModePreference != null) {
            developerModePreference.setChecked(true);
            developerModePreference.setEnabled(false);
        }
        if (debugDashboardPreference != null) {
            debugDashboardPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    startActivity(new Intent(activity, DebugStatusActivity.class));
                    return true;
                }
            });
        }
        de.schildbach.wallet.data.api.ExplorerApiStatsRepository repo = application.getExplorerApiStatsRepository();
        de.schildbach.wallet.ui.ExplorerStatsViewModel.Factory factory = new de.schildbach.wallet.ui.ExplorerStatsViewModel.Factory(
                activity.getApplication(), repo);

        androidx.lifecycle.ViewModelStoreOwner owner;
        if (activity instanceof androidx.lifecycle.ViewModelStoreOwner) {
            owner = (androidx.lifecycle.ViewModelStoreOwner) activity;
        } else {
            owner = application;
        }

        viewModel = new androidx.lifecycle.ViewModelProvider(owner, factory)
                .get(de.schildbach.wallet.ui.ExplorerStatsViewModel.class);

        if (syncModePreference != null) {
            syncModePreference.setValue(config.getSyncMode().name());
            syncModePreference.setSummary(syncModePreference.getEntry());
            syncModePreference.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String modeName = (String) newValue;
                    org.dash.wallet.common.data.SyncMode mode = org.dash.wallet.common.data.SyncMode.valueOf(modeName);
                    config.setSyncMode(mode);

                    int index = syncModePreference.findIndexOfValue(modeName);
                    if (index >= 0) {
                        syncModePreference.setSummary(syncModePreference.getEntries()[index]);
                    }

                    // Stop service to apply changes
                    application.stopBlockchainService();
                    updateApiPreferences(viewModel.getApiStatus().getValue());

                    return true;
                }
            });
        }
        if (activity instanceof LifecycleOwner) {
            viewModel.getApiStatus().observe((LifecycleOwner) activity, new Observer<ApiStatus>() {
                @Override
                public void onChanged(ApiStatus apiStatus) {
                    updateApiPreferences(apiStatus);
                }
            });
        }
        updateApiPreferences(viewModel.getApiStatus().getValue());
        updateDeveloperOptionsVisibility(true);

        updateTrustedPeer();
    }

    @Override
    public void onDestroy() {
        trustedPeerOnlyPreference.setOnPreferenceChangeListener(null);
        trustedPeerPreference.setOnPreferenceChangeListener(null);
        btcPrecisionPreference.setOnPreferenceChangeListener(null);
        if (developerModePreference != null) {
            developerModePreference.setOnPreferenceChangeListener(null);
        }
        if (syncModePreference != null) {
            syncModePreference.setOnPreferenceChangeListener(null);
        }

        backgroundThread.getLooper().quit();

        super.onDestroy();
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object newValue) {
        // delay action because preference isn't persisted until after this method
        // returns
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (preference.equals(btcPrecisionPreference)) {
                    WalletBalanceWidgetProvider.updateWidgets(activity, application.getWallet());
                } else if (preference.equals(trustedPeerPreference)) {
                    application.stopBlockchainService();
                    updateTrustedPeer();
                } else if (preference.equals(trustedPeerOnlyPreference)) {
                    application.stopBlockchainService();
                }
            }
        });

        return true;
    }

    private void updateApiPreferences(ApiStatus status) {
        if (apiBaseUrlPreference != null) {
            apiBaseUrlPreference.setSummary(config.getApiBaseUrl());
        }
        if (apiSyncModePreference != null) {
            apiSyncModePreference.setSummary(getSyncModeLabel(config.getSyncMode()));
        }
        if (status == null) {
            if (apiHealthPreference != null) {
                apiHealthPreference.setSummary(getString(R.string.preferences_api_health_summary));
            }
            if (apiLastCheckPreference != null) {
                apiLastCheckPreference.setSummary(getString(R.string.preferences_api_last_check_summary));
            }
            if (apiCheckpointPreference != null) {
                apiCheckpointPreference.setSummary(getString(R.string.preferences_api_checkpoint_summary));
            }
            if (apiErrorPreference != null) {
                apiErrorPreference.setSummary(getString(R.string.preferences_api_error_summary));
            }
            return;
        }

        if (apiHealthPreference != null) {
            String summary = formatApiState(status.getState());
            if (status.getLastHttpCode() > 0) {
                summary += " (HTTP " + status.getLastHttpCode() + ")";
            }
            apiHealthPreference.setSummary(summary);
        }
        if (apiLastCheckPreference != null) {
            if (status.getLastCheckedMillis() > 0) {
                DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                        Locale.getDefault());
                apiLastCheckPreference.setSummary(formatter.format(new Date(status.getLastCheckedMillis())));
            } else {
                apiLastCheckPreference.setSummary(getString(R.string.preferences_api_last_check_summary));
            }
        }
        if (apiCheckpointPreference != null) {
            if (status.getLastCheckpointHeight() > 0) {
                String hash = status.getLastCheckpointHash();
                if (hash != null && hash.length() > 12) {
                    hash = hash.substring(0, 12) + "...";
                }
                apiCheckpointPreference.setSummary("#" + status.getLastCheckpointHeight()
                        + (hash != null ? " (" + hash + ")" : ""));
            } else {
                apiCheckpointPreference.setSummary(getString(R.string.preferences_api_checkpoint_summary));
            }
        }
        if (apiErrorPreference != null) {
            if (status.getState() == ApiStatus.State.HEALTHY || status.getLastErrorMessage() == null) {
                apiErrorPreference.setSummary(getString(R.string.preferences_api_error_summary));
            } else {
                apiErrorPreference.setSummary(status.getLastErrorMessage());
            }
        }
    }

    private String formatApiState(ApiStatus.State state) {
        if (state == null) {
            return getString(R.string.api_status_unknown);
        }
        switch (state) {
            case HEALTHY:
                return getString(R.string.api_status_healthy);
            case DEGRADED:
                return getString(R.string.api_status_degraded);
            case OFFLINE:
            default:
                return getString(R.string.api_status_offline);
        }
    }

    private String getSyncModeLabel(org.dash.wallet.common.data.SyncMode mode) {
        if (syncModePreference != null) {
            int index = syncModePreference.findIndexOfValue(mode.name());
            if (index >= 0) {
                return syncModePreference.getEntries()[index].toString();
            }
        }
        return mode.name();
    }

    private void updateTrustedPeer() {
        final String trustedPeer = config.getTrustedPeerHost();

        if (trustedPeer == null) {
            trustedPeerPreference.setSummary(R.string.preferences_trusted_peer_summary);
            trustedPeerOnlyPreference.setEnabled(false);
        } else {
            trustedPeerPreference.setSummary(
                    trustedPeer + "\n[" + getString(R.string.preferences_trusted_peer_resolve_progress) + "]");
            trustedPeerOnlyPreference.setEnabled(true);

            new ResolveDnsTask(backgroundHandler) {
                @Override
                protected void onSuccess(final InetAddress address) {
                    trustedPeerPreference.setSummary(trustedPeer);
                    log.info("trusted peer '{}' resolved to {}", trustedPeer, address);
                }

                @Override
                protected void onUnknownHost() {
                    trustedPeerPreference.setSummary(trustedPeer + "\n["
                            + getString(R.string.preferences_trusted_peer_resolve_unknown_host) + "]");
                }
            }.resolve(trustedPeer);
        }
    }

    private void updateDeveloperOptionsVisibility(boolean developerModeEnabled) {
        if (debugDashboardPreference == null)
            return;
        final boolean enabled = developerModeEnabled || BuildConfig.DEBUG;
        debugDashboardPreference.setEnabled(enabled);
        debugDashboardPreference.setSelectable(enabled);
        debugDashboardPreference.setSummary(enabled
                ? getString(R.string.preferences_debug_dashboard_summary)
                : getString(R.string.preferences_debug_dashboard_locked));
    }

}
