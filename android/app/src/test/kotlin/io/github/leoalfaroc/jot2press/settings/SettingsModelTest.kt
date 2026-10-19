package io.github.leoalfaroc.jot2press.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import io.github.leoalfaroc.jot2press.TestWorld
import io.github.leoalfaroc.jot2press.TitleLanguage
import io.github.leoalfaroc.jot2press.Titles
import io.github.leoalfaroc.jot2press.data.Category
import io.github.leoalfaroc.jot2press.data.CategoryNotice
import io.github.leoalfaroc.jot2press.data.Credentials
import io.github.leoalfaroc.jot2press.data.PostStatus
import io.github.leoalfaroc.jot2press.location.Permission
import io.github.leoalfaroc.jot2press.send.Scheduler
import io.github.leoalfaroc.jot2press.send.Sender
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

    /** Answers with `answer` only once `gate` is open, so that a test can act while the request is in flight. */
    private fun gated(gate: CountDownLatch, answer: MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            gate.await()
            return answer
        }
    }

    @Test
    fun `saved credentials that can no longer be read open as empty fields, with a line saying so`() = runTest {
        world.connect()
        world.box.broken = true

        model.load()

        val form = ui().form
        assertTrue(form.editing)
        assertFalse(form.hasSaved)
        assertTrue(form.unreadable)
        assertEquals("", form.siteUrl)
    }

    @Test
    fun `saving again clears the line`() = runTest {
        world.connect()
        world.box.broken = true
        model.load()
        world.box.broken = false
        model.editSiteUrl("https://example.com/blog")
        model.editUsername("leo")
        model.editPassword("abcd efgh")

        model.save()

        assertFalse(ui().form.unreadable)
    }

    @Test
    fun `before anything is set up the fields are empty, open for typing, and the defaults show`() = runTest {
        model.load()

        val ui = ui()
        assertEquals(SettingsForm(revision = 1, editing = true, grace = "60", pattern = Titles.DEFAULT_PATTERN), ui.form)
        assertEquals("Empty", ui.queueSummary)
        assertEquals("2026-10-11 · Sunday, October 11", ui.titlePreview)
        assertFalse(ui.settings.configured)
    }

    @Test
    fun `it loads what is stored, read-only`() = runTest {
        world.connect()
        world.settings.setGraceSeconds(90)
        world.settings.setTitlePattern("d MMMM yyyy")
        world.write("Coffee")

        model.load()

        val form = ui().form
        assertEquals(world.signIn, Credentials(form.siteUrl, form.username, form.password))
        assertFalse(form.editing)
        assertTrue(form.hasSaved)
        assertEquals("90", form.grace)
        assertEquals("d MMMM yyyy", form.pattern)
        assertEquals("1 waiting", ui().queueSummary)
    }

    @Test
    fun `Save stores what is typed without asking the site`() = runTest {
        model.load()
        type()

        model.save()

        val ui = ui()
        assertEquals(Credentials("https://example.com/blog", "leo", "abcd efgh"), world.credentials.get())
        assertEquals(0, world.server.requestCount)
        assertTrue(ui.settings.configured)
        assertFalse(ui.form.editing)
        assertTrue(ui.form.hasSaved)
        assertTrue(ui.form.saved)
        assertEquals(2, ui.form.revision)
        assertEquals("https://example.com/blog", ui.form.siteUrl)
        assertEquals("leo", ui.form.username)
        assertEquals("abcd efgh", ui.form.password)
    }

    @Test
    fun `a sign-in that cannot be stored says so and stays open for typing`() = runTest {
        model.load()
        type()
        world.box.sealBroken = true

        model.save()

        val ui = ui()
        assertNull(world.credentials.get())
        assertTrue(ui.form.editing)
        assertFalse(ui.form.saved)
        assertEquals(TestState.Failed(STORE_ERROR), ui.form.test)
    }

    @Test
    fun `Save lets entries held by a rejected sign-in go again`() = runTest {
        world.connect()
        world.settings.setAuthError(true)
        world.write("Coffee")
        model.load()
        model.edit()
        model.editPassword("new secret")

        model.save()

        assertFalse(ui().settings.authError)
        assertEquals(Credentials(world.signIn.siteUrl, world.signIn.username, "new secret"), world.credentials.get())
        assertEquals(NetworkType.CONNECTED, world.jobs(Scheduler.SEND_WORK).single().constraints.requiredNetworkType)
    }

    @Test
    fun `saved credentials cannot be typed over until Edit, and Cancel puts them back`() = runTest {
        world.connect()
        model.load()

        model.editPassword("ignored")
        assertEquals(world.signIn.password, ui().form.password)

        model.edit()
        assertTrue(ui().form.editing)
        model.editPassword("other")
        assertEquals("other", ui().form.password)
        assertEquals(world.signIn, world.credentials.get())

        model.cancel()

        val form = ui().form
        assertFalse(form.editing)
        assertEquals(world.signIn.password, form.password)
        assertEquals(2, form.revision)
        assertEquals(world.signIn, world.credentials.get())
    }

    @Test
    fun `an address that is not https, or a missing username or password, is refused without storing or asking`() = runTest {
        model.load()

        type(site = "http://example.com")
        model.save()
        assertEquals(TestState.Failed(URL_ERROR), ui().form.test)
        model.testConnection()
        assertEquals(TestState.Failed(URL_ERROR), ui().form.test)

        type(password = "  ")
        model.save()
        assertEquals(TestState.Failed(SIGN_IN_ERROR), ui().form.test)
        model.testConnection()
        assertEquals(TestState.Failed(SIGN_IN_ERROR), ui().form.test)

        assertTrue(ui().form.editing)
        assertEquals(0, world.server.requestCount)
        assertNull(world.credentials.get())
    }

    @Test
    fun `Test connection checks what is typed and stores nothing, and Save then keeps what the site reported`() = runTest {
        world.server.enqueue(status())
        model.load()
        type()

        model.testConnection()

        assertEquals(TestState.Passed("Connected as leo. Upload limit: 64 MB."), ui().form.test)
        assertNull(world.credentials.get())
        assertFalse(ui().settings.configured)
        assertEquals(emptyList<Category>(), ui().settings.categories)
        val request = world.server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/wp-json/jot2press/v1/status", request.url.encodedPath)
        assertEquals(okhttp3.Credentials.basic("leo", "abcd efgh"), request.headers["Authorization"])

        model.save()

        val ui = ui()
        assertEquals(Credentials("https://example.com/blog", "leo", "abcd efgh"), world.credentials.get())
        assertEquals("journal", ui.settings.categorySlug)
        assertEquals(listOf(world.journal, world.travel), ui.settings.categories)
        assertEquals(67_108_864L, ui.settings.maxUploadBytes)
    }

    @Test
    fun `what a test reported is not kept when other values are saved`() = runTest {
        world.server.enqueue(status())
        model.load()
        type()
        model.testConnection()

        model.editPassword("changed")
        model.save()

        assertEquals("changed", world.credentials.get()?.password)
        assertEquals(emptyList<Category>(), ui().settings.categories)
        assertNull(ui().settings.maxUploadBytes)
    }

    @Test
    fun `testing the saved credentials keeps what the site reported and lets the queue go again`() = runTest {
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
    fun `credentials the site rejects in a test are not stored, and the ones in use stay`() = runTest {
        world.connect()
        world.server.enqueue(error(401, "incorrect_password", "The provided password is an invalid application password."))
        model.load()
        model.edit()
        model.editPassword("wrong")

        model.testConnection()

        assertEquals(TestState.Failed(REJECTED_ERROR), ui().form.test)
        assertEquals(world.signIn, world.credentials.get())
        assertEquals("wrong", ui().form.password)
        assertTrue(ui().form.editing)
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
    fun `while the connection is tested, typing, Save and Cancel do nothing`() = runTest {
        val gate = CountDownLatch(1)
        world.server.dispatcher = gated(gate, status())
        world.connect()
        model.load()
        model.edit()
        model.editPassword("typed")

        val test = launch { model.testConnection() }
        try {
            withContext(Dispatchers.IO) { world.server.takeRequest() }
            model.editPassword("new secret")
            model.save()
            model.cancel()
            assertEquals(TestState.Running, ui().form.test)
            assertTrue(ui().form.editing)
        } finally {
            gate.countDown()
        }
        test.join()

        assertEquals("typed", ui().form.password)
        assertEquals(TestState.Passed("Connected as leo. Upload limit: 64 MB."), ui().form.test)
        assertEquals(world.signIn, world.credentials.get())
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

    @Test
    fun `Attach location shows on only while it is stored on and a location permission is held`() = runTest {
        world.settings.setAttachLocation(true)
        model.load()
        assertFalse(ui().locationOn)

        world.location.permission = Permission.APPROXIMATE
        model.checkLocation()
        assertTrue(ui().locationOn)

        world.location.permission = Permission.NONE
        model.checkLocation()
        assertFalse(ui().locationOn)
    }

    @Test
    fun `a granted permission stores Attach location on, with the pin on`() = runTest {
        model.load()
        world.location.permission = Permission.PRECISE

        model.locationAnswered(granted = true, blocked = false)

        assertTrue(world.settings.get().attachLocation)
        assertTrue(world.settings.get().pinOn)
        assertTrue(ui().locationOn)
    }

    @Test
    fun `a refusal leaves it off, says so when Android no longer asks, and the line goes once a permission is held`() = runTest {
        model.load()

        model.locationAnswered(granted = false, blocked = false)
        assertFalse(world.settings.get().attachLocation)
        assertFalse(ui().form.locationBlocked)

        model.locationAnswered(granted = false, blocked = true)
        assertFalse(world.settings.get().attachLocation)
        assertTrue(ui().form.locationBlocked)

        world.location.permission = Permission.PRECISE
        model.checkLocation()
        assertFalse(ui().form.locationBlocked)
    }

    @Test
    fun `turning Attach location off stores it off`() = runTest {
        world.settings.setAttachLocation(true)
        world.location.permission = Permission.PRECISE
        model.load()

        model.setAttachLocation(false)

        assertFalse(world.settings.get().attachLocation)
        assertFalse(ui().locationOn)
    }
}
