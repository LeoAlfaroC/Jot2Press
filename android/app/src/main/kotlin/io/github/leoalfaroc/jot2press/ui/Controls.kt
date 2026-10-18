package io.github.leoalfaroc.jot2press.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** How long `Confirm discard` and `Confirm delete` stay before things go back to normal. */
const val CONFIRM_MILLIS = 4_000L

/** The press area of text and icon actions, and the corner of fields and outlined rows. */
val Corner = RoundedCornerShape(14.dp)

/** The alert mark: a count, or `!`. The line beside it says what it means, so TalkBack skips it. */
@Composable
fun Badge(mark: String, modifier: Modifier = Modifier) {
    val colors = LocalJot2PressColors.current
    Box(
        modifier
            .semantics { hideFromAccessibility() }
            .heightIn(min = 22.dp)
            .widthIn(min = 22.dp)
            .clip(CircleShape)
            .background(Alert)
            .border(2.dp, colors.paper, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(mark, style = Jot2PressType.badge, color = OnAlert)
    }
}

/**
 * A control that is only its label, on a target of at least 48 dp. `danger` is for `Confirm delete` and `Confirm discard` only.
 * `flush` puts the label against that edge, for a control that sits at the edge of the content.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    flush: Alignment.Horizontal? = null,
) {
    val colors = LocalJot2PressColors.current
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
        Text(text, style = Jot2PressType.label, color = if (danger) colors.danger else colors.ink)
    }
}

/** An icon without a border, on a 48 dp target; `label` is what TalkBack says. Disabled, it turns quiet and stops answering. */
@Composable
fun IconButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val colors = LocalJot2PressColors.current
    Box(
        modifier
            .size(48.dp)
            .clip(Corner)
            .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = if (enabled) colors.ink else colors.quiet)
    }
}

/**
 * A pill the width it is given: filled for the one action that commits, outlined otherwise.
 * Disabled, a filled button drops to its outline rather than fading, and an outlined one turns quiet.
 */
@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val colors = LocalJot2PressColors.current
    val fill = filled && enabled
    val tone = if (enabled || filled) colors.ink else colors.quiet
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .then(if (fill) Modifier.background(colors.ink) else Modifier.border(2.dp, tone, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Jot2PressType.button, color = if (fill) colors.onInk else tone)
    }
}
