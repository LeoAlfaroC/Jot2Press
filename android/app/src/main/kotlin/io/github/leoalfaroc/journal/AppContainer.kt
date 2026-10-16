package io.github.leoalfaroc.journal

import android.app.Application
import android.net.ConnectivityManager
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.glance.appwidget.updateAll
import androidx.room.Room
import androidx.work.WorkManager
import io.github.leoalfaroc.journal.data.CredentialStore
import io.github.leoalfaroc.journal.capture.Gallery
import io.github.leoalfaroc.journal.capture.MediaStoreGallery
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.JournalDb
import io.github.leoalfaroc.journal.data.KeystoreBox
import io.github.leoalfaroc.journal.data.PhotoFiles
import io.github.leoalfaroc.journal.data.Queue
import io.github.leoalfaroc.journal.data.SettingsStore
import io.github.leoalfaroc.journal.net.HttpJournalApi
import io.github.leoalfaroc.journal.net.JournalApi
import io.github.leoalfaroc.journal.net.journalHttpClient
import io.github.leoalfaroc.journal.send.PhotoEncoder
import io.github.leoalfaroc.journal.send.Scheduler
import io.github.leoalfaroc.journal.send.Sender
import io.github.leoalfaroc.journal.widget.JournalWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The parts of the app that exist once, handed to whatever needs them. */
class AppContainer(
    val clock: Clock,
    val queue: Queue,
    val settings: SettingsStore,
    val credentials: CredentialStore,
    /** The copies of the photos in the draft and in the queue. */
    val photoFiles: PhotoFiles,
    /** Where a photo taken with the camera button is also saved. */
    val gallery: Gallery = Gallery { },
    val scheduler: Scheduler,
    val sender: Sender,
    /** Makes the API for a set of credentials. `Test connection` uses it with credentials not saved yet. */
    val apiFor: (Credentials) -> JournalApi,
    /** Outlives every screen: work that must finish after a screen has closed runs here. */
    val scope: CoroutineScope,
    /** Whether the phone is on a network that `Upload on Wi-Fi only` allows. */
    val onUnmeteredNetwork: () -> Boolean = { true },
    val refreshWidget: suspend () -> Unit = {},
) {
    /**
     * What the capture dialog does to the queue, for every opening of it: a dialog that is closing
     * stores its draft before the next one reads it, however quickly one follows the other.
     */
    val captureWork = InOrder(scope)

    /** What the settings screen stores: two quick edits of a field are stored in the order they were typed. */
    val settingsWork = InOrder(scope)

    /** Redraws the widget. A failed redraw only leaves it as it was: it must not end the caller. */
    suspend fun redrawWidget() {
        try {
            refreshWidget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next change, or the next run of the sender, draws it again.
        }
    }
}

/** The wall clock, in whatever time zone the phone is in at the moment it is asked. */
object PhoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}

fun realContainer(app: Application): AppContainer {
    val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) { app.preferencesDataStoreFile(SettingsStore.NAME) }
    val db = Room.databaseBuilder(app, JournalDb::class.java, JournalDb.NAME).addMigrations(JournalDb.MIGRATION_1_2, JournalDb.MIGRATION_2_3).build()
    val photoFiles = PhotoFiles(File(app.filesDir, "photos"))
    val queue = Queue(db, PhoneClock, photoFiles)
    val settings = SettingsStore(store)
    val credentials = CredentialStore(store, KeystoreBox())
    val client = journalHttpClient()
    val apiFor = { signIn: Credentials -> HttpJournalApi(client, signIn) }
    val connectivity = app.getSystemService(ConnectivityManager::class.java)
    return AppContainer(
        clock = PhoneClock,
        queue = queue,
        settings = settings,
        credentials = credentials,
        photoFiles = photoFiles,
        gallery = MediaStoreGallery(app.contentResolver, app.getString(R.string.app_name)),
        scheduler = Scheduler(WorkManager.getInstance(app), queue, settings, PhoneClock),
        sender = Sender(queue, settings, credentials = { credentials.get() }, apiFor = apiFor, files = photoFiles, prepare = PhotoEncoder(app.cacheDir)::prepare),
        apiFor = apiFor,
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        onUnmeteredNetwork = { connectivity.activeNetwork != null && !connectivity.isActiveNetworkMetered },
        refreshWidget = { JournalWidget().updateAll(app) },
    )
}
