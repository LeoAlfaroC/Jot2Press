package io.github.leoalfaroc.jot2press.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.leoalfaroc.jot2press.BuildConfig
import io.github.leoalfaroc.jot2press.R
import io.github.leoalfaroc.jot2press.TitleLanguage
import io.github.leoalfaroc.jot2press.data.CategoryNotice
import io.github.leoalfaroc.jot2press.data.PostStatus
import io.github.leoalfaroc.jot2press.data.Settings
import io.github.leoalfaroc.jot2press.ui.Button
import io.github.leoalfaroc.jot2press.ui.Choice
import io.github.leoalfaroc.jot2press.ui.Corner
import io.github.leoalfaroc.jot2press.ui.Field
import io.github.leoalfaroc.jot2press.ui.Heading
import io.github.leoalfaroc.jot2press.ui.Jot2PressType
import io.github.leoalfaroc.jot2press.ui.LinkRow
import io.github.leoalfaroc.jot2press.ui.LocalJot2PressColors
import io.github.leoalfaroc.jot2press.ui.Shown
import io.github.leoalfaroc.jot2press.ui.StatusLine
import io.github.leoalfaroc.jot2press.ui.SwitchRow
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

/** The wordmark for the theme a screen is drawn in: a `-night` folder would follow the phone instead. */
@DrawableRes
fun wordmark(dark: Boolean): Int = if (dark) R.drawable.wordmark_dark else R.drawable.wordmark

/** `title` is the app's name: what TalkBack says for the wordmark. */
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
        Image(painterResource(wordmark(colors.dark)), contentDescription = title, modifier = Modifier.height(34.dp).semantics { heading() })

        settings.categoryNotice?.let { notice ->
            Row(
                Modifier.fillMaxWidth().border(2.dp, colors.ink, Corner).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(categoryNoticeText(notice), Modifier.weight(1f).padding(vertical = 12.dp), style = Jot2PressType.note, color = colors.text)
                TextAction("OK", actions.dismissCategoryNotice)
            }
        }

        // Always here, whatever the queue holds, so that the screen keeps its shape.
        LinkRow("Queue", ui.queueSummary, actions.openQueue, onClickLabel = "Open the queue")

        // The sign-in cannot be typed over by accident: it opens with Edit, and only Save stores it.
        val testing = form.test == TestState.Running
        Heading("Connection") {
            if (!form.editing) TextAction("Edit", actions.edit, flush = Alignment.End)
        }
        if (form.unreadable) StatusLine(UNREADABLE_LINE, danger = true)
        if (form.editing) {
            Field("Site URL", form.siteUrl, form.revision, actions.editSiteUrl, keyboard = KeyboardType.Uri, enabled = !testing)
            Field("Username", form.username, form.revision, actions.editUsername, enabled = !testing)
            Field("Application password", form.password, form.revision, actions.editPassword, keyboard = KeyboardType.Password, hidden = true, enabled = !testing)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (form.hasSaved) Button("Cancel", actions.cancel, Modifier.weight(1f), enabled = !testing)
                Button("Save", actions.save, Modifier.weight(1f), enabled = !testing, filled = true)
            }
        } else {
            Shown("Site URL", form.siteUrl)
            Shown("Username", form.username)
            Shown("Application password", HIDDEN_PASSWORD)
        }
        Button(if (testing) "Testing…" else "Test connection", actions.testConnection, Modifier.fillMaxWidth(), enabled = !testing)
        // Unreadable credentials also hold the queue as rejected ones do; their own line above says what happened.
        if (settings.authError && !form.unreadable) StatusLine(AUTH_ERROR_LINE, danger = true)
        when (val test = form.test) {
            is TestState.Passed -> StatusLine(test.message)
            is TestState.Failed -> StatusLine(test.message, danger = true)
            TestState.Running -> Unit
            TestState.Idle -> when {
                form.saved -> StatusLine(SAVED_LINE)
                form.editing && form.hasSaved -> StatusLine(EDITING_LINE)
            }
        }

        Heading("Sending")
        Field("Grace window, in seconds", form.grace, form.revision, actions.editGrace, keyboard = KeyboardType.Number, help = GRACE_LINE, error = form.graceError)
        SwitchRow("Upload on Wi-Fi only", settings.wifiOnly, actions.setWifiOnly)
        SwitchRow("Attach location", ui.locationOn, actions.setAttachLocation)
        if (form.locationBlocked) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(LOCATION_BLOCKED_LINE, Modifier.weight(1f), style = Jot2PressType.note, color = colors.ink)
                TextAction("Open", actions.openAppSettings, flush = Alignment.End)
            }
        }

        Heading("Day post")
        CategoryPicker(settings, actions.chooseCategory)
        Choice("Post status", PostStatus.entries, settings.postStatus, { it.label }, actions.setPostStatus)
        if (settings.postStatus == PostStatus.PUBLIC) StatusLine(if (ui.locationOn) PUBLIC_LOCATION_LINE else PUBLIC_LINE)
        StatusLine(EXISTING_POSTS_LINE)
        Choice("Title language", TitleLanguage.entries, settings.titleLanguage, { it.label }, actions.setTitleLanguage)
        Field("Title pattern", form.pattern, form.revision, actions.editPattern, help = ui.titlePreview?.let { "Today: $it" }, error = form.patternError)

        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            Modifier.fillMaxWidth().padding(top = 24.dp),
            style = Jot2PressType.note,
            color = colors.quiet,
            textAlign = TextAlign.Center,
        )
    }
}

/** The categories the site had at the last successful `Test connection`. */
@Composable
private fun CategoryPicker(settings: Settings, onChoose: (String) -> Unit) {
    val colors = LocalJot2PressColors.current
    if (!settings.configured) {
        LinkRow("Category", "", onClick = {}, enabled = false)
        // Below the row, not in it: the row cuts a long detail short, and this has to be read whole.
        Text(NO_CATEGORIES_LINE, style = Jot2PressType.note, color = colors.quiet)
        return
    }
    var open by remember { mutableStateOf(false) }
    val chosen = settings.categories.firstOrNull { it.slug == settings.categorySlug }
    Box {
        LinkRow("Category", chosen?.name ?: settings.categorySlug.orEmpty(), onClick = { open = true }, onClickLabel = "Choose the category")
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
