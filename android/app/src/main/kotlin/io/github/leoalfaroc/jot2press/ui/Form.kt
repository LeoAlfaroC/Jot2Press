package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.leoalfaroc.jot2press.R

/**
 * A text field that keeps what is typed, caret included, and tells the model. It starts again from `value` only when `revision` changes.
 * The label and the line below are drawn inside the field, so that TalkBack, and a test, find them as one with it.
 * `error`, when there is one, takes the place of `help`.
 */
@Composable
fun Field(
    label: String,
    value: String,
    revision: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    hidden: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalJot2PressColors.current
    var text by remember(revision) { mutableStateOf(value) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when {
        error != null -> colors.danger
        focused -> colors.ink
        else -> colors.quiet
    }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = Jot2PressType.body.copy(color = if (enabled) colors.text else colors.quiet),
        cursorBrush = SolidColor(colors.ink),
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard),
        singleLine = true,
        interactionSource = interaction,
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = Jot2PressType.label, color = if (focused) colors.ink else colors.quiet)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).border(2.dp, border, Corner).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    inner()
                }
                (error ?: help)?.let { Text(it, style = Jot2PressType.note, color = if (error != null) colors.danger else colors.ink) }
            }
        },
    )
}

/** A saved value, as text: it can change only after Edit. */
@Composable
fun Shown(label: String, value: String) {
    val colors = LocalJot2PressColors.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = Jot2PressType.label, color = colors.quiet)
            Text(value, style = Jot2PressType.body, color = colors.text)
        }
        HorizontalDivider(color = colors.line)
    }
}

/** A setting that is on or off. The whole row toggles it, and TalkBack reads it as a switch. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = LocalJot2PressColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = Jot2PressType.body, color = colors.text)
        // On: an ink track and a large thumb. Off: an outline and a small quiet thumb, so the two differ in more than colour.
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .then(if (checked) Modifier.background(colors.ink) else Modifier.border(2.dp, colors.quiet, CircleShape))
                .padding(horizontal = if (checked) 6.dp else 8.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(if (checked) 20.dp else 16.dp).clip(CircleShape).background(if (checked) colors.onInk else colors.quiet))
        }
    }
}

/** One of a few options, as pills that wrap when they do not fit; the chosen one is filled. TalkBack reads a group of radio buttons. */
@Composable
fun <T> Choice(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    val colors = LocalJot2PressColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Jot2PressType.label, color = colors.quiet)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val chosen = option == selected
                // The pill is 40 high; the row leaves 48, which Compose gives to the pill's touch area.
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .then(if (chosen) Modifier.background(colors.ink) else Modifier.border(2.dp, colors.ink, CircleShape))
                            .selectable(chosen, role = Role.RadioButton) { onSelect(option) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(name(option), style = Jot2PressType.label, color = if (chosen) colors.onInk else colors.ink)
                    }
                }
            }
        }
    }
}

/** A row that opens something: the queue, the list of categories. A long detail is cut short, never the label. */
@Composable
fun LinkRow(
    label: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalJot2PressColors.current
    val tone = if (enabled) colors.ink else colors.quiet
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(Corner)
            .border(2.dp, tone, Corner)
            .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Jot2PressType.label, color = tone)
        Text(detail, Modifier.weight(1f), style = Jot2PressType.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        if (enabled) Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, Modifier.size(20.dp), tint = colors.ink)
    }
}

/** A section's heading, with an optional action at its end. With the screen's 12 dp between rows, it stands 24 below what comes before. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = Jot2PressType.heading, color = LocalJot2PressColors.current.ink)
        action?.invoke()
    }
}

/** Something the app says about a setting: a hint, a result, an error. */
@Composable
fun StatusLine(text: String, danger: Boolean = false) {
    val colors = LocalJot2PressColors.current
    Text(text, style = Jot2PressType.note, color = if (danger) colors.danger else colors.ink)
}
