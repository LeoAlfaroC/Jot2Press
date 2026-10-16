package io.github.leoalfaroc.journal.capture

import io.github.leoalfaroc.journal.Trouble
import io.github.leoalfaroc.journal.data.Queue
import io.github.leoalfaroc.journal.data.SettingsStore
import io.github.leoalfaroc.journal.data.day
import io.github.leoalfaroc.journal.data.time
import io.github.leoalfaroc.journal.send.Scheduler
import io.github.leoalfaroc.journal.trouble
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate

/** The line about the most recent entry, shown while that entry is in its grace window. */
data class LastEntry(
    val id: String,
    /** `HH:mm`. */
    val time: String,
    /** The start of its text, on one line. */
    val excerpt: String,
    val confirmingDelete: Boolean = false,
)

/** Everything the capture dialog shows. */
data class CaptureUi(
    /** False until the draft has been read: the dialog draws nothing before that. */
    val ready: Boolean = false,
    val text: String = "",
    /** Goes up each time the model, not the user, replaces the text: the editor then starts again from `text`. */
    val revision: Int = 0,
    /** The day the entry will be added to. */
    val day: LocalDate,
    /** The day of the entry in the editor, when it was taken back from its grace window. */
    val entryDay: LocalDate? = null,
    val confirmingDiscard: Boolean = false,
    val lastEntry: LastEntry? = null,
    /** Epoch milliseconds of the next moment at which `refresh` changes something: midnight, or the end of a grace window. */
    val nextChangeAt: Long? = null,
    /** The dialog is done and should close. */
    val closed: Boolean = false,
    /** What the notice on top asks the user to fix, or null for no notice. */
    val trouble: Trouble? = null,
) {
    val hasContent: Boolean get() = text.isNotBlank()
    val canSend: Boolean get() = hasContent && !confirmingDiscard
    val canEditLast: Boolean get() = lastEntry != null && !hasContent
}

/**
 * The capture dialog without its screen. One instance lives as long as one opening of the dialog.
 * `type` answers at once; everything that touches the queue suspends, one call at a time.
 */
class CaptureModel(
    private val queue: Queue,
    private val settings: SettingsStore,
    private val scheduler: Scheduler,
    private val clock: Clock,
) {
    private val state = MutableStateFlow(CaptureUi(day = LocalDate.now(clock)))
    val ui: StateFlow<CaptureUi> = state.asStateFlow()

    private val mutex = Mutex()

    /** What needs fixing, as the queue and the settings change while the dialog is open. */
    val trouble: Flow<Trouble?> = combine(queue.observe(), settings.flow) { entries, settings ->
        trouble(entries, settings, clock.millis())
    }.distinctUntilChanged()

    /** Loads the draft, if one was left. */
    suspend fun open() = mutex.withLock {
        val draft = queue.draft()
        state.update { it.copy(ready = true, text = draft?.text.orEmpty(), revision = it.revision + 1, entryDay = draft?.day) }
        look()
    }

    fun type(text: String) = state.update {
        // Emptying the editor lets go of an entry taken back from its grace window: what is typed next is a new entry.
        if (text.isBlank()) it.copy(text = text, entryDay = null, day = LocalDate.now(clock)) else it.copy(text = text)
    }

    /** Keeps the text as the draft. Called shortly after typing stops, and when the dialog goes away. */
    suspend fun saveDraft() = mutex.withLock { save() }

    /** Stores the entry and closes the dialog. */
    suspend fun send() = mutex.withLock {
        if (!state.value.canSend || state.value.closed) return@withLock
        save()
        queue.send(settings.get().graceSeconds)
        state.update { it.copy(closed = true) }
        scheduler.sync()
    }

    fun askDiscard() = state.update {
        if (it.hasContent) it.copy(confirmingDiscard = true, lastEntry = it.lastEntry?.copy(confirmingDelete = false)) else it
    }

    /** `Keep`, or 4 seconds without confirming: back to normal, for either confirmation. */
    fun keep() = state.update { it.copy(confirmingDiscard = false, lastEntry = it.lastEntry?.copy(confirmingDelete = false)) }

    suspend fun confirmDiscard() = mutex.withLock {
        if (!state.value.confirmingDiscard) return@withLock
        queue.discardDraft()
        state.update { it.copy(text = "", entryDay = null, confirmingDiscard = false, closed = true) }
    }

    /** Looks again at the day and at the most recent entry. Called when `nextChangeAt` arrives and when the dialog returns. */
    suspend fun refresh() = mutex.withLock { look() }

    /** Takes the most recent entry back into the editor. Only while the editor is empty. */
    suspend fun editLast() = mutex.withLock {
        val last = state.value.lastEntry
        if (last == null || !state.value.canEditLast) return@withLock
        save()
        val entry = queue.takeBack(last.id)
        if (entry != null) {
            state.update {
                // The editor was empty when this began: what it holds now was typed meanwhile, and stays, after the entry's text.
                val typedMeanwhile = it.text.takeIf(String::isNotBlank).orEmpty()
                it.copy(text = entry.text + typedMeanwhile, revision = it.revision + 1, entryDay = entry.day)
            }
        }
        look()
    }

    fun askDeleteLast() = state.update {
        it.copy(confirmingDiscard = false, lastEntry = it.lastEntry?.copy(confirmingDelete = true))
    }

    suspend fun confirmDeleteLast() = mutex.withLock {
        val last = state.value.lastEntry
        if (last == null || !last.confirmingDelete) return@withLock
        queue.delete(last.id)
        look()
    }

    private suspend fun save() {
        val now = state.value
        if (!now.ready || now.closed) return
        // Without a day of its own, the editor holds no entry taken back: one that is still stored has been let go of.
        queue.saveDraft(now.text, afresh = now.entryDay == null)
    }

    private suspend fun look() {
        val last = queue.latestInGrace()
        val today = LocalDate.now(clock)
        val midnight = today.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        state.update { ui ->
            ui.copy(
                day = ui.entryDay ?: today,
                lastEntry = last?.let {
                    val confirming = ui.lastEntry?.id == it.id && ui.lastEntry.confirmingDelete
                    LastEntry(it.id, it.time.orEmpty(), excerpt(it.text), confirming)
                },
                nextChangeAt = minOf(midnight, last?.graceEndsAt ?: Long.MAX_VALUE),
            )
        }
    }

    private fun excerpt(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(EXCERPT_LENGTH)

    private companion object {
        const val EXCERPT_LENGTH = 120
    }
}
