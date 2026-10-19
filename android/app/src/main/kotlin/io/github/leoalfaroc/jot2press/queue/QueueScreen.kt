package io.github.leoalfaroc.jot2press.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.leoalfaroc.jot2press.R
import io.github.leoalfaroc.jot2press.data.Entry
import io.github.leoalfaroc.jot2press.data.EntryState
import io.github.leoalfaroc.jot2press.data.Settings
import io.github.leoalfaroc.jot2press.data.day
import io.github.leoalfaroc.jot2press.data.isStuck
import io.github.leoalfaroc.jot2press.data.time
import io.github.leoalfaroc.jot2press.data.withPhotos
import io.github.leoalfaroc.jot2press.ui.CONFIRM_MILLIS
import io.github.leoalfaroc.jot2press.ui.IconButton
import io.github.leoalfaroc.jot2press.ui.Jot2PressType
import io.github.leoalfaroc.jot2press.ui.LocalJot2PressColors
import io.github.leoalfaroc.jot2press.ui.TextAction
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One entry as the queue screen lists it. It cannot be opened or edited here. */
data class QueueRow(
    val id: String,
    /** `14:32`, or `11 Oct · 14:32` for an entry of another day. */
    val time: String,
    val text: String,
    val status: String,
    /** What the server last answered, for an entry it refused or failed on. */
    val detail: String? = null,
    val canRetry: Boolean = false,
    /** False for the entry being sent: it may already be on the site. */
    val canDelete: Boolean = true,
)

data class QueueUi(
    /** What holds the whole queue back, most serious first. */
    val messages: List<String> = emptyList(),
    /** Newest first. */
    val rows: List<QueueRow> = emptyList(),
)

const val AUTH_MESSAGE = "Nothing is being sent: the site rejected the credentials. Fix this under Connection in settings."
const val STUCK_MESSAGE = "The site keeps failing on an entry, and the others wait behind it. The app keeps trying. The cause is on the site."
const val WIFI_MESSAGE = "Waiting for Wi-Fi."
const val EMPTY_MESSAGE = "Nothing is waiting to be sent."

/** The state of an entry the site refused: the one state shown in danger. */
const val FAILED_STATUS = "Failed"

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val OTHER_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** `entries` as `Queue.observe` gives them: newest first, the draft among them. */
fun queueUi(
    entries: List<Entry>,
    settings: Settings,
    clock: Clock,
    onUnmeteredNetwork: Boolean,
    photoCounts: Map<String, Int> = emptyMap(),
    /** The id of the entry being sent at this moment. */
    sending: String? = null,
): QueueUi {
    val now = clock.millis()
    val today = LocalDate.now(clock)
    val queued = entries.filter { it.state != EntryState.DRAFT }
    val pending = queued.any { it.state == EntryState.PENDING }
    return QueueUi(
        messages = listOfNotNull(
            AUTH_MESSAGE.takeIf { settings.authError },
            STUCK_MESSAGE.takeIf { queued.any { it.isStuck(now) } },
            WIFI_MESSAGE.takeIf { settings.wifiOnly && pending && !onUnmeteredNetwork && !settings.authError },
        ),
        rows = queued.map { entry ->
            val since = entry.failingSince?.let { HOUR.format(Instant.ofEpochMilli(it).atZone(clock.zone)) }
            val day = entry.day
            QueueRow(
                id = entry.id,
                time = listOfNotNull(day?.takeIf { it != today }?.let(OTHER_DAY::format), entry.time).joinToString(" · "),
                text = withPhotos(photoCounts[entry.id] ?: 0, entry.text.trim().replace(Regex("\\s+"), " ")),
                status = when {
                    entry.state == EntryState.GRACE -> "In its grace window"
                    entry.state == EntryState.FAILED -> FAILED_STATUS
                    entry.id == sending -> "Sending"
                    since != null -> "Server error since $since · retrying"
                    else -> "Waiting"
                },
                detail = entry.lastError,
                canRetry = entry.state == EntryState.FAILED,
                canDelete = entry.id != sending,
            )
        },
    )
}

@Composable
fun QueueScreen(ui: QueueUi, onRetry: (String) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    val colors = LocalJot2PressColors.current
    val haptics = LocalHapticFeedback.current
    // The id of the entry whose `Delete` was tapped, until it is confirmed, kept, or 4 seconds pass.
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(confirming) {
        if (confirming != null) {
            delay(CONFIRM_MILLIS)
            confirming = null
        }
    }

    Column(Modifier.fillMaxSize().background(colors.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(R.drawable.ic_back, "Back", onBack, onClickLabel = "Back to settings")
            Text("Queue", Modifier.padding(start = 4.dp).semantics { heading() }, style = Jot2PressType.heading, color = colors.ink)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.messages) { message ->
                Text(
                    message,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    style = Jot2PressType.note,
                    color = if (message == AUTH_MESSAGE) colors.danger else colors.ink,
                )
            }
            if (ui.rows.isEmpty()) {
                item {
                    Text(EMPTY_MESSAGE, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), style = Jot2PressType.note, color = colors.quiet)
                }
            }
            items(ui.rows, key = { it.id }) { row ->
                HorizontalDivider(color = colors.line)
                EntryRow(
                    row,
                    confirming = confirming == row.id,
                    onRetry = { onRetry(row.id) },
                    onAskDelete = { confirming = row.id },
                    onKeep = { confirming = null },
                    onConfirmDelete = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        confirming = null
                        onDelete(row.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    row: QueueRow,
    confirming: Boolean,
    onRetry: () -> Unit,
    onAskDelete: () -> Unit,
    onKeep: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val colors = LocalJot2PressColors.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(Jot2PressType.label.toSpanStyle().copy(color = colors.ink)) { append(row.time) }
                append("  ")
                withStyle(SpanStyle(color = if (row.status == FAILED_STATUS) colors.danger else colors.quiet)) { append(row.status) }
            },
            style = Jot2PressType.note,
        )
        // Two lines, not one: the row keeps enough of the entry to say which it is.
        Text(row.text, style = Jot2PressType.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        row.detail?.let { Text(it, style = Jot2PressType.note, color = colors.quiet) }
        // `Confirm delete` appears on the side opposite to `Delete`, so that a double tap in one place cannot delete.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (confirming && row.canDelete) {
                TextAction("Confirm delete", onConfirmDelete, danger = true, flush = Alignment.Start)
                TextAction("Keep", onKeep, flush = Alignment.End)
            } else {
                Spacer(Modifier.size(0.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.canRetry) TextAction("Retry", onRetry)
                    if (row.canDelete) TextAction("Delete", onAskDelete, flush = Alignment.End)
                }
            }
        }
    }
}
