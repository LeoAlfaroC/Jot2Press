package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** The corner of every control and of the capture sheet. */
val Corner = RoundedCornerShape(12.dp)

/** How long `Confirm discard` and `Confirm delete` stay before things go back to normal. */
const val CONFIRM_MILLIS = 4_000L

/**
 * A control that is only its label, on a target of at least 48 dp.
 * `flush` puts the label against that edge, for a control that sits at the edge of the content.
 */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalJot2PressColors.current.app,
    flush: Alignment.Horizontal? = null,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(Corner)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (flush == Alignment.Start) 0.dp else 12.dp, end = if (flush == Alignment.End) 0.dp else 12.dp),
        contentAlignment = when (flush) {
            Alignment.Start -> Alignment.CenterStart
            Alignment.End -> Alignment.CenterEnd
            else -> Alignment.Center
        },
    ) {
        Text(label, style = Jot2PressType.label.copy(lineHeight = 1.25.em), color = color)
    }
}
