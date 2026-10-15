package io.github.leoalfaroc.journal

import android.app.Application
import io.github.leoalfaroc.journal.widget.widgetStates
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
        container.scope.launch { widgetStates(container).collect { container.refreshWidget() } }
    }
}
