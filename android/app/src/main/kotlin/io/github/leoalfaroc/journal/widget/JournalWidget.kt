package io.github.leoalfaroc.journal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.leoalfaroc.journal.AppContainer
import io.github.leoalfaroc.journal.JournalApp
import io.github.leoalfaroc.journal.R
import io.github.leoalfaroc.journal.WidgetState
import io.github.leoalfaroc.journal.capture.CaptureActivity
import io.github.leoalfaroc.journal.queueFacts
import io.github.leoalfaroc.journal.settings.SettingsActivity
import io.github.leoalfaroc.journal.ui.DarkColors
import io.github.leoalfaroc.journal.ui.LightColors
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
            WidgetContent(state)
        }
    }
}

class JournalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = JournalWidget()
}

/** What a screen reader says for the status area. */
fun statusDescription(state: WidgetState): String = listOfNotNull(
    "Needs attention".takeIf { state.alert },
    "${state.count} in the queue".takeIf { state.count > 0 },
    "Unfinished draft".takeIf { state.hasDraft },
    "Open settings",
).joinToString(". ")

/** Two tap targets: the write button, and the status area, which opens settings. */
@Composable
fun WidgetContent(state: WidgetState) {
    // The backgrounds are drawables, which take the same colours from colors.xml.
    val app = ColorProvider(day = LightColors.app, night = DarkColors.app)
    val label = TextStyle(color = app, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Row(
        GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_sheet)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .background(ImageProvider(R.drawable.widget_write))
                // Until `Test connection` has succeeded there is nowhere to send an entry to.
                .clickable(if (state.configured) actionStartActivity<CaptureActivity>() else actionStartActivity<SettingsActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            Text("Write", style = label.copy(color = ColorProvider(day = LightColors.onApp, night = DarkColors.onApp)))
        }
        Spacer(GlanceModifier.width(6.dp))
        Row(
            GlanceModifier
                .width(64.dp)
                .fillMaxHeight()
                .then(if (state.alert) GlanceModifier.background(ImageProvider(R.drawable.widget_alert)) else GlanceModifier)
                .semantics { contentDescription = statusDescription(state) }
                .clickable(actionStartActivity<SettingsActivity>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.alert) Text("!", style = label)
            if (state.alert && state.count > 0) Spacer(GlanceModifier.width(4.dp))
            if (state.count > 0) Text(state.count.toString(), style = label)
            if (state.hasDraft) {
                if (state.alert || state.count > 0) Spacer(GlanceModifier.width(4.dp))
                Image(ImageProvider(R.drawable.ic_draft), contentDescription = null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(app))
            }
        }
    }
}
