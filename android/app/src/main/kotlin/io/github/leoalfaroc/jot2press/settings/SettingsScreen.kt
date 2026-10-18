package io.github.leoalfaroc.jot2press.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.leoalfaroc.jot2press.TitleLanguage
import io.github.leoalfaroc.jot2press.data.CategoryNotice
import io.github.leoalfaroc.jot2press.data.PostStatus
import io.github.leoalfaroc.jot2press.data.Settings
import io.github.leoalfaroc.jot2press.ui.Corner
import io.github.leoalfaroc.jot2press.ui.Jot2PressType
import io.github.leoalfaroc.jot2press.ui.LocalJot2PressColors
import io.github.leoalfaroc.jot2press.ui.TextAction

/** What the settings screen can ask for. */
class SettingsActions(
    val openQueue: () -> Unit = {},
    val dismissCategoryNotice: () -> Unit = {},
    val editSiteUrl: (String) -> Unit = {},
    val editUsername: (String) -> Unit = {},
    val editPassword: (String) -> Unit = {},
    val testConnection: () -> Unit = {},
    val edit: () -> Unit = {},
    val cancel: () -> Unit = {},
    val save: () -> Unit = {},
    val editGrace: (String) -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val chooseCategory: (String) -> Unit = {},
    val setPostStatus: (PostStatus) -> Unit = {},
    val setTitleLanguage: (TitleLanguage) -> Unit = {},
    val editPattern: (String) -> Unit = {},
    /** On goes through Android's permission request first. */
    val setAttachLocation: (Boolean) -> Unit = {},
    /** Opens the app's page in Android settings, where a blocked permission is given. */
    val openAppSettings: () -> Unit = {},
)

const val UNREADABLE_LINE = "The saved sign-in can no longer be read on this phone. Enter it again."
const val AUTH_ERROR_LINE = "The site rejected these credentials. Entries wait on the phone. Edit them and save again."
const val EDITING_LINE = "Sending uses the saved values until you tap Save."
const val SAVED_LINE = "Saved."

/** The saved password is never shown, not even its length. */
private const val HIDDEN_PASSWORD = "••••••••••••"
const val NO_CATEGORIES_LINE = "Available after Test connection succeeds."
const val PUBLIC_LINE = "Every entry is public as soon as it is sent, with its photos."
const val PUBLIC_LOCATION_LINE = "Every entry is public as soon as it is sent, with its photos and its location."
const val LOCATION_BLOCKED_LINE = "Location is blocked for this app in Android settings."
const val EXISTING_POSTS_LINE = "Category and post status apply to day posts created from now on. Existing posts keep theirs."
const val GRACE_LINE = "A sent entry can still be edited or deleted for this long. 0 sends at once."

fun categoryNoticeText(notice: CategoryNotice): String =
    "The category \"${notice.missing}\" no longer exists on your site. Day posts now go to \"${notice.used}\"."

/** `title` is the app's name. */
@Composable
fun SettingsScreen(title: String, ui: SettingsUi, actions: SettingsActions) {
    val colors = LocalJot2PressColors.current
    val form = ui.form
    val settings = ui.settings
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, style = Jot2PressType.button.copy(fontSize = 22.sp, lineHeight = 28.sp), color = colors.ink)

        settings.categoryNotice?.let { notice ->
            Outlined {
                Text(categoryNoticeText(notice), Modifier.weight(1f).padding(vertical = 12.dp), style = Jot2PressType.label, color = colors.ink)
                TextAction("OK", actions.dismissCategoryNotice, flush = Alignment.End)
            }
        }

        // Always here, whatever the queue holds, so that the screen keeps its shape.
        Outlined(Modifier.clickable(onClickLabel = "Open the queue", role = Role.Button, onClick = actions.openQueue)) {
            Text("Queue", Modifier.weight(1f), style = Jot2PressType.label, color = colors.ink)
            Text(ui.queueSummary, style = Jot2PressType.body, color = colors.text)
        }

        // The sign-in cannot be typed over by accident: it opens with Edit, and only Save stores it.
        val testing = form.test == TestState.Running
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Connection", Modifier.weight(1f).semantics { heading() }, style = Jot2PressType.button, color = colors.ink)
            if (!form.editing) TextAction("Edit", actions.edit, flush = Alignment.End)
        }
        if (form.unreadable) Line(UNREADABLE_LINE, colors.danger)
        if (form.editing) {
            Field("Site URL", form.siteUrl, form.revision, actions.editSiteUrl, keyboard = KeyboardType.Uri, enabled = !testing)
            Field("Username", form.username, form.revision, actions.editUsername, enabled = !testing)
            Field("Application password", form.password, form.revision, actions.editPassword, keyboard = KeyboardType.Password, hidden = true, enabled = !testing)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (form.hasSaved) Action("Cancel", actions.cancel, Modifier.weight(1f), enabled = !testing)
                Action("Save", actions.save, Modifier.weight(1f), filled = true, enabled = !testing)
            }
        } else {
            Shown("Site URL", form.siteUrl)
            Shown("Username", form.username)
            Shown("Application password", HIDDEN_PASSWORD)
        }
        Action(if (testing) "Testing…" else "Test connection", actions.testConnection, Modifier.fillMaxWidth(), enabled = !testing)
        // Unreadable credentials also hold the queue as rejected ones do; their own line above says what happened.
        if (settings.authError && !form.unreadable) Line(AUTH_ERROR_LINE, colors.danger)
        when (val test = form.test) {
            is TestState.Passed -> Line(test.message)
            is TestState.Failed -> Line(test.message, colors.danger)
            TestState.Running -> Unit
            TestState.Idle -> when {
                form.saved -> Line(SAVED_LINE)
                form.editing && form.hasSaved -> Line(EDITING_LINE)
            }
        }

        Heading("Sending")
        Field("Grace window, in seconds", form.grace, form.revision, actions.editGrace, keyboard = KeyboardType.Number, below = form.graceError ?: GRACE_LINE)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(settings.wifiOnly, role = Role.Switch, onValueChange = actions.setWifiOnly),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Upload on Wi-Fi only", Modifier.weight(1f), style = Jot2PressType.body, color = colors.text)
            Switch(checked = settings.wifiOnly, onCheckedChange = null)
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(ui.locationOn, role = Role.Switch, onValueChange = actions.setAttachLocation),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Attach location", Modifier.weight(1f), style = Jot2PressType.body, color = colors.text)
            Switch(checked = ui.locationOn, onCheckedChange = null)
        }
        if (form.locationBlocked) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(LOCATION_BLOCKED_LINE, Modifier.weight(1f), style = Jot2PressType.label, color = colors.ink)
                TextAction("Open", actions.openAppSettings, flush = Alignment.End)
            }
        }

        Heading("Day post")
        CategoryPicker(settings, actions.chooseCategory)
        Choice("Post status", PostStatus.entries, settings.postStatus, { it.label }, actions.setPostStatus)
        if (settings.postStatus == PostStatus.PUBLIC) Line(if (ui.locationOn) PUBLIC_LOCATION_LINE else PUBLIC_LINE)
        Line(EXISTING_POSTS_LINE)
        Choice("Title language", TitleLanguage.entries, settings.titleLanguage, { it.label }, actions.setTitleLanguage)
        Field(
            "Title pattern",
            form.pattern,
            form.revision,
            actions.editPattern,
            below = form.patternError ?: ui.titlePreview?.let { "Today: $it" },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 12.dp).semantics { heading() }, style = Jot2PressType.button, color = LocalJot2PressColors.current.ink)
}

/** Something the app says. */
@Composable
private fun Line(text: String, color: Color = LocalJot2PressColors.current.ink) {
    Text(text, style = Jot2PressType.label, color = color)
}

/** A button the width it is given: outlined, or filled for the one that commits. */
@Composable
private fun Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    val colors = LocalJot2PressColors.current
    Box(
        modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Corner)
            .then(if (filled) Modifier.background(colors.ink) else Modifier.border(2.dp, colors.ink, Corner))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Jot2PressType.button, color = if (filled) colors.onInk else colors.ink)
    }
}

/** A saved value, as text: it can change only after Edit. */
@Composable
private fun Shown(label: String, value: String) {
    val colors = LocalJot2PressColors.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = Jot2PressType.label, color = colors.quiet)
            Text(value, style = Jot2PressType.body, color = colors.text)
        }
        HorizontalDivider(color = colors.quiet.copy(alpha = 0.4f))
    }
}

@Composable
private fun Outlined(
    modifier: Modifier = Modifier,
    color: Color = LocalJot2PressColors.current.ink,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(Corner)
            .border(2.dp, color, Corner)
            .then(modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * A text field that keeps what is typed, caret included, and tells the model.
 * It starts again from `value` only when `revision` changes.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    revision: Int,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    hidden: Boolean = false,
    below: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalJot2PressColors.current
    var text by remember(revision) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        supportingText = below?.let { { Text(it, style = Jot2PressType.label, color = colors.ink) } },
        singleLine = true,
        shape = Corner,
        textStyle = Jot2PressType.body.copy(color = colors.text),
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard),
    )
}

/** The categories the site had at the last successful `Test connection`. */
@Composable
private fun CategoryPicker(settings: Settings, onChoose: (String) -> Unit) {
    val colors = LocalJot2PressColors.current
    if (!settings.configured) {
        Outlined(color = colors.quiet) {
            Text("Category", Modifier.weight(1f), style = Jot2PressType.label, color = colors.quiet)
            Text(NO_CATEGORIES_LINE, style = Jot2PressType.label, color = colors.quiet)
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val chosen = settings.categories.firstOrNull { it.slug == settings.categorySlug }
    Box {
        Outlined(Modifier.clickable(onClickLabel = "Choose the category", role = Role.Button) { open = true }) {
            Text("Category", Modifier.weight(1f), style = Jot2PressType.label, color = colors.ink)
            Text(chosen?.name ?: settings.categorySlug.orEmpty(), style = Jot2PressType.body, color = colors.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            settings.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name, style = Jot2PressType.body, color = colors.text) },
                    onClick = {
                        open = false
                        onChoose(category.slug)
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    val colors = LocalJot2PressColors.current
    Column {
        Text(title, Modifier.padding(top = 4.dp), style = Jot2PressType.label, color = colors.ink)
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option == chosen, role = Role.RadioButton) { onChoose(option) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = option == chosen, onClick = null)
                Text(label(option), style = Jot2PressType.body, color = colors.text)
            }
        }
    }
}
