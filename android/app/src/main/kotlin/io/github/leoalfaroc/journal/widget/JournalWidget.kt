package io.github.leoalfaroc.journal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
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

private val BADGE = 26.dp

/** One square: the write button, with a badge over its corner that opens settings when something needs fixing. */
@Composable
fun WidgetContent(state: WidgetState) {
    // The backgrounds are drawables, which take the same colours from colors.xml.
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(GlanceModifier.fillMaxSize().padding(6.dp)) {
            Box(
                GlanceModifier
                    .fillMaxSize()
                    .background(ImageProvider(R.drawable.widget_write))
                    // Until credentials are saved there is nowhere to send an entry to.
                    .clickable(if (state.configured) actionStartActivity<CaptureActivity>() else actionStartActivity<SettingsActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Write",
                    style = TextStyle(color = ColorProvider(day = LightColors.onApp, night = DarkColors.onApp), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
        badgeText(state)?.let { text ->
            Box(
                (if (text.length == 1) GlanceModifier.size(BADGE) else GlanceModifier.height(BADGE).padding(horizontal = 8.dp))
                    .background(ImageProvider(R.drawable.widget_badge))
                    .semantics { contentDescription = badgeDescription(state) }
                    .clickable(actionStartActivity<SettingsActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = TextStyle(color = ColorProvider(day = Color.White, night = Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}
