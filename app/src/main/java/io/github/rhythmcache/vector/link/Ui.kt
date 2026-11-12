package io.github.rhythmcache.vector.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Gate { Loading, NoRoot, NoNodes, Ready }

data class UiState(
    val gate: Gate = Gate.Loading,
    val snap: Snapshot? = null,
    val error: String? = null,
)

class VectorViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Called from MainActivity inside repeatOnLifecycle(STARTED): polls only while visible. */
    suspend fun pollLoop() {
        while (true) {
            refresh()
            delay(1500)
        }
    }

    fun retry() {
        viewModelScope.launch {
            VectorRepo.resetShell()
            _state.update { UiState(Gate.Loading) }
            refresh()
        }
    }

    private suspend fun refresh() = mutex.withLock {
        if (_state.value.gate == Gate.NoRoot) return
        if (_state.value.gate != Gate.Ready) {
            if (!VectorRepo.hasRoot()) {
                _state.update { it.copy(gate = Gate.NoRoot) }
                return
            }
            if (!VectorRepo.hasNodes()) {
                _state.update { it.copy(gate = Gate.NoNodes) }
                return
            }
        } else if (!VectorRepo.hasNodes()) {
            _state.update { it.copy(gate = Gate.NoNodes) }
            return
        }
        val snap = VectorRepo.read()
        _state.update { it.copy(gate = Gate.Ready, snap = snap) }
    }

    /** value can be "80 75" for the dual-value charge_stop_pct syntax. */
    fun set(node: String, value: String) {
        viewModelScope.launch {
            // optimistic update so the UI doesn't snap back before the next poll
            value.toIntOrNull()?.let { v ->
                _state.update { s ->
                    s.snap?.let { s.copy(snap = it.copy(nodes = it.nodes + (node to v))) } ?: s
                }
            }
            val ok = VectorRepo.write(node, value)
            _state.update { it.copy(error = if (ok) null else "Write failed: $node = $value") }
            refresh()
        }
    }

    fun set(node: String, value: Int) = set(node, value.toString())
    fun set(node: String, on: Boolean) = set(node, if (on) 1 else 0)
}
