package io.github.leoalfaroc.journal.send

import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.Photo
import io.github.leoalfaroc.journal.data.PhotoFiles
import io.github.leoalfaroc.journal.data.Queue
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.data.SettingsStore
import io.github.leoalfaroc.journal.data.day
import io.github.leoalfaroc.journal.net.ApiResult
import io.github.leoalfaroc.journal.net.EntryPayload
import io.github.leoalfaroc.journal.net.JournalApi
import io.github.leoalfaroc.journal.settings.megabytes
import kotlinx.coroutines.Dispatchers
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
    refused.status == 401 || refused.status == 403 -> Verdict.AUTH
    refused.status == 413 -> Verdict.REJECTED
    refused.status in 400..499 && refused.status != 408 && refused.status != 429 &&
        refused.code != null && refused.code != NO_ROUTE -> Verdict.REJECTED
    else -> Verdict.SERVER_FAULT
}

private const val NO_ROUTE = "rest_no_route"

/** Sends the pending entries, one at a time, oldest first. */
class Sender(
    private val queue: Queue,
    private val settings: SettingsStore,
    /** The stored credentials, or null when there are none. */
    private val credentials: suspend () -> Credentials?,
    private val apiFor: (Credentials) -> JournalApi,
    private val files: PhotoFiles,
    /** Makes a photo's copy fit the limit: `PhotoEncoder.prepare`. */
    private val prepare: (File, Long) -> Prepared,
    private val deviceLocale: () -> Locale = { Locale.getDefault() },
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

    /**
     * No credentials to send with. After a Save this means they can no longer be decrypted (the Keystore key was lost):
     * the user fixes it the same way as a rejected sign-in, by saving them again.
     */
    private suspend fun unreadable(): Outcome {
        if (settings.get().configured) settings.setAuthError(true)
        return Outcome.HELD
    }

    suspend fun run(): Outcome = oneAtATime.withLock { drain() }

    /** What came of one request: go on with this entry, start over with the next one, or stop the run. */
    private sealed interface Step {
        data object Go : Step
        data object Again : Step
        data class Stop(val outcome: Outcome) : Step
    }

    private suspend fun drain(): Outcome {
        while (true) {
            // Read again for every entry: `Save` may have stored other credentials since the last one.
            if (settings.get().authError) return Outcome.HELD
            val signIn = credentials() ?: return unreadable()
            queue.promoteDue()
            val entry = queue.nextPending() ?: return Outcome.DONE
            val api = apiFor(signIn)
            val photos = queue.photosOf(entry.id)
            var step = sendPhotos(api, signIn, entry, photos)
            if (step == Step.Go) step = sendEntry(api, signIn, entry, photos)
            if (step is Step.Stop) return step.outcome
        }
    }

    /** Uploads the photos the site does not have yet, in order. Each one counts as a request of the entry. */
    private suspend fun sendPhotos(api: JournalApi, signIn: Credentials, entry: Entry, photos: List<Photo>): Step {
        val waiting = photos.filterNot { it.uploaded }
        if (waiting.isEmpty()) return Step.Go
        val limit = settings.get().maxUploadBytes ?: when (val status = api.status()) {
            is ApiResult.Ok -> status.value.maxUploadBytes.also { settings.setMaxUploadBytes(it) }
            is ApiResult.Unreachable -> return Step.Stop(Outcome.RETRY)
            is ApiResult.Refused -> return refused(status, signIn, entry)
        }
        for (photo in waiting) {
            val number = photos.indexOf(photo) + 1
            val ready = try {
                withContext(Dispatchers.IO) { prepare(files.file(photo.file), limit) }
            } catch (e: UnreadablePhoto) {
                queue.markFailed(entry.id, "Photo $number could not be read on the phone.")
                return Step.Again
            } catch (e: IOException) {
                // The phone could not write what it would send (no space): not the photo's fault.
                return Step.Stop(Outcome.RETRY)
            }
            val result = try {
                api.uploadPhoto(photo.id, ready.file, ready.mediaType)
            } finally {
                if (ready.temporary) ready.file.delete()
            }
            when (result) {
                is ApiResult.Ok -> queue.markUploaded(photo.id)
                is ApiResult.Unreachable -> return Step.Stop(Outcome.RETRY)
                is ApiResult.Refused -> {
                    if (result.status != 413) return refused(result, signIn, entry)
                    // WordPress reported a limit the web server in front of it does not allow.
                    (api.status() as? ApiResult.Ok)?.let { settings.setMaxUploadBytes(it.value.maxUploadBytes) }
                    val reported = settings.get().maxUploadBytes ?: limit
                    return refused(
                        result,
                        signIn,
                        entry,
                        "Photo $number of ${photos.size} is larger than the site accepts. " +
                            "The web server's upload limit is lower than the ${megabytes(reported)} WordPress reports.",
                    )
                }
            }
        }
        return Step.Go
    }

    private suspend fun sendEntry(api: JournalApi, signIn: Credentials, entry: Entry, photos: List<Photo>): Step =
        when (val result = api.sendEntry(payload(entry, settings.get(), photos))) {
            is ApiResult.Ok -> {
                // The fallback is recorded first: if the process dies between the two, the resend finds the entry
                // still pending and the plugin answers it again.
                result.value.categoryFallback?.let { settings.categoryFellBack(it) }
                queue.markSent(entry.id)
                Step.Again
            }
            // No answer says nothing about the site, so nothing is recorded.
            is ApiResult.Unreachable -> Step.Stop(Outcome.RETRY)
            is ApiResult.Refused -> refused(result, signIn, entry)
        }

    /** What a refusal of any request of [entry] does to the queue. [message] replaces the site's own, when given. */
    private suspend fun refused(result: ApiResult.Refused, signIn: Credentials, entry: Entry, message: String = result.message): Step =
        when (verdict(result)) {
            Verdict.AUTH -> {
                // Credentials replaced while the request was on its way are not the ones that were rejected:
                // the entry is tried again with them.
                if (credentials() != signIn) {
                    Step.Again
                } else {
                    queue.clearServerError(entry.id)
                    settings.setAuthError(true)
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
    )

    private fun describe(refused: ApiResult.Refused): String =
        if (refused.code == NO_ROUTE) PLUGIN_MISSING else refused.message

    companion object {
        const val PLUGIN_MISSING = "The journal plugin does not answer on the site. Check that it is installed and active."
    }
}
