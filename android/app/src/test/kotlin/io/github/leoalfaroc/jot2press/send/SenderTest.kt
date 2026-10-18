package io.github.leoalfaroc.jot2press.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.TestClock
import io.github.leoalfaroc.jot2press.TitleLanguage
import io.github.leoalfaroc.jot2press.data.Category
import io.github.leoalfaroc.jot2press.data.CategoryNotice
import io.github.leoalfaroc.jot2press.data.Credentials
import io.github.leoalfaroc.jot2press.data.DraftPhoto
import io.github.leoalfaroc.jot2press.data.Entry
import io.github.leoalfaroc.jot2press.data.EntryState
import io.github.leoalfaroc.jot2press.data.PhotoFiles
import io.github.leoalfaroc.jot2press.data.PostStatus
import io.github.leoalfaroc.jot2press.data.Queue
import io.github.leoalfaroc.jot2press.data.SettingsStore
import io.github.leoalfaroc.jot2press.data.SiteStatus
import io.github.leoalfaroc.jot2press.data.isStuck
import io.github.leoalfaroc.jot2press.data.memoryDb
import io.github.leoalfaroc.jot2press.location.Fix
import io.github.leoalfaroc.jot2press.net.HttpJot2PressApi
import io.github.leoalfaroc.jot2press.net.Jot2PressApi
import io.github.leoalfaroc.jot2press.net.jot2PressHttpClient
import io.github.leoalfaroc.jot2press.tempStore
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
    private val files by lazy { PhotoFiles(folder.newFolder("photos")) }
    private val queue by lazy { Queue(db, clock, files) { "id-${++ids}" } }
    private val settings by lazy { SettingsStore(tempStore(folder.root, storeScope)) }
    private val signIn by lazy { Credentials(server.url("").toString(), "leo", "abcd") }
    private var saved: Credentials? = null
    /** Runs when a request is about to leave, after the sender has read the credentials for it. */
    private var whileSending: () -> Unit = {}
    /** Replaced by a test that needs preparing a photo to fail. */
    private var prepare: (java.io.File, Long) -> Prepared = { source, limit -> PhotoEncoder(folder.newFolder("cache-${++caches}")).prepare(source, limit) }
    private var caches = 0
    private val sender by lazy {
        saved = signIn
        Sender(queue, settings, credentials = { saved }, apiFor = ::api, files = files, prepare = { source, limit -> prepare(source, limit) }, deviceLocale = { Locale.FRENCH })
    }

    private fun api(signIn: Credentials): Jot2PressApi {
        whileSending()
        return HttpJot2PressApi(jot2PressHttpClient(), signIn)
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

    private var photoIds = 0

    /** An entry with photos, ready to send. Each copy starts like a JPEG, so it goes as it is under a large limit (the type is read from 12 bytes). */
    private suspend fun pendingWithPhotos(text: String, count: Int): Pair<Entry, List<DraftPhoto>> {
        val photos = List(count) {
            val id = "photo-${++photoIds}"
            files.newFile(id, "jpg").writeBytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(8) + id.toByteArray())
            DraftPhoto(id, "$id.jpg")
        }
        queue.saveDraft(text, photos)
        val entry = queue.send(graceSeconds = 0)!!
        clock.advance(Duration.ofMinutes(1))
        return entry to photos
    }

    private fun photoStored() = answer(201, """{"status":"created","attachment_id":12}""")
    private fun status(limit: Long) = answer(
        200,
        """{"user":"leo","category":{"slug":"journal","name":"Journal"},"categories":[],"max_upload_bytes":$limit,"version":"1.0.0"}""",
    )

    @Test
    fun `photos go first, one per request and in order, then the entry lists them`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (entry, photos) = pendingWithPhotos("Coffee", 2)
        photoStored()
        photoStored()
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.url.encodedPath.endsWith("/photos"))
        assertTrue(first.body!!.utf8().contains(photos[0].id))
        assertTrue(second.body!!.utf8().contains(photos[1].id))
        val body = sentBody()
        assertEquals(entry.id, body.text("id"))
        assertEquals("[\"${photos[0].id}\",\"${photos[1].id}\"]", body.getValue("photo_ids").toString())
        assertTrue(queue.observe().first().isEmpty())
        assertFalse(files.file(photos[0].file).exists())
    }

    @Test
    fun `a run that stops part-way goes on from the first photo not uploaded`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (_, photos) = pendingWithPhotos("Coffee", 2)
        photoStored()
        answer(503, """{"code":"jot2press_busy","message":"Busy.","data":{"status":503}}""")

        assertEquals(Sender.Outcome.RETRY, sender.run())
        photoStored()
        created()
        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals(4, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        assertTrue(server.takeRequest().body!!.utf8().contains(photos[1].id))
    }

    @Test
    fun `with no limit stored, the sender asks the site for it before the first photo`() = runTest {
        pendingWithPhotos("Coffee", 1)
        status(8_388_608)
        photoStored()
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertTrue(server.takeRequest().url.encodedPath.endsWith("/status"))
        assertEquals(8_388_608L, settings.get().maxUploadBytes)
    }

    @Test
    fun `a photo the web server rejects as too large fails the entry, says which, and the limit is read again`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (entry, _) = pendingWithPhotos("Coffee", 2)
        answer(413, "<html>Request Entity Too Large</html>")
        status(2_097_152)

        assertEquals(Sender.Outcome.DONE, sender.run())

        val failed = stored(entry.id)!!
        assertEquals(EntryState.FAILED, failed.state)
        assertEquals(
            "Photo 1 of 2 is larger than the site accepts. The web server's upload limit is lower than the 2 MB WordPress reports.",
            failed.lastError,
        )
        assertEquals(2_097_152L, settings.get().maxUploadBytes)
    }

    @Test
    fun `after unknown_photo, Retry uploads the photos again`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (entry, photos) = pendingWithPhotos("Coffee", 1)
        photoStored()
        wordPressError(400, "unknown_photo", "Photo ${photos[0].id} has not been uploaded.")
        assertEquals(Sender.Outcome.DONE, sender.run())
        assertEquals(EntryState.FAILED, stored(entry.id)!!.state)

        queue.retry(entry.id)
        photoStored()
        created()
        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals(4, server.requestCount)
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `a copy that cannot be read fails the entry without asking the site`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (entry, photos) = pendingWithPhotos("Coffee", 2)
        files.file(photos[1].file).delete()
        photoStored()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals("Photo 2 could not be read on the phone.", stored(entry.id)!!.lastError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a phone that cannot write the photo to send keeps the entry waiting`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (entry, _) = pendingWithPhotos("Coffee", 1)
        prepare = { _, _ -> throw java.io.IOException("No space left on device") }

        assertEquals(Sender.Outcome.RETRY, sender.run())

        assertEquals(EntryState.PENDING, stored(entry.id)!!.state)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a photo that fails to prepare in an unexpected way fails the entry as unreadable`() = runTest {
        settings.setMaxUploadBytes(67_108_864)
        val (entry, _) = pendingWithPhotos("Coffee", 1)
        prepare = { _, _ -> throw IllegalStateException("Bitmap broke") }

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals(EntryState.FAILED, stored(entry.id)!!.state)
        assertEquals("Photo 1 could not be read on the phone.", stored(entry.id)!!.lastError)
        assertEquals(0, server.requestCount)
    }

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
        wordPressError(503, "jot2press_busy", "The journal is busy. Try again.")
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
        assertEquals(HttpJot2PressApi.TOO_LARGE, failed.lastError)
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
            listOf("/wp-json/jot2press/v1/entries", "/blog/wp-json/jot2press/v1/entries"),
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
        fun refused(status: Int, code: String?) = verdict(io.github.leoalfaroc.jot2press.net.ApiResult.Refused(status, code, "message"))

        assertEquals(Verdict.REJECTED, refused(400, "empty_entry"))
        assertEquals(Verdict.REJECTED, refused(404, "rest_post_invalid_id"))
        assertEquals(Verdict.REJECTED, refused(413, "photo_too_large"))
        assertEquals(Verdict.REJECTED, refused(413, null))
        assertEquals(Verdict.AUTH, refused(401, "rest_not_logged_in"))
        assertEquals(Verdict.AUTH, refused(403, null))
        for ((status, code) in listOf(500 to "jot2press_failure", 503 to "jot2press_busy", 404 to "rest_no_route", 404 to null, 408 to "timeout", 429 to "slow_down", 301 to null, 200 to null)) {
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

    private fun bogota() = Fix(4.60971, -74.08175, 12f, clock.millis())

    @Test
    fun `an entry whose pin was on sends its location`() = runTest {
        queue.saveDraft("Coffee")
        queue.send(graceSeconds = 0, attachLocation = true, position = bogota())
        clock.advance(Duration.ofMinutes(1))
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertEquals(Json.parseToJsonElement("""{"lat":4.60971,"lng":-74.08175}"""), sentBody().getValue("location"))
    }

    @Test
    fun `an entry whose pin was on but that found no position sends none`() = runTest {
        queue.saveDraft("Coffee")
        queue.send(graceSeconds = 0, attachLocation = true)
        clock.advance(Duration.ofMinutes(1))
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertFalse("location" in sentBody())
    }

    @Test
    fun `a position kept with the pin turned off stays on the phone`() = runTest {
        queue.saveDraft("Coffee")
        val first = queue.send(graceSeconds = 60, attachLocation = true, position = bogota())!!
        queue.takeBack(first.id)
        queue.send(graceSeconds = 0, attachLocation = false)
        clock.advance(Duration.ofMinutes(1))
        created()

        assertEquals(Sender.Outcome.DONE, sender.run())

        assertFalse("location" in sentBody())
    }
}
