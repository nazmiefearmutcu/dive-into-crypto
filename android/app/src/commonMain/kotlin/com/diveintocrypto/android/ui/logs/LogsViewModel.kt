package com.diveintocrypto.android.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diveintocrypto.android.AppContainer
import com.diveintocrypto.android.data.binance.NetworkLog
import com.diveintocrypto.android.data.binance.NetworkLogEntry
import com.diveintocrypto.android.data.binance.NetworkLogKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Network Log — replaced the paper-bot event log.
 *
 * Listens to the [NetworkLog] singleton's real-time stream; accumulates a
 * [NetworkLogEntry] for each HTTP (REST + WS) call. UI:
 *   - text filter (substring against host/path/method)
 *   - status filter (2xx / 4xx / 5xx / error)
 *   - "Clear" button → [NetworkLog.clear]
 */
class LogsViewModel(@Suppress("unused") container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(LogsUiState())
    val ui: StateFlow<LogsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            NetworkLog.entries.collect { entries ->
                _ui.update { it.copy(entries = entries) }
            }
        }
    }

    fun setFilterText(s: String) {
        _ui.update { it.copy(filterText = s) }
    }

    fun toggleStatusFilter(filter: StatusFilter) {
        _ui.update {
            val next = it.statusFilters.toMutableSet()
            if (filter in next) next.remove(filter) else next.add(filter)
            it.copy(statusFilters = if (next.isEmpty()) ALL_FILTERS else next)
        }
    }

    fun clearAll() = NetworkLog.clear()

    companion object {
        val ALL_FILTERS: Set<StatusFilter> = StatusFilter.values().toSet()
    }
}

enum class StatusFilter(val label: String) {
    SUCCESS("2xx"),
    CLIENT_ERROR("4xx"),
    SERVER_ERROR("5xx"),
    NETWORK_ERROR("ERR");

    fun matches(entry: NetworkLogEntry): Boolean = when (this) {
        SUCCESS -> entry.status in 200..299
        CLIENT_ERROR -> entry.status in 400..499
        SERVER_ERROR -> entry.status in 500..599
        NETWORK_ERROR -> entry.status < 0
    }
}

data class LogsUiState(
    val filterText: String = "",
    val statusFilters: Set<StatusFilter> = LogsViewModel.ALL_FILTERS,
    val entries: List<NetworkLogEntry> = emptyList(),
) {
    /** Filter applied + most-recent first. */
    val visible: List<NetworkLogEntry>
        get() {
            val q = filterText.trim().lowercase()
            return entries
                .asReversed()
                .filter { e ->
                    if (statusFilters.none { it.matches(e) }) return@filter false
                    if (q.isEmpty()) return@filter true
                    e.host.lowercase().contains(q) ||
                        e.path.lowercase().contains(q) ||
                        e.method.lowercase().contains(q)
                }
        }

    val countREST: Int get() = entries.count { it.kind == NetworkLogKind.REST }
    val countWS: Int get() = entries.count { it.kind == NetworkLogKind.WS }
}
