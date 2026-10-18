package io.github.leoalfaroc.jot2press

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.leoalfaroc.jot2press.data.Category
import io.github.leoalfaroc.jot2press.data.CredentialStore
import io.github.leoalfaroc.jot2press.data.Credentials
import io.github.leoalfaroc.jot2press.data.Entry
import io.github.leoalfaroc.jot2press.data.PhotoFiles
import io.github.leoalfaroc.jot2press.data.Queue
import io.github.leoalfaroc.jot2press.data.SettingsStore
import io.github.leoalfaroc.jot2press.data.SiteStatus
import io.github.leoalfaroc.jot2press.data.memoryDb
import io.github.leoalfaroc.jot2press.location.FakeLocationSource
import io.github.leoalfaroc.jot2press.location.FakeSearchService
import io.github.leoalfaroc.jot2press.location.Fix
import io.github.leoalfaroc.jot2press.net.HttpJot2PressApi
import io.github.leoalfaroc.jot2press.net.Jot2PressApi
import io.github.leoalfaroc.jot2press.net.jot2PressHttpClient
import io.github.leoalfaroc.jot2press.send.PhotoEncoder
import io.github.leoalfaroc.jot2press.send.Scheduler
import io.github.leoalfaroc.jot2press.send.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import mockwebserver3.MockWebServer
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole app wired for a test: an empty queue, default settings, a clock moved by hand, a simulated site,
 * and a WorkManager whose jobs run only when the test says so. Call `close()` when the test ends.
 */
class TestWorld(folder: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val server = MockWebServer().apply { start() }
    val clock = TestClock()
    val box = FakeBox()

    private val db = memoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = tempStore(folder, storeScope)
    private var ids = 0

    val photoFiles = PhotoFiles(File(folder, "photos"))
    val location = FakeLocationSource()
    val search = FakeSearchService()
    /** The search's ticks come only when a test sends one. */
    val ticks = MutableSharedFlow<Unit>()
    val queue = Queue(db, clock, photoFiles) { "id-${++ids}" }
    val settings = SettingsStore(store)
    val credentials = CredentialStore(store, box)
    val work: WorkManager
    val driver: TestDriver
    val scheduler: Scheduler
    /** Every site address leads to the simulated site, which has no https. */
    val apiFor: (Credentials) -> Jot2PressApi = { HttpJot2PressApi(jot2PressHttpClient(), it.copy(siteUrl = server.url("/").toString())) }
    val sender = Sender(queue, settings, credentials = { credentials.get() }, apiFor = apiFor, files = photoFiles, prepare = PhotoEncoder(File(folder, "cache"))::prepare, deviceLocale = { Locale.ENGLISH })
    var widgetRefreshes = 0
    /** When set, the widget redraw throws it. */
    var widgetFailure: Exception? = null
    var unmetered = true
    /** The names of the photos the gallery was asked to save, including those it failed on. Written off the main thread. */
    val galleryAttempts: MutableList<String> = CopyOnWriteArrayList()
    /** The names of the photos the gallery saved. Written off the main thread. */
    val galleryCopies: MutableList<String> = CopyOnWriteArrayList()
    /** When set, saving to the gallery throws it. */
    var galleryFailure: Exception? = null
    val container: AppContainer

    val journal = Category("journal", "Journal")
    val travel = Category("travel", "Travel")

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        work = WorkManager.getInstance(context)
        driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        scheduler = Scheduler(work, queue, settings, clock)
        container = AppContainer(
            clock = clock,
            queue = queue,
            settings = settings,
            credentials = credentials,
            photoFiles = photoFiles,
            gallery = { photo ->
                galleryAttempts += photo.name
                galleryFailure?.let { throw it }
                galleryCopies += photo.name
            },
            location = location,
            searchService = search,
            scheduler = scheduler,
            sender = sender,
            apiFor = apiFor,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            onUnmeteredNetwork = { unmetered },
            refreshWidget = {
                widgetRefreshes++
                widgetFailure?.let { throw it }
            },
            locationTicks = ticks,
        )
        (context as Jot2PressApp).container = container
    }

    val signIn = Credentials("https://example.com/blog", "leo", "abcd efgh")

    /** As after saving credentials and a successful `Test connection` of them. */
    suspend fun connect(vararg categories: Category = arrayOf(journal, travel)) {
        credentials.save(signIn)
        settings.connectionSucceeded(SiteStatus("leo", journal, categories.toList(), 67_108_864, "1.0.0"))
    }

    /** Writes and sends an entry, then moves the clock on by a minute. */
    suspend fun write(text: String, graceSeconds: Int = 0): Entry {
        queue.saveDraft(text)
        val entry = queue.send(graceSeconds)!!
        clock.advance(Duration.ofMinutes(1))
        return entry
    }

    /** A position taken now, in Bogotá unless said otherwise. */
    fun fix(accuracy: Float, lat: Double = 4.60971, lng: Double = -74.08175, ageMillis: Long = 0) =
        Fix(lat, lng, accuracy, clock.millis() - ageMillis)

    /** Hands a position to whatever listens, and waits until the app has handled it. */
    suspend fun position(fix: Fix) = handled { location.emit(fix) }

    /** One tick of the search, handled. */
    suspend fun tick() = handled {
        ticks.subscriptionCount.first { it > 0 }
        ticks.emit(Unit)
    }

    private suspend fun handled(send: suspend () -> Unit) {
        val before = container.locator.handled.value
        send()
        container.locator.handled.first { it > before }
    }

    suspend fun entries(): List<Entry> = queue.observe().first()

    suspend fun jobs(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWorkFlow(name).first()

    fun close() {
        server.close()
        container.scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
        storeScope.cancel()
        db.close()
    }
}
