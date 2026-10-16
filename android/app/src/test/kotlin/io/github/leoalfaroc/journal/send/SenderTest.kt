package io.github.leoalfaroc.journal.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestClock
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.data.Category
import io.github.leoalfaroc.journal.data.CategoryNotice
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.data.PostStatus
import io.github.leoalfaroc.journal.data.Queue
import io.github.leoalfaroc.journal.data.SettingsStore
import io.github.leoalfaroc.journal.data.SiteStatus
import io.github.leoalfaroc.journal.data.isStuck
import io.github.leoalfaroc.journal.data.memoryDb
import io.github.leoalfaroc.journal.net.HttpJournalApi
import io.github.leoalfaroc.journal.net.JournalApi
import io.github.leoalfaroc.journal.net.journalHttpClient
import io.github.leoalfaroc.journal.tempStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SenderTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val clock = TestClock()
    private val db = memoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ids = 0
    private val queue = Queue(db, clock) { "id-${++ids}" }
    private val settings by lazy { SettingsStore(tempStore(folder.root, storeScope)) }
    private val signIn by lazy { Credentials(server.url("").toString(), "leo", "abcd") }
    private var saved: Credentials? = null
    /** Runs when a request is about to leave, after the sender has read the credentials for it. */
    private var whileSending: () -> Unit = {}
    private val sender by lazy {
        saved = signIn
        Sender(queue, settings, credentials = { saved }, apiFor = ::api, deviceLocale = { Locale.FRENCH })
    }

    private fun api(signIn: Credentials): JournalApi {
        whileSending()
        return HttpJournalApi(journalHttpClient(), signIn)
    }

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        server.close()
        db.close()
        storeScope.cancel()
    }

    /** Writes an entry that is ready to send, then moves the clock on by a minute. */
    private suspend fun pending(text: String): Entry {
        queue.saveDraft(text)
        val entry = queue.send(graceSeconds = 0)!!
        clock.advance(Duration.ofMinutes(1))
        return entry
    }

    private fun answer(status: Int, body: String) = server.enqueue(MockResponse.Builder().code(status).body(body).build())
    private fun created() = answer(201, """{"status":"created","post_id":7}""")
    private fun wordPressError(status: Int, code: String, message: String) =
        answer(status, """{"code":"$code","message":"$message","data":{"status":$status}}""")

    private fun basic(signIn: Credentials) = okhttp3.Credentials.basic(signIn.username, signIn.password, Charsets.UTF_8)
    private fun sentBody(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private suspend fun stored(id: String): Entry? = queue.observe().first().firstOrNull { it.id == id }

    @Test
    fun `a confirmed entry leaves the phone`() = runTest {
        val entry = pending("Coffee with Ana")
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertTrue(queue.observe().first().isEmpty())
        val body = sentBody()
        assertEquals(entry.id, body.text("id"))
        assertEquals("2026-10-11T14:32:05-05:00", body.text("written_at"))
        assertEquals("Coffee with Ana", body.text("text"))
        assertEquals("2026-10-11 · Sunday, October 11", body.text("title"))
        assertEquals("private", body.text("post_status"))
        assertFalse("category" in body)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an entry the site already has leaves the phone too`() = runTest {
        pending("Coffee")
        answer(200, """{"status":"duplicate","post_id":7}""")

        assertEquals(Sender.Outcome.DONE, sender.run())
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `entries go one at a time, oldest first`() = runTest {
        pending("First")
        pending("Second")
        pending("Third")
        repeat(3) { created() }

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals(listOf("First", "Second", "Third"), List(3) { sentBody().text("text") })
    }

    @Test
    fun `an entry still in its grace window is not sent until the window ends`() = runTest {
        queue.saveDraft("Coffee")
        queue.send(graceSeconds = 60)

        assertEquals(Sender.Outcome.DONE, sender.run())
        assertEquals(0, server.requestCount)

        clock.advance(Duration.ofSeconds(60))
        created()
        assertEquals(Sender.Outcome.DONE, sender.run())
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `without an answer the entry waits and nothing is recorded`() = runTest {
        val entry = pending("Coffee")
        server.close()

        assertEquals(Sender.Outcome.RETRY, sender.run())
        assertEquals(entry, stored(entry.id))
    }

    @Test
    fun `a server error is kept on the entry, holds the ones behind it, and is cleared when the entry is sent`() = runTest {
        val first = pending("First")
        val second = pending("Second")
        val began = clock.millis()
        answer(500, "<html>Internal Server Error</html>")
        assertEquals(Sender.Outcome.RETRY, sender.run())

        assertEquals(1, server.requestCount)
        assertEquals(EntryState.PENDING, stored(first.id)!!.state)
        assertEquals("The site answered with an error (HTTP 500).", stored(first.id)!!.lastError)
        assertEquals(began, stored(first.id)!!.failingSince)
        assertEquals(second, stored(second.id))

        clock.advance(Duration.ofMinutes(61))
        wordPressError(503, "journal_busy", "The journal is busy. Try again.")
        assertEquals(Sender.Outcome.RETRY, sender.run())
        assertEquals("The journal is busy. Try again.", stored(first.id)!!.lastError)
        assertEquals(began, stored(first.id)!!.failingSince)
        assertTrue(stored(first.id)!!.isStuck(clock.millis()))

        created()
        created()
        assertEquals(Sender.Outcome.DONE, sender.run())
        assertTrue(queue.observe().first().isEmpty())
        repeat(2) { server.takeRequest() }
        assertEquals(listOf("First", "Second"), List(2) { sentBody().text("text") })
    }

    @Test
    fun `a server error stays recorded while there is no answer`() = runTest {
        val entry = pending("Coffee")
        answer(500, "")
        sender.run()
        val failing = stored(entry.id)!!
        server.close()

        assertEquals(Sender.Outcome.RETRY, sender.run())
        assertEquals(failing, stored(entry.id))
    }

    @Test
    fun `a rejected entry fails with the plugin's message and the next one is sent`() = runTest {
        val first = pending("First")
        pending("Second")
        answer(500, "")
        sender.run()
        wordPressError(400, "invalid_text", "text must be a string.")
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        val failed = queue.observe().first().single()
        assertEquals(first.id, failed.id)
        assertEquals(EntryState.FAILED, failed.state)
        assertEquals("text must be a string.", failed.lastError)
        assertNull(failed.failingSince)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an entry too large for the web server fails, whoever says so, and the next one is sent`() = runTest {
        val large = pending("A very long entry")
        pending("Second")
        answer(413, "<html>Request Entity Too Large</html>")
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        val failed = queue.observe().first().single()
        assertEquals(large.id, failed.id)
        assertEquals(EntryState.FAILED, failed.state)
        assertEquals(HttpJournalApi.TOO_LARGE, failed.lastError)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a retried entry is sent before the newer ones that are waiting`() = runTest {
        val first = pending("First")
        wordPressError(400, "invalid_text", "text must be a string.")
        sender.run()
        pending("Second")
        queue.retry(first.id)
        repeat(2) { created() }

        assertEquals(Sender.Outcome.DONE, sender.run())

        server.takeRequest()
        assertEquals(listOf("First", "Second"), List(2) { sentBody().text("text") })
    }

    @Test
    fun `saved credentials that can no longer be read hold the queue as a rejected sign-in`() = runTest {
        pending("Coffee")
        sender
        settings.credentialsSaved()
        saved = null

        assertEquals(Sender.Outcome.HELD, sender.run())

        assertTrue(settings.get().authError)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with no credentials ever saved, the queue waits without an alert`() = runTest {
        pending("Coffee")
        sender
        saved = null

        assertEquals(Sender.Outcome.HELD, sender.run())

        assertFalse(settings.get().authError)
    }

    @Test
    fun `rejected credentials stop everything and keep every entry`() = runTest {
        for (status in listOf(401, 403)) {
            val entry = pending("Coffee $status")
            wordPressError(status, "rest_forbidden", "Sorry, you are not allowed to do that.")
            val before = server.requestCount

            assertEquals(Sender.Outcome.HELD, sender.run())
            assertTrue(settings.get().authError)
            assertEquals(entry, stored(entry.id))

            assertEquals(Sender.Outcome.HELD, sender.run())
            assertEquals(before + 1, server.requestCount)

            created()
            settings.setAuthError(false)
            assertEquals(Sender.Outcome.DONE, sender.run())
            assertTrue(queue.observe().first().isEmpty())
        }
    }

    @Test
    fun `a rejection of credentials that were replaced meanwhile is not held against the new ones`() = runTest {
        val entry = pending("Coffee")
        val renewed = signIn.copy(password = "wxyz")
        whileSending = {
            whileSending = {}
            saved = renewed
        }
        wordPressError(401, "incorrect_password", "The provided password is an invalid application password.")
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertFalse(settings.get().authError)
        assertNull(stored(entry.id))
        val passwords = List(2) { server.takeRequest().headers["Authorization"] }
        assertEquals(listOf(basic(signIn), basic(renewed)), passwords)
    }

    @Test
    fun `the credentials are read again for every entry`() = runTest {
        pending("First")
        pending("Second")
        val moved = signIn.copy(siteUrl = server.url("/blog").toString())
        whileSending = {
            whileSending = {}
            saved = moved
        }
        repeat(2) { created() }

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals(
            listOf("/wp-json/journal/v1/entries", "/blog/wp-json/journal/v1/entries"),
            List(2) { server.takeRequest().url.encodedPath },
        )
    }

    @Test
    fun `nothing is sent without credentials`() = runTest {
        val entry = pending("Coffee")
        sender
        saved = null

        assertEquals(Sender.Outcome.HELD, sender.run())
        assertEquals(0, server.requestCount)
        assertEquals(entry, stored(entry.id))
    }

    @Test
    fun `a missing plugin holds the queue instead of failing each entry`() = runTest {
        val first = pending("First")
        val second = pending("Second")
        wordPressError(404, "rest_no_route", "No route was found matching the URL and request method.")

        assertEquals(Sender.Outcome.RETRY, sender.run())

        assertEquals(1, server.requestCount)
        assertEquals(EntryState.PENDING, stored(first.id)!!.state)
        assertEquals(Sender.PLUGIN_MISSING, stored(first.id)!!.lastError)
        assertEquals(second, stored(second.id))
    }

    @Test
    fun `an answer that says nothing about the entry never fails it`() = runTest {
        val entry = pending("Coffee")
        val answers = listOf(
            MockResponse.Builder().code(404).body("<html>Not Found</html>"),
            MockResponse.Builder().code(400).body("<html>Bad Request</html>"),
            MockResponse.Builder().code(429).body("""{"code":"too_many_requests","message":"Slow down."}"""),
            MockResponse.Builder().code(301).addHeader("Location", "https://www.example.com/"),
            MockResponse.Builder().code(200).body("<html>Sign in to the hotel Wi-Fi</html>"),
        )
        for (response in answers) {
            server.enqueue(response.build())
            assertEquals(Sender.Outcome.RETRY, sender.run())
            assertEquals(EntryState.PENDING, stored(entry.id)!!.state)
            assertTrue(stored(entry.id)!!.lastError!!.isNotEmpty())
        }
        assertEquals(answers.size, server.requestCount)
    }

    @Test
    fun `only a 4xx with an error code is a verdict on the entry`() {
        fun refused(status: Int, code: String?) = verdict(io.github.leoalfaroc.journal.net.ApiResult.Refused(status, code, "message"))

        assertEquals(Verdict.REJECTED, refused(400, "empty_entry"))
        assertEquals(Verdict.REJECTED, refused(404, "rest_post_invalid_id"))
        assertEquals(Verdict.REJECTED, refused(413, "photo_too_large"))
        assertEquals(Verdict.REJECTED, refused(413, null))
        assertEquals(Verdict.AUTH, refused(401, "rest_not_logged_in"))
        assertEquals(Verdict.AUTH, refused(403, null))
        for ((status, code) in listOf(500 to "journal_failure", 503 to "journal_busy", 404 to "rest_no_route", 404 to null, 408 to "timeout", 429 to "slow_down", 301 to null, 200 to null)) {
            assertEquals("$status $code", Verdict.SERVER_FAULT, refused(status, code))
        }
    }

    @Test
    fun `the category, the status and the title are the ones set when the entry is sent`() = runTest {
        pending("Yesterday's entry")
        clock.advance(Duration.ofDays(1))
        settings.connectionSucceeded(
            SiteStatus("leo", Category("journal", "Journal"), listOf(Category("journal", "Journal"), Category("travel", "Travel")), 1_000_000, "1.0.0"),
        )
        settings.chooseCategory("travel")
        settings.setPostStatus(PostStatus.DRAFT)
        settings.setTitleLanguage(TitleLanguage.SPANISH)
        settings.setTitlePattern("EEEE d 'de' MMMM")
        created()

        sender.run()

        val body = sentBody()
        assertEquals("travel", body.text("category"))
        assertEquals("draft", body.text("post_status"))
        assertEquals("domingo 11 de octubre", body.text("title"))
    }

    @Test
    fun `the device language is used for the title when it is the one chosen`() = runTest {
        pending("Coffee")
        settings.setTitleLanguage(TitleLanguage.DEVICE)
        created()

        sender.run()

        assertEquals("2026-10-11 · dimanche, octobre 11", sentBody().text("title"))
    }

    @Test
    fun `a category fallback counts as sent, switches the setting and raises the notice`() = runTest {
        settings.connectionSucceeded(
            SiteStatus("leo", Category("journal", "Journal"), listOf(Category("journal", "Journal"), Category("travel", "Travel")), 1_000_000, "1.0.0"),
        )
        settings.chooseCategory("travel")
        pending("Coffee")
        answer(201, """{"status":"created","post_id":7,"category_fallback":{"slug":"journal","name":"Journal"}}""")

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertTrue(queue.observe().first().isEmpty())
        assertEquals("journal", settings.get().categorySlug)
        assertEquals(CategoryNotice(missing = "Travel", used = "Journal"), settings.get().categoryNotice)
    }
}
