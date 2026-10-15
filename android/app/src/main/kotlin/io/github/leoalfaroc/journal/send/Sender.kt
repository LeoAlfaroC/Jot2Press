package io.github.leoalfaroc.journal.send

import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.Queue
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.data.SettingsStore
import io.github.leoalfaroc.journal.data.day
import io.github.leoalfaroc.journal.net.ApiResult
import io.github.leoalfaroc.journal.net.EntryPayload
import io.github.leoalfaroc.journal.net.JournalApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    suspend fun run(): Outcome = oneAtATime.withLock { drain() }

    private suspend fun drain(): Outcome {
        while (true) {
            // Read again for every entry: `Test connection` may have stored other credentials since the last one.
            if (settings.get().authError) return Outcome.HELD
            val signIn = credentials() ?: return Outcome.HELD
            queue.promoteDue()
            val entry = queue.nextPending() ?: return Outcome.DONE
            when (val result = apiFor(signIn).sendEntry(payload(entry, settings.get()))) {
                is ApiResult.Ok -> {
                    queue.markSent(entry.id)
                    result.value.categoryFallback?.let { settings.categoryFellBack(it) }
                }
                // No answer says nothing about the site, so nothing is recorded.
                is ApiResult.Unreachable -> return Outcome.RETRY
                is ApiResult.Refused -> when (verdict(result)) {
                    Verdict.AUTH -> {
                        // Credentials replaced while the request was on its way are not the ones that were rejected:
                        // the entry is tried again with them.
                        if (credentials() != signIn) continue
                        queue.clearServerError(entry.id)
                        settings.setAuthError(true)
                        return Outcome.HELD
                    }
                    Verdict.REJECTED -> queue.markFailed(entry.id, result.message)
                    Verdict.SERVER_FAULT -> {
                        queue.recordServerError(entry.id, describe(result))
                        return Outcome.RETRY
                    }
                }
            }
        }
    }

    /** The title, the category and the status are the ones set when the entry is sent, not when it was written. */
    private fun payload(entry: Entry, settings: Settings) = EntryPayload(
        id = entry.id,
        writtenAt = checkNotNull(entry.writtenAt) { "A pending entry has been sent, so it has its time." },
        text = entry.text,
        title = entry.day?.let { Titles.build(settings.titlePattern, settings.titleLanguage, it, deviceLocale()) },
        category = settings.categorySlug,
        postStatus = settings.postStatus.wire,
    )

    private fun describe(refused: ApiResult.Refused): String =
        if (refused.code == NO_ROUTE) PLUGIN_MISSING else refused.message

    companion object {
        const val PLUGIN_MISSING = "The journal plugin does not answer on the site. Check that it is installed and active."
    }
}
