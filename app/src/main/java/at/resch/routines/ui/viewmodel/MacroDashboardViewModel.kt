package at.resch.routines.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import at.resch.routines.data.MacroRepository
import at.resch.routines.domain.model.MacroScript
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI-State für das Dashboard. */
sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data object Empty : DashboardUiState
    data class Success(val macros: List<MacroScript>) : DashboardUiState
}

/**
 * ViewModel für den MacroDashboard-Screen.
 *
 * Exponiert [uiState] als StateFlow; Toggle-Aktionen werden direkt ans Repository
 * delegiert — keine Business-Logik hier, nur enabled-Flip auf dem Domain-Objekt.
 */
class MacroDashboardViewModel(
    private val repository: MacroRepository
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> =
        repository.observeAll()
            .map { list ->
                if (list.isEmpty()) DashboardUiState.Empty
                else DashboardUiState.Success(list)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState.Loading
            )

    /**
     * Flippt [MacroScript.enabled] und persistiert das Makro via [MacroRepository.save].
     * Kein JSON-Handling hier — das Repository serialisiert das Domain-Objekt.
     */
    fun toggleEnabled(macro: MacroScript) {
        viewModelScope.launch {
            repository.save(macro.copy(enabled = !macro.enabled))
        }
    }

    /** Factory für manuelles DI (kein Hilt). Erhält das Repository von der Activity/Application. */
    class Factory(private val repository: MacroRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MacroDashboardViewModel(repository) as T
    }
}
