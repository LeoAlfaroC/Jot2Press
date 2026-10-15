package io.github.leoalfaroc.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.leoalfaroc.journal.data.Category
import io.github.leoalfaroc.journal.data.CredentialStore
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.Queue
import io.github.leoalfaroc.journal.data.SettingsStore
import io.github.leoalfaroc.journal.data.SiteStatus
import io.github.leoalfaroc.journal.data.memoryDb
import io.github.leoalfaroc.journal.net.HttpJournalApi
import io.github.leoalfaroc.journal.net.JournalApi
import io.github.leoalfaroc.journal.net.journalHttpClient
import io.github.leoalfaroc.journal.send.Scheduler
import io.github.leoalfaroc.journal.send.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import mockwebserver3.MockWebServer
import java.io.File
import java.time.Duration
import java.util.Locale

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

    val queue = Queue(db, clock) { "id-${++ids}" }
    val settings = SettingsStore(store)
    val credentials = CredentialStore(store, box)
    val work: WorkManager
    val driver: TestDriver
    val scheduler: Scheduler
    /** Every site address leads to the simulated site, which has no https. */
    val apiFor: (Credentials) -> JournalApi = { HttpJournalApi(journalHttpClient(), it.copy(siteUrl = server.url("/").toString())) }
    val sender = Sender(queue, settings, credentials = { credentials.get() }, apiFor = apiFor, deviceLocale = { Locale.ENGLISH })
    var widgetRefreshes = 0
    var unmetered = true
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
            scheduler = scheduler,
            sender = sender,
            apiFor = apiFor,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            onUnmeteredNetwork = { unmetered },
            refreshWidget = { widgetRefreshes++ },
        )
        (context as JournalApp).container = container
    }

    val signIn = Credentials("https://example.com/blog", "leo", "abcd efgh")

    /** As after a successful `Test connection`. */
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

    suspend fun entries(): List<Entry> = queue.observe().first()

    suspend fun jobs(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWorkFlow(name).first()

    fun close() {
        server.close()
        container.scope.cancel()
        storeScope.cancel()
        db.close()
    }
}
