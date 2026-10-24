package ai.enthral.sentinel.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.domain.Alert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val alerts: List<Alert>) : HomeUiState
    data class Error(val failure: ApiResult<Nothing>) : HomeUiState
}

class HomeViewModel(private val repo: AlertsRepository) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = HomeUiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = repo.recent()) {
                is ApiResult.Success -> HomeUiState.Content(r.value)
                is ApiResult.HttpError -> HomeUiState.Error(r)
                is ApiResult.NetworkError -> HomeUiState.Error(r)
            }
        }
    }

    companion object {
        fun factory(repo: AlertsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repo) }
        }
    }
}
