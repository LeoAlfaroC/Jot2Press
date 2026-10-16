package io.github.leoalfaroc.journal

import android.app.Application
import io.github.leoalfaroc.journal.widget.widgetStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class JournalApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        install()
    }

    /** Builds the real parts of the app. Tests replace this and set `container` themselves. */
    protected open fun install() {
        container = realContainer(this)
        // A safety net: whatever a killed process left unscheduled is scheduled again.
        container.scope.launch { container.scheduler.sync() }
        // The widget is redrawn whenever what it shows changes.
        container.scope.launch { widgetStates(container).collect { container.redrawWidget() } }
        // Copies left behind by a process that died between deleting a photo's row and its file.
        val started = System.currentTimeMillis()
        container.scope.launch(Dispatchers.IO) {
            // Best effort: a failure here must not take the app down.
            runCatching { container.photoFiles.sweep(container.queue.photoFileNames(), before = started - SWEEP_MARGIN_MILLIS) }
        }
    }

    private companion object {
        /** A camera shot can return to a dialog that Android recreated in a new process: its copy is spared for an hour. */
        const val SWEEP_MARGIN_MILLIS = 60 * 60 * 1000L
    }
}
