package io.github.leoalfaroc.jot2press.send

import io.github.leoalfaroc.jot2press.BuildConfig
import io.github.leoalfaroc.jot2press.Titles
import io.github.leoalfaroc.jot2press.data.Credentials
import io.github.leoalfaroc.jot2press.data.Entry
import io.github.leoalfaroc.jot2press.data.PLUGIN_PHOTO_TYPES
import io.github.leoalfaroc.jot2press.data.Photo
import io.github.leoalfaroc.jot2press.data.PhotoFiles
import io.github.leoalfaroc.jot2press.data.Queue
import io.github.leoalfaroc.jot2press.data.Settings
import io.github.leoalfaroc.jot2press.data.SettingsStore
import io.github.leoalfaroc.jot2press.data.SiteStatus
import io.github.leoalfaroc.jot2press.data.UNKNOWN_PHOTO
import io.github.leoalfaroc.jot2press.data.day
import io.github.leoalfaroc.jot2press.data.megabytes
import io.github.leoalfaroc.jot2press.net.ApiResult
import io.github.leoalfaroc.jot2press.net.Coordinates
import io.github.leoalfaroc.jot2press.net.EntryPayload
import io.github.leoalfaroc.jot2press.net.Jot2PressApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Locale

/** What a refusal means for the queue. */
enum class Verdict {
    /** The site rejected the credentials: nothing can be sent until they are fixed. */
    AUTH,

    /** The plugin rejected this entry itself: it fails, and the next one is tried. */
    REJECTED,

    /** Something is wrong on the site, not with the entry: the queue waits and tries again. */
    SERVER_FAULT,
}

/**
 * A 4xx is a rejection of the entry only when the plugin, or WordPress, says so with an error code.
 * Anything else (a 5xx, a redirect, an error page, a missing plugin, a rate limit) is the site's trouble:
 * failing the entry for it would turn an outage into a queue of entries to retry by hand.
 * A 413 is the exception: whoever answers it, it is about the size of this entry, and sending it again
 * would get the same answer and hold every entry behind it.
 */
fun verdict(refused: ApiResult.Refused): Verdict = when {
    refused.status == 403 && refused.code in ENTRY_REFUSALS -> Verdict.REJECTED
    refused.status == 401 || refused.status == 403 -> Verdict.AUTH
    refused.status == 413 -> Verdict.REJECTED
    refused.status in 400..499 && refused.status != 408 && refused.status != 429 &&
        refused.code != null && refused.code != NO_ROUTE -> Verdict.REJECTED
    else -> Verdict.SERVER_FAULT
}

private const val NO_ROUTE = "rest_no_route"
private const val TYPE_NOT_ALLOWED = "photo_type_not_allowed"

/** What a refusal says to the user: the site's own message, unless the plugin does not answer at all. */
fun describe(refused: ApiResult.Refused): String =
    if (refused.code == NO_ROUTE) Sender.PLUGIN_MISSING else refused.message

fun describe(unreachable: ApiResult.Unreachable): String = "Could not reach the site: ${unreachable.reason}"

/** The plugin's 403s about one entry: the account may publish, but not upload its photos or edit its day's post. */
private val ENTRY_REFUSALS = setOf("cannot_upload_photos", "cannot_edit_day_post")

/** Sends the pending entries, one at a time, oldest first. */
class Sender(
    private val queue: Queue,
    private val settings: SettingsStore,
    /** The stored credentials, or null when there are none. */
    private val credentials: suspend () -> Credentials?,
    private val apiFor: (Credentials) -> Jot2PressApi,
    private val files: PhotoFiles,
    /** Makes a photo's copy fit the limit and the types the site accepts: `PhotoEncoder.prepare`. */
    private val prepare: (File, Long, Set<String>) -> Prepared,
    private val deviceLocale: () -> Locale = { Locale.getDefault() },
    /** Whether the phone is on a network that reaches the internet: a site that does not answer then is the site's trouble. */
    private val networkUp: () -> Boolean = { false },
) {
    enum class Outcome {
        /** Nothing is left to send. */
        DONE,

        /** Something is left, and trying again later may send it. */
        RETRY,

        /** Something may be left, and only the user can unblock it. */
        HELD,
    }

    private val oneAtATime = Mutex()

    /** Held while the next entry is picked and while one is deleted: an entry cannot be both. */
    private val claim = Mutex()
    private val inFlight = MutableStateFlow<String?>(null)

    /** The entry being sent at this moment, or null. Its requests may already have reached the site. */
    val sending: StateFlow<String?> = inFlight.asStateFlow()

    /**
     * Deletes an entry from the queue, unless it is the one being sent: deleting that one could no longer
     * keep it off the site. Returns whether it was deleted.
     */
    suspend fun withdraw(id: String): Boolean = claim.withLock {
        if (inFlight.value == id) return@withLock false
        queue.delete(id)
        true
    }

    /**
     * No credentials to send with. After a Save this means they can no longer be decrypted (the Keystore key was lost):
     * the user fixes it the same way as a rejected sign-in, by saving them again.
     */
    private suspend fun unreadable(): Outcome {
        if (settings.get().configured) settings.credentialsRejected(version)
        return Outcome.HELD
    }

    /**
     * The `credentialsVersion` read just before the credentials the current entry is sent with. Read before them, so
     * that a Save that stores new ones after it moves the version on, and their rejection is not recorded against them.
     * Only `run` sets it, one at a time.
     */
    private var version = 0L

    suspend fun run(): Outcome = oneAtATime.withLock { drain() }

    /** What came of one request: go on with this entry, start over with the next one, or stop the run. */
    private sealed interface Step {
        data object Go : Step
        data object Again : Step
        data class Stop(val outcome: Outcome) : Step
    }

    private suspend fun drain(): Outcome {
        // The entries whose photos were uploaded again in this run, after the site said it had lost them.
        val reuploaded = mutableSetOf<String>()
        while (true) {
            // Read again for every entry: `Save` may have stored other credentials since the last one.
            val current = settings.get()
            if (current.authError) return Outcome.HELD
            version = current.credentialsVersion
            val signIn = credentials() ?: return unreadable()
            queue.promoteDue()
            val entry = claim.withLock { queue.nextPending()?.also { inFlight.value = it.id } } ?: return Outcome.DONE
            try {
                val api = apiFor(signIn)
                val photos = queue.photosOf(entry.id)
                var step = sendPhotos(api, signIn, entry, photos)
                if (step == Step.Go) step = sendEntry(api, signIn, entry, photos, reuploaded)
                if (step is Step.Stop) return step.outcome
            } finally {
                inFlight.value = null
            }
        }
    }

    /** Uploads the photos the site does not have yet, in order. Each one counts as a request of the entry. */
    private suspend fun sendPhotos(api: Jot2PressApi, signIn: Credentials, entry: Entry, photos: List<Photo>): Step {
        val waiting = photos.filterNot { it.uploaded }
        if (waiting.isEmpty()) return Step.Go
        val stored = settings.get()
        val (limit, types) = stored.maxUploadBytes?.let { it to stored.photoTypes } ?: when (val status = api.status()) {
            is ApiResult.Ok -> status.value.also { settings.setUploadRules(it) }.let { it.maxUploadBytes to it.photoTypes }
            is ApiResult.Unreachable -> return unreachable(status, entry)
            is ApiResult.Refused -> return refused(status, signIn, entry)
        }
        val accepted = types ?: PLUGIN_PHOTO_TYPES
        for (photo in waiting) {
            val number = photos.indexOf(photo) + 1
            val ready = try {
                withContext(Dispatchers.IO) { prepare(files.file(photo.file), limit, accepted) }
            } catch (e: IOException) {
                // The phone could not write what it would send (no space): not the photo's fault.
                return Step.Stop(Outcome.RETRY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // UnreadablePhoto, or whatever the platform's image code throws: a photo it cannot handle is the entry's
                // problem, not the queue's.
                queue.markFailed(entry.id, "Photo $number could not be read on the phone.")
                return Step.Again
            }
            val size = ready.file.length()
            val result = try {
                api.uploadPhoto(photo.id, ready.file, ready.mediaType)
            } finally {
                if (ready.temporary) ready.file.delete()
            }
            when (result) {
                is ApiResult.Ok -> queue.markUploaded(photo.id)
                is ApiResult.Unreachable -> return unreachable(result, entry)
                is ApiResult.Refused -> return when {
                    result.code == TYPE_NOT_ALLOWED && noLongerTaken(api, ready.mediaType) -> Step.Again
                    result.status == 413 -> refused(result, signIn, entry, tooLarge(api, size, limit, "Photo $number of ${photos.size}"))
                    else -> refused(result, signIn, entry)
                }
            }
        }
        return Step.Go
    }

    /** What the site accepts now, read again and kept; null when it does not say. */
    private suspend fun freshRules(api: Jot2PressApi): SiteStatus? =
        (api.status() as? ApiResult.Ok)?.value?.also { settings.setUploadRules(it) }

    /**
     * Whether the site no longer takes [mediaType], a type it took. The entry then starts over, which makes the photo
     * a JPEG. Only once: the next refusal of the kind fails the entry.
     */
    private suspend fun noLongerTaken(api: Jot2PressApi, mediaType: String): Boolean {
        val now = freshRules(api) ?: return false
        return mediaType !in (now.photoTypes ?: PLUGIN_PHOTO_TYPES)
    }

    /**
     * A photo of [size] bytes was refused as too large: WordPress reported a limit the web server in front of it does
     * not allow. Only the refusal says how much less it takes: half of what it refused, so that Retry sends a smaller
     * photo, and a few Retries reach even a far lower limit. A tested connection puts WordPress's figure back.
     * Returns what the entry fails with.
     */
    private suspend fun tooLarge(api: Jot2PressApi, size: Long, limit: Long, photo: String): String {
        val reported = freshRules(api)?.maxUploadBytes ?: limit
        settings.setMaxUploadBytes(minOf(reported, size / 2))
        return "$photo is larger than the site accepts. " +
            "The web server's upload limit is lower than the ${megabytes(reported)} WordPress reports."
    }

    private suspend fun sendEntry(
        api: Jot2PressApi,
        signIn: Credentials,
        entry: Entry,
        photos: List<Photo>,
        reuploaded: MutableSet<String>,
    ): Step =
        when (val result = api.sendEntry(payload(entry, settings.get(), photos))) {
            is ApiResult.Ok -> {
                // The fallback is recorded first: if the process dies between the two, the resend finds the entry
                // still pending and the plugin answers it again.
                result.value.categoryFallback?.let { settings.categoryFellBack(it) }
                queue.markSent(entry.id)
                Step.Again
            }
            is ApiResult.Unreachable -> unreachable(result, entry)
            // The site's cleanup deleted photos uploaded long ago, while the entry waited: they are uploaded again,
            // once per run, so that a site that keeps losing them fails the entry instead of looping.
            is ApiResult.Refused -> if (result.code == UNKNOWN_PHOTO && reuploaded.add(entry.id)) {
                queue.forgetUploads(entry.id)
                Step.Again
            } else {
                refused(result, signIn, entry)
            }
        }

    /**
     * No answer. Without a network that says nothing about the site, so nothing is recorded. With one, the site is
     * the trouble (a lapsed certificate, a domain gone): it is kept on the entry like a server error, so that an entry
     * left waiting for days shows stuck, and why.
     */
    private suspend fun unreachable(result: ApiResult.Unreachable, entry: Entry): Step {
        if (networkUp()) queue.recordServerError(entry.id, describe(result))
        return Step.Stop(Outcome.RETRY)
    }

    /** What a refusal of any request of [entry] does to the queue. [message] replaces the site's own, when given. */
    private suspend fun refused(result: ApiResult.Refused, signIn: Credentials, entry: Entry, message: String = result.message): Step =
        when (verdict(result)) {
            Verdict.AUTH -> {
                // Credentials replaced while the request was on its way, or while the rejection is being recorded,
                // are not the ones that were rejected: the entry is tried again with them.
                if (credentials() != signIn || !settings.credentialsRejected(version)) {
                    Step.Again
                } else {
                    queue.clearServerError(entry.id)
                    Step.Stop(Outcome.HELD)
                }
            }
            Verdict.REJECTED -> {
                queue.markFailed(entry.id, message, result.code)
                Step.Again
            }
            Verdict.SERVER_FAULT -> {
                queue.recordServerError(entry.id, describe(result))
                Step.Stop(Outcome.RETRY)
            }
        }

    /** The title, the category and the status are the ones set when the entry is sent, not when it was written. */
    private fun payload(entry: Entry, settings: Settings, photos: List<Photo>) = EntryPayload(
        id = entry.id,
        writtenAt = checkNotNull(entry.writtenAt) { "A pending entry has been sent, so it has its time." },
        text = entry.text,
        title = entry.day?.let { Titles.build(settings.titlePattern, settings.titleLanguage, it, deviceLocale()) },
        category = settings.categorySlug,
        postStatus = settings.postStatus.wire,
        photoIds = photos.map { it.id },
        location = locationOf(entry),
    )

    /** Only for an entry whose pin was on: a position kept with the pin off stays on the phone. */
    private fun locationOf(entry: Entry): Coordinates? {
        val lat = entry.lat ?: return null
        val lng = entry.lng ?: return null
        return if (entry.attachLocation) Coordinates(lat, lng) else null
    }

    companion object {
        const val PLUGIN_MISSING = "The ${BuildConfig.PLUGIN_NAME} plugin does not answer on the site. Check that it is installed and active."
    }
}
