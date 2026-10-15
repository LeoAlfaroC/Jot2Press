package io.github.leoalfaroc.journal.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.leoalfaroc.journal.ui.CONFIRM_MILLIS
import io.github.leoalfaroc.journal.ui.Corner
import io.github.leoalfaroc.journal.ui.JournalType
import io.github.leoalfaroc.journal.ui.LocalJournalColors
import io.github.leoalfaroc.journal.ui.TextAction
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What the dialog can ask for. */
class CaptureActions(
    val type: (String) -> Unit = {},
    val send: () -> Unit = {},
    val askDiscard: () -> Unit = {},
    val keep: () -> Unit = {},
    val confirmDiscard: () -> Unit = {},
    val editLast: () -> Unit = {},
    val askDeleteLast: () -> Unit = {},
    val confirmDeleteLast: () -> Unit = {},
    val refresh: () -> Unit = {},
)

/** The whole window: the sheet at the bottom, on top of the keyboard. A tap anywhere else closes the dialog. */
@Composable
fun CaptureScreen(ui: CaptureUi, actions: CaptureActions, now: () -> Long, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Beside the sheet, not around it: a clickable parent would merge the sheet's semantics into its own.
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Close" }
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
        if (ui.ready) {
            CaptureSheet(
                ui,
                actions,
                now,
                Modifier
                    .align(Alignment.BottomCenter)
                    // Taps on the sheet itself must not fall through to the layer that closes the dialog.
                    .pointerInput(Unit) { detectTapGestures { } },
                // The keyboard, or the navigation bar when the keyboard is closed: the sheet's white runs under them.
                insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
        }
    }
}

@Composable
fun CaptureSheet(
    ui: CaptureUi,
    actions: CaptureActions,
    now: () -> Long,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets(0),
) {
    val colors = LocalJournalColors.current
    val current by rememberUpdatedState(actions)
    val confirmingDelete = ui.lastEntry?.confirmingDelete == true

    LaunchedEffect(ui.confirmingDiscard, confirmingDelete) {
        if (ui.confirmingDiscard || confirmingDelete) {
            delay(CONFIRM_MILLIS)
            current.keep()
        }
    }
    LaunchedEffect(ui.nextChangeAt) {
        val at = ui.nextChangeAt ?: return@LaunchedEffect
        delay((at - now()).coerceAtLeast(0))
        current.refresh()
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.sheet, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .windowInsetsPadding(insets)
            .semantics { paneTitle = "New entry" }
            // The line about the last entry brings its own height, so the sheet gives it less room above.
            .padding(start = 20.dp, end = 20.dp, top = if (ui.lastEntry != null) 6.dp else 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ui.lastEntry?.let { LastEntryLine(it, canEdit = ui.canEditLast, actions = actions) }
        Header(ui, actions)
        Editor(ui, actions)
        SendButton(enabled = ui.canSend, onClick = actions.send)
    }
}

/** One plain line, above the day so that it cannot be read as the title of the entry being written. */
@Composable
private fun LastEntryLine(last: LastEntry, canEdit: Boolean, actions: CaptureActions) {
    val colors = LocalJournalColors.current
    if (last.confirmingDelete) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextAction("Confirm delete", actions.confirmDeleteLast, color = colors.danger, flush = Alignment.Start)
            TextAction("Keep", actions.keep, flush = Alignment.End)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.app, fontSize = JournalType.label.fontSize, fontWeight = FontWeight.Medium)) {
                    append(last.time)
                }
                append("  ")
                append(last.excerpt)
            },
            modifier = Modifier.weight(1f),
            style = JournalType.excerpt,
            color = colors.you,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The last control on the line sits against the edge, under `Discard`.
        TextAction("Delete", actions.askDeleteLast, flush = if (canEdit) null else Alignment.End)
        if (canEdit) TextAction("Edit", actions.editLast, flush = Alignment.End)
    }
}

/** The day the entry will be added to, with `Discard`; or, after `Discard`, the confirmation on the opposite side. */
@Composable
private fun Header(ui: CaptureUi, actions: CaptureActions) {
    val colors = LocalJournalColors.current
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.confirmingDiscard) {
            TextAction("Confirm discard", actions.confirmDiscard, color = colors.danger, flush = Alignment.Start)
            TextAction("Keep", actions.keep, flush = Alignment.End)
        } else {
            Day(ui.day)
            if (ui.hasContent) TextAction("Discard", actions.askDiscard, flush = Alignment.End)
        }
    }
}

/** Always in English, whatever the language of the day post's title. */
@Composable
private fun Day(day: LocalDate) {
    val colors = LocalJournalColors.current
    val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val month = day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$weekday ${day.dayOfMonth} $month ${day.year}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(day.dayOfMonth.toString(), style = JournalType.dayNumber, color = colors.app)
        Text("$weekday\n$month ${day.year}", style = JournalType.label, color = colors.app)
    }
}

@Composable
private fun Editor(ui: CaptureUi, actions: CaptureActions) {
    val colors = LocalJournalColors.current
    // The field keeps its own value, caret included. It starts again from the model only when the model replaced the text.
    var field by remember(ui.revision) { mutableStateOf(TextFieldValue(ui.text, TextRange(ui.text.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // The keyboard is open on arrival. It can only be asked for once the window has the focus.
    LaunchedEffect(windowFocused) {
        if (windowFocused) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    BasicTextField(
        value = field,
        onValueChange = {
            val changed = it.text != field.text
            field = it
            if (changed) actions.type(it.text)
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        textStyle = JournalType.entry.copy(color = colors.you),
        cursorBrush = SolidColor(colors.app),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 3,
        maxLines = 7,
        decorationBox = { inner ->
            Box {
                if (field.text.isEmpty()) Text("What happened?", style = JournalType.entry, color = colors.app)
                inner()
            }
        },
    )
}

/** The only filled control. Disabled, it is an outline, not a faded button. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalJournalColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Corner)
            .then(if (enabled) Modifier.background(colors.app) else Modifier.border(2.dp, colors.app, Corner))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("Send", style = JournalType.send, color = if (enabled) colors.onApp else colors.app)
    }
}
