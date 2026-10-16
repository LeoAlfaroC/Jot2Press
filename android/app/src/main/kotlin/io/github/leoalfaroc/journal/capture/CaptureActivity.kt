package io.github.leoalfaroc.journal.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import io.github.leoalfaroc.journal.InOrder
import io.github.leoalfaroc.journal.JournalApp
import io.github.leoalfaroc.journal.Trouble
import io.github.leoalfaroc.journal.settings.SettingsActivity
import io.github.leoalfaroc.journal.ui.JournalTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keeps the dialog's model across rotation. What the model does to the queue is handed to `work`, which outlives
 * the dialog and is shared by every opening of it: a `Send` or a draft being saved finishes after the dialog has
 * closed, and before the next dialog reads the draft.
 */
class CaptureViewModel(private val model: CaptureModel, private val work: InOrder) : ViewModel() {
    val ui = model.ui

    /** Kept apart from `ui`, which must answer typing at once: the notice can follow a moment later. */
    val trouble: StateFlow<Trouble?> = model.trouble.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var saving: Job? = null

    /** [fix] and [review] leave the dialog for settings and for the queue. */
    fun actions(fix: () -> Unit, review: () -> Unit) = CaptureActions(
        type = { text ->
            model.type(text)
            saving?.cancel()
            // On the main thread, like everything that cancels it: once cancelled, it hands nothing over.
            saving = viewModelScope.launch {
                delay(SAVE_AFTER_MILLIS)
                work.run { model.saveDraft() }
            }
        },
        send = {
            saving?.cancel()
            work.run { model.send() }
        },
        askDiscard = model::askDiscard,
        keep = model::keep,
        confirmDiscard = {
            saving?.cancel()
            work.run { model.confirmDiscard() }
        },
        editLast = { work.run { model.editLast() } },
        askDeleteLast = model::askDeleteLast,
        confirmDeleteLast = { work.run { model.confirmDeleteLast() } },
        refresh = { refresh() },
        fix = fix,
        review = review,
    )

    init {
        work.run { model.open() }
    }

    fun refresh() {
        work.run { model.refresh() }
    }

    /** The dialog is going away: what is in the editor is kept now, not when typing next pauses. */
    fun saveNow() {
        saving?.cancel()
        work.run { model.saveDraft() }
    }

    companion object {
        /** The draft is saved this long after the last key. */
        const val SAVE_AFTER_MILLIS = 300L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as JournalApp).container
                CaptureViewModel(
                    CaptureModel(container.queue, container.settings, container.scheduler, container.clock),
                    container.captureWork,
                )
            }
        }
    }
}

/** The capture dialog: a translucent window over the home screen. Back, or a tap outside the sheet, keeps the draft. */
class CaptureActivity : ComponentActivity() {
    private val viewModel: CaptureViewModel by viewModels { CaptureViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val clock = (application as JournalApp).container.clock
        setContent {
            JournalTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val trouble by viewModel.trouble.collectAsStateWithLifecycle()
                LaunchedEffect(ui.closed) { if (ui.closed) finish() }
                val actions = remember { viewModel.actions(fix = { leaveFor(Intent(this, SettingsActivity::class.java)) }, review = { leaveFor(SettingsActivity.queueIntent(this)) }) }
                CaptureScreen(ui.copy(trouble = trouble), actions, now = remember { { clock.millis() } }, onDismiss = ::finish)
            }
        }
    }

    /** Settings opens in the app's own task, not in the dialog's. Pausing keeps the text as the draft. */
    private fun leaveFor(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onStart() {
        super.onStart()
        // The day or the last entry's grace window may have changed while the dialog was out of sight.
        viewModel.refresh()
    }

    /**
     * Android pauses this dialog before it creates the next one, and may stop it only long after:
     * saving here puts this dialog's draft in line ahead of the next dialog's reading of it.
     */
    override fun onPause() {
        super.onPause()
        viewModel.saveNow()
    }
}
