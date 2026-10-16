package io.github.leoalfaroc.journal.settings

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
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.data.CategoryNotice
import io.github.leoalfaroc.journal.data.PostStatus
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.ui.Corner
import io.github.leoalfaroc.journal.ui.JournalType
import io.github.leoalfaroc.journal.ui.LocalJournalColors
import io.github.leoalfaroc.journal.ui.TextAction

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
)

const val AUTH_ERROR_LINE = "The site rejected these credentials. Entries wait on the phone. Edit them and save again."
const val EDITING_LINE = "Sending uses the saved values until you tap Save."
const val SAVED_LINE = "Saved."

/** The saved password is never shown, not even its length. */
private const val HIDDEN_PASSWORD = "••••••••••••"
const val NO_CATEGORIES_LINE = "Available after Test connection succeeds."
const val PUBLIC_LINE = "Every entry is public as soon as it is sent."
const val EXISTING_POSTS_LINE = "Category and post status apply to day posts created from now on. Existing posts keep theirs."
const val GRACE_LINE = "A sent entry can still be edited or deleted for this long. 0 sends at once."

fun categoryNoticeText(notice: CategoryNotice): String =
    "The category \"${notice.missing}\" no longer exists on your site. Day posts now go to \"${notice.used}\"."

/** `title` is the app's name. */
@Composable
fun SettingsScreen(title: String, ui: SettingsUi, actions: SettingsActions) {
    val colors = LocalJournalColors.current
    val form = ui.form
    val settings = ui.settings
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.semantics { heading() }, style = JournalType.send.copy(fontSize = 22.sp, lineHeight = 28.sp), color = colors.app)

        settings.categoryNotice?.let { notice ->
            Outlined {
                Text(categoryNoticeText(notice), Modifier.weight(1f).padding(vertical = 12.dp), style = JournalType.label, color = colors.app)
                TextAction("OK", actions.dismissCategoryNotice, flush = Alignment.End)
            }
        }

        // Always here, whatever the queue holds, so that the screen keeps its shape.
        Outlined(Modifier.clickable(onClickLabel = "Open the queue", role = Role.Button, onClick = actions.openQueue)) {
            Text("Queue", Modifier.weight(1f), style = JournalType.label, color = colors.app)
            Text(ui.queueSummary, style = JournalType.excerpt, color = colors.you)
        }

        // The sign-in cannot be typed over by accident: it opens with Edit, and only Save stores it.
        val testing = form.test == TestState.Running
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Connection", Modifier.weight(1f).semantics { heading() }, style = JournalType.send, color = colors.app)
            if (!form.editing) TextAction("Edit", actions.edit, flush = Alignment.End)
        }
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
        if (settings.authError) Line(AUTH_ERROR_LINE, colors.danger)
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
            Text("Upload on Wi-Fi only", Modifier.weight(1f), style = JournalType.excerpt, color = colors.you)
            Switch(checked = settings.wifiOnly, onCheckedChange = null)
        }

        Heading("Day post")
        CategoryPicker(settings, actions.chooseCategory)
        Choice("Post status", PostStatus.entries, settings.postStatus, { it.label }, actions.setPostStatus)
        if (settings.postStatus == PostStatus.PUBLIC) Line(PUBLIC_LINE)
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
    Text(text, Modifier.padding(top = 12.dp).semantics { heading() }, style = JournalType.send, color = LocalJournalColors.current.app)
}

/** Something the app says. */
@Composable
private fun Line(text: String, color: Color = LocalJournalColors.current.app) {
    Text(text, style = JournalType.label, color = color)
}

/** A button the width it is given: outlined, or filled for the one that commits. */
@Composable
private fun Action(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    val colors = LocalJournalColors.current
    Box(
        modifier
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Corner)
            .then(if (filled) Modifier.background(colors.app) else Modifier.border(2.dp, colors.app, Corner))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = JournalType.send, color = if (filled) colors.onApp else colors.app)
    }
}

/** A saved value, as text: it can change only after Edit. */
@Composable
private fun Shown(label: String, value: String) {
    val colors = LocalJournalColors.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = JournalType.label, color = colors.quiet)
            Text(value, style = JournalType.excerpt, color = colors.you)
        }
        HorizontalDivider(color = colors.quiet.copy(alpha = 0.4f))
    }
}

@Composable
private fun Outlined(
    modifier: Modifier = Modifier,
    color: Color = LocalJournalColors.current.app,
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
    val colors = LocalJournalColors.current
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
        supportingText = below?.let { { Text(it, style = JournalType.label, color = colors.app) } },
        singleLine = true,
        shape = Corner,
        textStyle = JournalType.excerpt.copy(color = colors.you),
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboard),
    )
}

/** The categories the site had at the last successful `Test connection`. */
@Composable
private fun CategoryPicker(settings: Settings, onChoose: (String) -> Unit) {
    val colors = LocalJournalColors.current
    if (!settings.configured) {
        Outlined(color = colors.quiet) {
            Text("Category", Modifier.weight(1f), style = JournalType.label, color = colors.quiet)
            Text(NO_CATEGORIES_LINE, style = JournalType.label, color = colors.quiet)
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val chosen = settings.categories.firstOrNull { it.slug == settings.categorySlug }
    Box {
        Outlined(Modifier.clickable(onClickLabel = "Choose the category", role = Role.Button) { open = true }) {
            Text("Category", Modifier.weight(1f), style = JournalType.label, color = colors.app)
            Text(chosen?.name ?: settings.categorySlug.orEmpty(), style = JournalType.excerpt, color = colors.you)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            settings.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name, style = JournalType.excerpt, color = colors.you) },
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
    val colors = LocalJournalColors.current
    Column {
        Text(title, Modifier.padding(top = 4.dp), style = JournalType.label, color = colors.app)
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option == chosen, role = Role.RadioButton) { onChoose(option) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = option == chosen, onClick = null)
                Text(label(option), style = JournalType.excerpt, color = colors.you)
            }
        }
    }
}
