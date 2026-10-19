package io.github.leoalfaroc.jot2press.widget

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
import io.github.leoalfaroc.jot2press.AppContainer
import io.github.leoalfaroc.jot2press.Jot2PressApp
import io.github.leoalfaroc.jot2press.R
import io.github.leoalfaroc.jot2press.Trouble
import io.github.leoalfaroc.jot2press.WidgetState
import io.github.leoalfaroc.jot2press.capture.CaptureActivity
import io.github.leoalfaroc.jot2press.settings.SettingsActivity
import io.github.leoalfaroc.jot2press.widgetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** What the widget shows, each time the queue or the settings change. */
fun widgetStates(container: AppContainer): Flow<WidgetState> =
    combine(container.queue.observe(), container.settings.flow) { entries, settings ->
        widgetState(entries, settings, container.clock.millis())
    }.distinctUntilChanged()

class Jot2PressWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = widgetStates((context.applicationContext as Jot2PressApp).container)
        val first = states.first()
        provideContent {
            val state by states.collectAsState(first)
            AndroidRemoteViews(widgetViews(LocalContext.current, state), GlanceModifier.fillMaxSize())
        }
    }
}

class Jot2PressWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = Jot2PressWidget()
}

/** What a screen reader says for the badge, and where touching it leads. */
fun badgeDescription(trouble: Trouble): String = when (trouble) {
    Trouble.SignInRejected -> "The site rejected the sign-in. Opens settings"
    is Trouble.NotSent -> "${trouble.summary} Opens the queue"
}

/**
 * One square: the write button, with a badge over its corner that opens settings when something needs fixing.
 * A plain layout rather than Glance's own, because only a view can keep itself square whatever size the launcher reports.
 */
fun widgetViews(context: Context, state: WidgetState): RemoteViews = RemoteViews(context.packageName, R.layout.widget_square).apply {
    // Until credentials are saved there is nowhere to send an entry to.
    if (state.configured) {
        setOnClickPendingIntent(R.id.write, open(context, WRITE, Intent(context, CaptureActivity::class.java)))
        setContentDescription(R.id.write, "Write a journal entry")
    } else {
        setOnClickPendingIntent(R.id.write, open(context, WRITE, Intent(context, SettingsActivity::class.java)))
        setContentDescription(R.id.write, "Set up the journal. Opens settings")
    }
    val trouble = state.trouble
    if (trouble == null) {
        setViewVisibility(R.id.badge, View.GONE)
    } else {
        setViewVisibility(R.id.badge, View.VISIBLE)
        setTextViewText(R.id.badge, trouble.mark)
        setContentDescription(R.id.badge, badgeDescription(trouble))
        // A rejected sign-in is fixed in settings; entries that could not be sent, in the queue.
        val fix = when (trouble) {
            Trouble.SignInRejected -> Intent(context, SettingsActivity::class.java)
            is Trouble.NotSent -> SettingsActivity.queueIntent(context)
        }
        setOnClickPendingIntent(R.id.badge, open(context, BADGE, fix))
    }
}

/** Request codes, one per thing to touch: Android tells pending intents to the same activity apart by them, not by their extras. */
private const val WRITE = 0
private const val BADGE = 1

private fun open(context: Context, requestCode: Int, intent: Intent): PendingIntent =
    PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
