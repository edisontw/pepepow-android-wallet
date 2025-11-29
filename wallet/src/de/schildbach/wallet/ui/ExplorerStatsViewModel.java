package de.schildbach.wallet.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.api.ApiStatus;
import de.schildbach.wallet.data.api.ExplorerApiStatsRepository;
import de.schildbach.wallet.data.api.NetworkStats;

public class ExplorerStatsViewModel extends AndroidViewModel {

    private final ExplorerApiStatsRepository repository;

    public ExplorerStatsViewModel(@NonNull Application application, @NonNull ExplorerApiStatsRepository repository) {
        super(application);
        this.repository = repository;
    }

    public LiveData<ApiStatus> getApiStatus() {
        return repository.getApiStatus();
    }

    public LiveData<NetworkStats> getNetworkStats() {
        return repository.getNetworkStats();
    }

    public void refresh(boolean force) {
        repository.refresh(force);
    }

    public static class Factory implements ViewModelProvider.Factory {
        private final Application application;
        private final ExplorerApiStatsRepository repository;

        public Factory(@NonNull Application application, @NonNull ExplorerApiStatsRepository repository) {
            this.application = application;
            this.repository = repository;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(ExplorerStatsViewModel.class)) {
                return (T) new ExplorerStatsViewModel(application, repository);
            }
            throw new IllegalArgumentException("Unknown ViewModel class");
        }
    }
}
