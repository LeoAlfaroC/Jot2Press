package io.github.leoalfaroc.journal.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.fillMaxSize
import io.github.leoalfaroc.journal.AppContainer
import io.github.leoalfaroc.journal.JournalApp
import io.github.leoalfaroc.journal.R
import io.github.leoalfaroc.journal.WidgetState
import io.github.leoalfaroc.journal.capture.CaptureActivity
import io.github.leoalfaroc.journal.queueFacts
import io.github.leoalfaroc.journal.settings.SettingsActivity
import io.github.leoalfaroc.journal.widgetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** What the widget shows, each time the queue or the settings change. */
fun widgetStates(container: AppContainer): Flow<WidgetState> =
    combine(container.queue.observe(), container.settings.flow) { entries, settings ->
        widgetState(queueFacts(entries, container.clock.millis()), settings)
    }.distinctUntilChanged()

class JournalWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = widgetStates((context.applicationContext as JournalApp).container)
        val first = states.first()
        provideContent {
            val state by states.collectAsState(first)
            AndroidRemoteViews(widgetViews(LocalContext.current, state), GlanceModifier.fillMaxSize())
        }
    }
}

class JournalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = JournalWidget()
}

/** What the badge shows, or null when there is no badge. A rejected sign-in comes first: nothing is sent until it is fixed. */
fun badgeText(state: WidgetState): String? = when {
    state.signInRejected -> "!"
    state.failed > 99 -> "99+"
    state.failed > 0 -> state.failed.toString()
    else -> null
}

/** What a screen reader says for the badge. */
fun badgeDescription(state: WidgetState): String =
    if (state.signInRejected) "The site rejected the sign-in. Open settings" else "${state.failed} failed to send. Open settings"

/**
 * One square: the write button, with a badge over its corner that opens settings when something needs fixing.
 * A plain layout rather than Glance's own, because only a view can keep itself square whatever size the launcher reports.
 */
fun widgetViews(context: Context, state: WidgetState): RemoteViews = RemoteViews(context.packageName, R.layout.widget_square).apply {
    // Until credentials are saved there is nowhere to send an entry to.
    setOnClickPendingIntent(R.id.write, open(context, if (state.configured) CaptureActivity::class.java else SettingsActivity::class.java))
    val badge = badgeText(state)
    if (badge == null) {
        setViewVisibility(R.id.badge, View.GONE)
    } else {
        setViewVisibility(R.id.badge, View.VISIBLE)
        setTextViewText(R.id.badge, badge)
        setContentDescription(R.id.badge, badgeDescription(state))
        setOnClickPendingIntent(R.id.badge, open(context, SettingsActivity::class.java))
    }
}

private fun open(context: Context, activity: Class<*>): PendingIntent =
    PendingIntent.getActivity(context, 0, Intent(context, activity), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
