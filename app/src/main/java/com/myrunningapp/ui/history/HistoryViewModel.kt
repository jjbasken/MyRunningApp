package com.myrunningapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.export.GpxImportException
import com.myrunningapp.data.export.GpxImportFailure
import com.myrunningapp.data.export.RunImporter
import com.myrunningapp.data.repository.RunRepository
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.stats.HistoryStats
import com.myrunningapp.domain.stats.RunTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val runs: List<Run> = emptyList(),
    val thisWeek: RunTotals = RunTotals.EMPTY,
    val allTime: RunTotals = RunTotals.EMPTY,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: RunRepository,
    private val importer: RunImporter,
    private val clock: Clock,
) : ViewModel() {

    /** How an import the user asked for turned out. */
    sealed interface ImportEvent {
        data class Imported(val runId: Long) : ImportEvent
        data class Failed(val failure: GpxImportFailure) : ImportEvent
    }

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * The user's own idea of when a week starts — Sunday in the US, Monday in
     * much of the world — rather than one hard-coded here.
     */
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    /**
     * Totals are recomputed whenever the run list changes, which is also the only
     * time they can go stale: an app left open across midnight on the first day of
     * a week keeps the old week's header until the next run lands. Cheap to
     * accept, and the run that would notice refreshes it anyway.
     */
    val uiState = repository.runs
        .map { runs ->
            HistoryUiState(
                loading = false,
                runs = runs,
                thisWeek = HistoryStats.weekTotals(runs, clock.instant(), zone, firstDayOfWeek),
                allTime = HistoryStats.totals(runs),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun deleteRun(runId: Long) {
        viewModelScope.launch { repository.deleteRun(runId) }
    }

    private val _importing = MutableStateFlow(false)
    /** True while a picked file is being read and saved, so the button cannot be pressed twice. */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importEvents = MutableSharedFlow<ImportEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val importEvents = _importEvents.asSharedFlow()

    /**
     * @param readFile reads the picked file's bytes, throwing [GpxImportException]
     *   when it cannot. Handed in rather than a `Uri` so the ViewModel stays free
     *   of the Storage Access Framework, as the export side does.
     */
    fun importGpx(readFile: suspend () -> ByteArray) {
        viewModelScope.launch {
            _importing.value = true
            val event = try {
                ImportEvent.Imported(importer.importGpx(readFile()))
            } catch (e: GpxImportException) {
                ImportEvent.Failed(e.failure)
            } finally {
                _importing.value = false
            }
            _importEvents.emit(event)
        }
    }
}
