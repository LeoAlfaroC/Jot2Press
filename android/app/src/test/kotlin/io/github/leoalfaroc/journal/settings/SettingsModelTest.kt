package io.github.leoalfaroc.journal.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import io.github.leoalfaroc.journal.TestWorld
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.data.Category
import io.github.leoalfaroc.journal.data.CategoryNotice
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.PostStatus
import io.github.leoalfaroc.journal.send.Scheduler
import io.github.leoalfaroc.journal.send.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class SettingsModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val world by lazy { TestWorld(folder.root) }
    private val model by lazy { SettingsModel(world.container, deviceLocale = { Locale.FRENCH }) }

    @After
    fun close() = world.close()

    private suspend fun ui() = model.ui.first()

    private fun status(vararg categories: String = arrayOf("journal:Journal", "travel:Travel")): MockResponse {
        val list = categories.joinToString(",") { """{"slug":"${it.substringBefore(':')}","name":"${it.substringAfter(':')}"}""" }
        val body = """{"ok":true,"user":"leo","category":{"slug":"journal","name":"Journal"},"categories":[$list],"max_upload_bytes":67108864,"version":"1.0.0"}"""
        return MockResponse.Builder().code(200).body(body).build()
    }

    private fun error(status: Int, code: String, message: String) =
        MockResponse.Builder().code(status).body("""{"code":"$code","message":"$message","data":{"status":$status}}""").build()

    private fun type(site: String = "example.com/blog/", username: String = " leo ", password: String = " abcd efgh ") {
        model.editSiteUrl(site)
        model.editUsername(username)
        model.editPassword(password)
    }

    /** Answers with `answer` only once `gate` is open, so that a test can type while the request is in flight. */
    private fun gated(gate: CountDownLatch, answer: MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            gate.await()
            return answer
        }
    }

    @Test
    fun `before anything is set up the fields are empty and the defaults show`() = runTest {
        model.load()

        val ui = ui()
        assertEquals(SettingsForm(revision = 1, grace = "60", pattern = Titles.DEFAULT_PATTERN), ui.form)
        assertEquals("Empty", ui.queueSummary)
        assertEquals("2026-10-11 · Sunday, October 11", ui.titlePreview)
        assertFalse(ui.settings.configured)
    }

    @Test
    fun `it loads what is stored`() = runTest {
        world.connect()
        world.settings.setGraceSeconds(90)
        world.settings.setTitlePattern("d MMMM yyyy")
        world.write("Coffee")

        model.load()

        val form = ui().form
        assertEquals(world.signIn, Credentials(form.siteUrl, form.username, form.password))
        assertEquals("90", form.grace)
        assertEquals("d MMMM yyyy", form.pattern)
        assertEquals("1 waiting", ui().queueSummary)
    }

    @Test
    fun `a connection that works is stored, with what the site reported`() = runTest {
        world.server.enqueue(status())
        model.load()
        type()

        model.testConnection()

        val ui = ui()
        assertEquals(TestState.Passed("Connected as leo. Upload limit: 64 MB."), ui.form.test)
        assertEquals("https://example.com/blog", ui.form.siteUrl)
        assertEquals("leo", ui.form.username)
        assertEquals(2, ui.form.revision)
        assertEquals(Credentials("https://example.com/blog", "leo", "abcd efgh"), world.credentials.get())
        assertTrue(ui.settings.configured)
        assertEquals("journal", ui.settings.categorySlug)
        assertEquals(listOf(world.journal, world.travel), ui.settings.categories)
        assertEquals(67_108_864L, ui.settings.maxUploadBytes)

        val request = world.server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/wp-json/journal/v1/status", request.url.encodedPath)
        assertEquals(okhttp3.Credentials.basic("leo", "abcd efgh"), request.headers["Authorization"])
    }

    @Test
    fun `a connection that works lets the queue go again`() = runTest {
        world.connect()
        world.settings.setAuthError(true)
        world.write("Coffee")
        world.server.enqueue(status())
        model.load()

        model.testConnection()

        assertFalse(ui().settings.authError)
        assertEquals(NetworkType.CONNECTED, world.jobs(Scheduler.SEND_WORK).single().constraints.requiredNetworkType)
    }

    @Test
    fun `an address that is not https is refused without asking the site`() = runTest {
        model.load()
        type(site = "http://example.com")

        model.testConnection()

        assertEquals(TestState.Failed(URL_ERROR), ui().form.test)
        assertEquals(0, world.server.requestCount)
        assertNull(world.credentials.get())
    }

    @Test
    fun `a missing username or password is asked for`() = runTest {
        model.load()
        type(password = "  ")

        model.testConnection()

        assertEquals(TestState.Failed(SIGN_IN_ERROR), ui().form.test)
        assertEquals(0, world.server.requestCount)
    }

    @Test
    fun `credentials the site rejects are not stored, and the ones in use stay`() = runTest {
        world.connect()
        world.server.enqueue(error(401, "incorrect_password", "The provided password is an invalid application password."))
        model.load()
        model.editPassword("wrong")

        model.testConnection()

        assertEquals(TestState.Failed(REJECTED_ERROR), ui().form.test)
        assertEquals(world.signIn, world.credentials.get())
        assertEquals("wrong", ui().form.password)
    }

    @Test
    fun `it says what is wrong when the plugin is missing, the user may not write, or the site is not reached`() = runTest {
        model.load()
        type()

        world.server.enqueue(error(404, "rest_no_route", "No route was found matching the URL and request method."))
        model.testConnection()
        assertEquals(TestState.Failed(Sender.PLUGIN_MISSING), ui().form.test)

        world.server.enqueue(error(403, "rest_forbidden", "Sorry, you are not allowed to do that."))
        model.testConnection()
        assertEquals(TestState.Failed("Sorry, you are not allowed to do that."), ui().form.test)

        world.server.close()
        model.testConnection()
        val failed = ui().form.test as TestState.Failed
        assertTrue(failed.message, failed.message.startsWith("Could not reach the site: "))
        assertFalse(ui().settings.configured)
    }

    @Test
    fun `editing a field takes the last result away`() = runTest {
        model.load()
        type(site = "http://example.com")
        model.testConnection()

        model.editSiteUrl("example.com")

        assertEquals(TestState.Idle, ui().form.test)
    }

    @Test
    fun `a password typed while the connection is tested is kept, and the tested one is stored`() = runTest {
        val gate = CountDownLatch(1)
        world.server.dispatcher = gated(gate, status())
        model.load()
        type()

        val test = launch { model.testConnection() }
        try {
            withContext(Dispatchers.IO) { world.server.takeRequest() }
            model.editPassword("new secret")
            assertEquals(TestState.Running, ui().form.test)
        } finally {
            gate.countDown()
        }
        test.join()

        val ui = ui()
        assertEquals("new secret", ui.form.password)
        assertEquals(TestState.Passed("Connected with the earlier values. Test again to use these."), ui.form.test)
        assertEquals(Credentials("https://example.com/blog", "leo", "abcd efgh"), world.credentials.get())
        assertTrue(ui.settings.configured)
    }

    @Test
    fun `a password typed while a refused connection is tested is kept, with no message about the old one`() = runTest {
        val gate = CountDownLatch(1)
        world.server.dispatcher = gated(gate, error(401, "incorrect_password", "The provided password is an invalid application password."))
        model.load()
        type()

        val test = launch { model.testConnection() }
        try {
            withContext(Dispatchers.IO) { world.server.takeRequest() }
            model.editPassword("new secret")
        } finally {
            gate.countDown()
        }
        test.join()

        val ui = ui()
        assertEquals("new secret", ui.form.password)
        assertEquals(TestState.Idle, ui.form.test)
        assertNull(world.credentials.get())
    }

    @Test
    fun `a connection whose list no longer has the chosen category switches to the default and raises the notice`() = runTest {
        world.connect()
        world.settings.chooseCategory("travel")
        world.server.enqueue(status("journal:Journal"))
        model.load()

        model.testConnection()

        assertEquals("journal", ui().settings.categorySlug)
        assertEquals(CategoryNotice(missing = "Travel", used = "Journal"), ui().settings.categoryNotice)

        model.dismissCategoryNotice()
        assertNull(ui().settings.categoryNotice)
    }

    @Test
    fun `choosing a category clears the notice`() = runTest {
        world.connect()
        world.settings.chooseCategory("travel")
        world.settings.categoryFellBack(Category("journal", "Journal"))
        assertEquals(CategoryNotice(missing = "Travel", used = "Journal"), ui().settings.categoryNotice)

        model.chooseCategory("journal")

        assertNull(ui().settings.categoryNotice)
        assertEquals("journal", ui().settings.categorySlug)
    }

    @Test
    fun `the grace window is stored when it is a number from 0 to 600`() = runTest {
        model.load()

        model.editGrace("0")
        assertEquals(0, ui().settings.graceSeconds)
        model.editGrace(" 600 ")
        assertEquals(600, ui().settings.graceSeconds)
        assertNull(ui().form.graceError)

        for (wrong in listOf("601", "-1", "", "abc", "1.5")) {
            model.editGrace(wrong)
            assertEquals(wrong, ui().form.grace)
            assertEquals(GRACE_ERROR, ui().form.graceError)
            assertEquals(600, ui().settings.graceSeconds)
        }
    }

    @Test
    fun `Upload on Wi-Fi only replaces the send job at once`() = runTest {
        world.write("Coffee")
        world.scheduler.sync()

        model.setWifiOnly(true)
        assertTrue(ui().settings.wifiOnly)
        assertEquals(NetworkType.UNMETERED, world.jobs(Scheduler.SEND_WORK).single { !it.state.isFinished }.constraints.requiredNetworkType)

        model.setWifiOnly(false)
        assertEquals(NetworkType.CONNECTED, world.jobs(Scheduler.SEND_WORK).single { !it.state.isFinished }.constraints.requiredNetworkType)
    }

    @Test
    fun `the title pattern is previewed in the chosen language, and stored only when it can make a title`() = runTest {
        model.load()

        model.editPattern("EEEE d 'de' MMMM")
        model.setTitleLanguage(TitleLanguage.SPANISH)
        assertEquals("domingo 11 de octubre", ui().titlePreview)
        assertEquals("EEEE d 'de' MMMM", ui().settings.titlePattern)

        model.setTitleLanguage(TitleLanguage.DEVICE)
        assertEquals("dimanche 11 de octobre", ui().titlePreview)

        model.editPattern("yyyy-MM-dd 'at' HH:mm")
        assertEquals(PATTERN_ERROR, ui().form.patternError)
        assertNull(ui().titlePreview)
        assertEquals("EEEE d 'de' MMMM", ui().settings.titlePattern)

        model.editPattern("yyyy-MM-dd")
        assertNull(ui().form.patternError)
        assertEquals("2026-10-11", ui().titlePreview)
    }

    @Test
    fun `the post status is stored`() = runTest {
        model.setPostStatus(PostStatus.PUBLIC)
        assertEquals(PostStatus.PUBLIC, ui().settings.postStatus)
    }

    @Test
    fun `megabytes are whole when they can be`() {
        assertEquals("64 MB", megabytes(67_108_864))
        assertEquals("1.5 MB", megabytes(1_572_864))
        assertEquals("0.9 MB", megabytes(1_000_000))
    }
}
