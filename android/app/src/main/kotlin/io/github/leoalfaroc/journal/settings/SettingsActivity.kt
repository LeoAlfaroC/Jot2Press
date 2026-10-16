package io.github.leoalfaroc.journal.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.leoalfaroc.journal.AppContainer
import io.github.leoalfaroc.journal.JournalApp
import io.github.leoalfaroc.journal.R
import io.github.leoalfaroc.journal.queue.QueueScreen
import io.github.leoalfaroc.journal.queue.QueueUi
import io.github.leoalfaroc.journal.queue.queueUi
import io.github.leoalfaroc.journal.ui.JournalTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Serves the settings screen and the queue screen. Changes run in the app's scope, so that leaving does not cut one short. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val model = SettingsModel(container)
    private val scope = container.scope

    /** Null until the stored values have been read. */
    val settings: StateFlow<SettingsUi?> = model.ui
        .filter { it.form.revision > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val queue: StateFlow<QueueUi?> = combine(container.queue.observe(), container.settings.flow) { entries, settings ->
        queueUi(entries, settings, container.clock, container.onUnmeteredNetwork())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        scope.launch { model.load() }
    }

    fun actions(openQueue: () -> Unit) = SettingsActions(
        openQueue = openQueue,
        dismissCategoryNotice = { container.settingsWork.run { model.dismissCategoryNotice() } },
        editSiteUrl = model::editSiteUrl,
        editUsername = model::editUsername,
        editPassword = model::editPassword,
        testConnection = { scope.launch { model.testConnection() } },
        edit = model::edit,
        cancel = model::cancel,
        save = { container.settingsWork.run { model.save() } },
        editGrace = { container.settingsWork.run { model.editGrace(it) } },
        setWifiOnly = { container.settingsWork.run { model.setWifiOnly(it) } },
        chooseCategory = { container.settingsWork.run { model.chooseCategory(it) } },
        setPostStatus = { container.settingsWork.run { model.setPostStatus(it) } },
        setTitleLanguage = { container.settingsWork.run { model.setTitleLanguage(it) } },
        editPattern = { container.settingsWork.run { model.editPattern(it) } },
    )

    fun retry(id: String) {
        scope.launch {
            container.queue.retry(id)
            container.scheduler.sync()
        }
    }

    fun delete(id: String) {
        scope.launch { container.queue.delete(id) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel((this[APPLICATION_KEY] as JournalApp).container) }
        }
    }
}

/** Opened from the launcher icon, the widget and the capture dialog's notice. The queue screen is one step in; back returns here. */
class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory }

    companion object {
        /** Starts on the queue screen, where entries that could not be sent are retried or deleted. */
        const val OPEN_QUEUE = "io.github.leoalfaroc.journal.OPEN_QUEUE"

        fun queueIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java).putExtra(OPEN_QUEUE, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JournalTheme {
                var onQueue by rememberSaveable { mutableStateOf(intent.getBooleanExtra(OPEN_QUEUE, false)) }
                if (onQueue) {
                    BackHandler { onQueue = false }
                    val ui by viewModel.queue.collectAsStateWithLifecycle()
                    ui?.let { QueueScreen(it, onRetry = viewModel::retry, onDelete = viewModel::delete, onBack = { onQueue = false }) }
                } else {
                    val ui by viewModel.settings.collectAsStateWithLifecycle()
                    val actions = remember { viewModel.actions(openQueue = { onQueue = true }) }
                    ui?.let { SettingsScreen(stringResource(R.string.app_name), it, actions) }
                }
            }
        }
    }
}
