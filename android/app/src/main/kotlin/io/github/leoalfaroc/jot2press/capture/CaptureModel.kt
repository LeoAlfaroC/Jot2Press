package io.github.leoalfaroc.jot2press.capture

import io.github.leoalfaroc.jot2press.Trouble
import io.github.leoalfaroc.jot2press.data.DraftPhoto
import io.github.leoalfaroc.jot2press.data.MAX_PHOTOS
import io.github.leoalfaroc.jot2press.data.Queue
import io.github.leoalfaroc.jot2press.data.SettingsStore
import io.github.leoalfaroc.jot2press.data.day
import io.github.leoalfaroc.jot2press.data.oneLine
import io.github.leoalfaroc.jot2press.data.time
import io.github.leoalfaroc.jot2press.data.withPhotos
import io.github.leoalfaroc.jot2press.location.Locator
import io.github.leoalfaroc.jot2press.location.Permission
import io.github.leoalfaroc.jot2press.send.Scheduler
import io.github.leoalfaroc.jot2press.trouble
import kotlinx.coroutines.CancellationException
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
    /** The start of its text, on one line. It starts with the number of photos when it has any. */
    val excerpt: String,
    val confirmingDelete: Boolean = false,
)

/** Everything the capture dialog shows. */
data class CaptureUi(
    /** False until the draft has been read: the dialog draws nothing before that. */
    val ready: Boolean = false,
    val text: String = "",
    val photos: List<DraftPhoto> = emptyList(),
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
    /** `Attach location` shows on in settings: the pin is in the bottom row. */
    val showPin: Boolean = false,
    /** The pin: the entry sends where it was written. */
    val pinOn: Boolean = false,
    /** The last attempt to store the editor failed: what it shows is kept on screen only. */
    val unsaved: Boolean = false,
) {
    val hasContent: Boolean get() = text.isNotBlank() || photos.isNotEmpty()
    val canSend: Boolean get() = hasContent && !confirmingDiscard
    val canEditLast: Boolean get() = lastEntry != null && !hasContent
    /** At 10 photos the camera and photos buttons stop working. */
    val canAddPhotos: Boolean get() = photos.size < MAX_PHOTOS
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
    private val locator: Locator,
) {
    private val state = MutableStateFlow(CaptureUi(day = LocalDate.now(clock)))
    val ui: StateFlow<CaptureUi> = state.asStateFlow()

    private val mutex = Mutex()

    /** The dialog is in sight: only then does it listen for positions. */
    private var inSight = false

    /** What needs fixing, as the queue and the settings change while the dialog is open. */
    val trouble: Flow<Trouble?> = combine(queue.observe(), settings.flow) { entries, settings ->
        trouble(entries, settings, clock.millis())
    }.distinctUntilChanged()

    /** Loads the draft, if one was left, and the pin as it was last left. */
    suspend fun open() = mutex.withLock {
        val draft = queue.draft()
        val photos = draft?.let { queue.photosOf(it.id) }.orEmpty().map { DraftPhoto(it.id, it.file) }
        val pinOn = settings.get().pinOn
        state.update { it.copy(ready = true, text = draft?.text.orEmpty(), photos = photos, revision = it.revision + 1, entryDay = draft?.day, pinOn = pinOn) }
        locator.opened()
        readPin()
        look()
    }

    fun type(text: String) = state.update { letGoIfEmpty(it.copy(text = text)) }

    /** An editor emptied of text and photos lets go of an entry taken back from its grace window: what comes next is a new entry. */
    private fun letGoIfEmpty(ui: CaptureUi): CaptureUi =
        if (ui.hasContent) ui else ui.copy(entryDay = null, day = LocalDate.now(clock))

    /** Photos whose copies were just made. Kept at once, so the copies are never left without a row. */
    suspend fun addPhotos(photos: List<DraftPhoto>) = mutex.withLock {
        if (photos.isEmpty()) return@withLock
        // A closed dialog stores nothing more: copies that arrive after it have no row, and nobody else will delete them.
        if (state.value.closed) return@withLock queue.forget(photos)
        state.update { it.copy(photos = (it.photos + photos).take(MAX_PHOTOS)) }
        save()
        queue.forget(photos.filterNot { it in state.value.photos })
    }

    suspend fun removePhoto(id: String) = mutex.withLock {
        state.update { letGoIfEmpty(it.copy(photos = it.photos.filterNot { photo -> photo.id == id })) }
        save()
    }

    /** At once, so that a `Send` that follows sends with what the pin shows. `rememberPin` stores it. */
    fun togglePin() = state.update { if (it.showPin && !it.closed) it.copy(pinOn = !it.pinOn) else it }

    /** Keeps the pin's state for the next dialog, and listens or stops listening with it. */
    suspend fun rememberPin() = mutex.withLock {
        settings.setPinOn(state.value.pinOn)
        listen()
    }

    /** The dialog came into sight, or left it. It listens for positions only while in sight. */
    suspend fun shown(on: Boolean) = mutex.withLock {
        inSight = on
        listen()
    }

    /** Keeps the text as the draft. Called shortly after typing stops, and when the dialog goes away. */
    suspend fun saveDraft() = mutex.withLock { save() }

    /** Stores the entry, with its position when the pin is on, and closes the dialog. */
    suspend fun send() = mutex.withLock {
        val now = state.value
        if (!now.canSend || now.closed) return@withLock
        // What could not be stored cannot be sent: the dialog stays open with it.
        if (!save()) return@withLock
        val attach = now.showPin && now.pinOn
        // An entry sent again after Edit keeps the position it has, and no search resumes for it.
        val searching = attach && queue.draft()?.lat == null
        val pick = if (searching) locator.pick() else null
        val sent = queue.send(settings.get().graceSeconds, attachLocation = attach, position = pick?.fix)
        // Before the dialog closes: Android lets the search's service start only while the dialog is in sight.
        if (sent != null && searching) locator.follow(sent, pick)
        state.update { it.copy(closed = true) }
        listen()
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
        state.update { it.copy(text = "", photos = emptyList(), entryDay = null, confirmingDiscard = false, closed = true) }
        listen()
    }

    /** Looks again at the day, the most recent entry and whether the pin shows. Called when `nextChangeAt` arrives and when the dialog returns. */
    suspend fun refresh() = mutex.withLock {
        look()
        readPin()
    }

    /** Takes the most recent entry back into the editor. Only while the editor is empty. */
    suspend fun editLast() = mutex.withLock {
        val last = state.value.lastEntry
        if (last == null || !state.value.canEditLast) return@withLock
        save()
        val entry = queue.takeBack(last.id)
        if (entry != null) {
            val photos = queue.photosOf(entry.id).map { DraftPhoto(it.id, it.file) }
            var overflow = emptyList<DraftPhoto>()
            state.update {
                // The editor was empty when this began: what it holds now was typed meanwhile, and stays, after the entry's text.
                val typedMeanwhile = it.text.takeIf(String::isNotBlank).orEmpty()
                it.copy(text = entry.text + typedMeanwhile, photos = (photos + it.photos).take(MAX_PHOTOS).also { kept -> overflow = it.photos.filterNot { p -> p in kept } }, revision = it.revision + 1, entryDay = entry.day)
            }
            queue.forget(overflow)
            // The pin takes the entry's state, which becomes the remembered one.
            state.update { it.copy(pinOn = entry.attachLocation) }
            settings.setPinOn(entry.attachLocation)
        }
        look()
        listen()
    }

    fun askDeleteLast() = state.update {
        it.copy(confirmingDiscard = false, lastEntry = it.lastEntry?.copy(confirmingDelete = true))
    }

    suspend fun confirmDeleteLast() = mutex.withLock {
        val last = state.value.lastEntry
        if (last == null || !last.confirmingDelete) return@withLock
        // Once its window has ended the entry is the sender's: the line then goes, and the entry is sent.
        queue.deleteInGrace(last.id)
        look()
    }

    /** Stores the editor as the draft. False when the storage failed: the dialog then says so, instead of losing the text. */
    private suspend fun save(): Boolean {
        val now = state.value
        if (!now.ready || now.closed) return true
        val stored = try {
            // Without a day of its own, the editor holds no entry taken back: one that is still stored has been let go of.
            queue.saveDraft(now.text, now.photos, afresh = now.entryDay == null)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        state.update { it.copy(unsaved = !stored) }
        return stored
    }

    /** Whether the pin shows: `Attach location` on, and a location permission held. Either can change while the dialog is away. */
    private suspend fun readPin() {
        val shown = settings.get().attachLocation && locator.permission() != Permission.NONE
        state.update { it.copy(showPin = shown) }
        listen()
    }

    private suspend fun listen() {
        val ui = state.value
        locator.listen(this, inSight && !ui.closed && ui.showPin && ui.pinOn)
    }

    private suspend fun look() {
        val last = queue.latestInGrace()
        val count = last?.let { queue.photosOf(it.id).size } ?: 0
        val today = LocalDate.now(clock)
        val midnight = today.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        state.update { ui ->
            ui.copy(
                day = ui.entryDay ?: today,
                lastEntry = last?.let {
                    val confirming = ui.lastEntry?.id == it.id && ui.lastEntry.confirmingDelete
                    LastEntry(it.id, it.time.orEmpty(), withPhotos(count, it.oneLine.take(EXCERPT_LENGTH)), confirming)
                },
                nextChangeAt = minOf(midnight, last?.graceEndsAt ?: Long.MAX_VALUE),
            )
        }
    }

    private companion object {
        const val EXCERPT_LENGTH = 120
    }
}
