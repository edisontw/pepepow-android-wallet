package de.schildbach.wallet.data

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.api.ExplorerApiClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ExplorerDataViewModel(application: Application) : AndroidViewModel(application) {
    private val explorerClient = ExplorerApiClient()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateLiveData = MutableLiveData<ExplorerDataState>()

    fun getState(): LiveData<ExplorerDataState> = stateLiveData

    fun refresh(address: String) {
        if (address.isEmpty()) return
        val current = stateLiveData.value
        if (current != null && current.address == address && current.isFresh()) {
            return
        }
        executor.execute {
            val height = explorerClient.fetchBlockCount()
            val balance = explorerClient.fetchBalance(address)
            val state = ExplorerDataState(
                address = address,
                explorerHeight = height,
                balance = balance,
                timestamp = SystemClock.elapsedRealtime(),
                errorMessage = if (height == null || balance == null) "error" else null
            )
            stateLiveData.postValue(state)
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdownNow()
    }
}
