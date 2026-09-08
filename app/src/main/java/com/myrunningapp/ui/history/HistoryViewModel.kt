package com.myrunningapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.repository.RunRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(repository: RunRepository) : ViewModel() {
    val runs = repository.runs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
