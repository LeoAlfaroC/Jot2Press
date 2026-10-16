package io.github.leoalfaroc.journal.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.data.Category
import io.github.leoalfaroc.journal.data.CategoryNotice
import io.github.leoalfaroc.journal.data.PostStatus
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.ui.JournalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = SettingsActions(
        openQueue = { calls += "openQueue" },
        dismissCategoryNotice = { calls += "dismissCategoryNotice" },
        editSiteUrl = { calls += "siteUrl:$it" },
        editUsername = { calls += "username:$it" },
        editPassword = { calls += "password:$it" },
        testConnection = { calls += "testConnection" },
        edit = { calls += "edit" },
        cancel = { calls += "cancel" },
        save = { calls += "save" },
        editGrace = { calls += "grace:$it" },
        setWifiOnly = { calls += "wifiOnly:$it" },
        chooseCategory = { calls += "category:$it" },
        setPostStatus = { calls += "postStatus:$it" },
        setTitleLanguage = { calls += "titleLanguage:$it" },
        editPattern = { calls += "pattern:$it" },
    )
    private val form = SettingsForm(revision = 1, editing = true, grace = "60", pattern = Titles.DEFAULT_PATTERN)
    private val fresh = SettingsUi(form, Settings(), queueSummary = "Empty", titlePreview = "2026-10-11 · Sunday, October 11")
    private val stored = form.copy(editing = false, hasSaved = true, siteUrl = "https://example.com/blog", username = "leo", password = "abcd efgh")
    private val connected = Settings(
        configured = true,
        categorySlug = "journal",
        categories = listOf(Category("journal", "Journal"), Category("travel", "Travel")),
        defaultCategory = Category("journal", "Journal"),
    )

    private fun show(ui: SettingsUi) {
        compose.setContent { JournalTheme { SettingsScreen("The App", ui, actions) } }
    }

    @Test
    fun `it is titled with the app's name and starts with the queue row`() {
        show(fresh.copy(queueSummary = "1 failed, 2 waiting"))

        compose.onNodeWithText("The App").assertExists()
        compose.onNodeWithText("1 failed, 2 waiting").assertExists()
        compose.onNodeWithText("Queue").performClick()
        assertEquals(listOf("openQueue"), calls)
    }

    @Test
    fun `the first time, what is typed goes to the model, and Save and Test connection ask for theirs`() {
        show(fresh)

        compose.onNodeWithText("Site URL").performTextReplacement("example.com")
        compose.onNodeWithText("Username").performTextReplacement("leo")
        compose.onNodeWithText("Application password").performTextReplacement("abcd")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.onNodeWithText("Test connection").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("siteUrl:example.com", "username:leo", "password:abcd", "save", "testConnection"), calls)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText(AUTH_ERROR_LINE).assertDoesNotExist()
    }

    @Test
    fun `saved credentials are shown as text, with the password hidden, until Edit`() {
        show(fresh.copy(settings = connected, form = stored))

        compose.onNodeWithText("https://example.com/blog").assertExists()
        compose.onNodeWithText("leo").assertExists()
        compose.onNodeWithText("abcd efgh").assertDoesNotExist()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.onNodeWithText("Edit").performClick()

        assertEquals(listOf("testConnection", "edit"), calls)
    }

    @Test
    fun `while saved credentials are edited, Cancel and Save are offered above Test connection`() {
        show(fresh.copy(settings = connected, form = stored.copy(editing = true)))

        compose.onNodeWithText(EDITING_LINE).assertExists()
        val save = compose.onNodeWithText("Save").getUnclippedBoundsInRoot()
        val test = compose.onNodeWithText("Test connection").getUnclippedBoundsInRoot()
        assertTrue(save.bottom <= test.top)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf("cancel", "save"), calls)
    }

    @Test
    fun `while the test runs the fields and the buttons wait`() {
        show(fresh.copy(settings = connected, form = stored.copy(editing = true, test = TestState.Running)))

        compose.onNodeWithText("Testing…").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithText("Application password").assertIsNotEnabled()
    }

    @Test
    fun `the result of the test is shown`() {
        show(fresh.copy(form = form.copy(test = TestState.Passed("Connected as leo. Upload limit: 64 MB."))))
        compose.onNodeWithText("Connected as leo. Upload limit: 64 MB.").assertExists()
    }

    @Test
    fun `a save is confirmed`() {
        show(fresh.copy(settings = connected, form = stored.copy(saved = true)))
        compose.onNodeWithText(SAVED_LINE).assertExists()
    }

    @Test
    fun `while the site rejects the credentials the Connection section says so`() {
        show(fresh.copy(settings = connected.copy(authError = true), form = stored.copy(editing = true, test = TestState.Failed(REJECTED_ERROR))))

        compose.onNodeWithText(AUTH_ERROR_LINE).assertExists()
        compose.onNodeWithText(REJECTED_ERROR).assertExists()
    }

    @Test
    fun `the grace window and the Wi-Fi switch go to the model`() {
        show(fresh)

        compose.onNodeWithText(GRACE_LINE).assertExists()
        compose.onNodeWithText("Grace window, in seconds").performScrollTo().performTextReplacement("90")
        compose.onNodeWithText("Upload on Wi-Fi only").performScrollTo().assertIsOff().performClick()

        assertEquals(listOf("grace:90", "wifiOnly:true"), calls)
    }

    @Test
    fun `an invalid grace window or pattern shows its error in place of the hint`() {
        show(fresh.copy(form = form.copy(grace = "abc", graceError = GRACE_ERROR, pattern = "HH", patternError = PATTERN_ERROR), titlePreview = null))

        compose.onNodeWithText(GRACE_ERROR).assertExists()
        compose.onNodeWithText(GRACE_LINE).assertDoesNotExist()
        compose.onNodeWithText(PATTERN_ERROR).assertExists()
    }

    @Test
    fun `until the first connection the category cannot be chosen, and the screen says why`() {
        show(fresh)

        compose.onNodeWithText(NO_CATEGORIES_LINE).assertExists()
        compose.onNodeWithText("Category").performScrollTo().performClick()
        compose.onNodeWithText("Travel").assertDoesNotExist()
    }

    @Test
    fun `the category is chosen from the site's categories`() {
        show(fresh.copy(settings = connected))

        compose.onNodeWithText("Journal").assertExists()
        compose.onNodeWithText("Category").performScrollTo().performClick()
        compose.onNodeWithText("Travel").performClick()

        assertEquals(listOf("category:travel"), calls)
        compose.onNodeWithText(NO_CATEGORIES_LINE).assertDoesNotExist()
    }

    @Test
    fun `the post status is Private unless changed, and Public comes with its warning`() {
        show(fresh)
        compose.onNodeWithText("Private").assertIsSelected()
        compose.onNodeWithText(PUBLIC_LINE).assertDoesNotExist()
        compose.onNodeWithText(EXISTING_POSTS_LINE).assertExists()
        compose.onNodeWithText("Draft").performScrollTo().performClick()
        assertEquals(listOf("postStatus:DRAFT"), calls)
    }

    @Test
    fun `with Public chosen a line says that every entry is public at once`() {
        show(fresh.copy(settings = Settings(postStatus = PostStatus.PUBLIC)))

        compose.onNodeWithText("Public").assertIsSelected()
        compose.onNodeWithText(PUBLIC_LINE).assertExists()
    }

    @Test
    fun `the title's language and pattern go to the model, with a preview of today's title`() {
        show(fresh)

        compose.onNodeWithText("English").assertIsSelected()
        compose.onNodeWithText("Spanish").performScrollTo().performClick()
        compose.onNodeWithText("Today: 2026-10-11 · Sunday, October 11").assertExists()
        compose.onNodeWithText("Title pattern").performScrollTo().performTextReplacement("yyyy")

        assertEquals(listOf("titleLanguage:${TitleLanguage.SPANISH}", "pattern:yyyy"), calls)
    }

    @Test
    fun `the category notice is shown above the queue row until OK`() {
        show(fresh.copy(settings = connected.copy(categoryNotice = CategoryNotice(missing = "Travel", used = "Journal"))))

        compose.onNodeWithText("The category \"Travel\" no longer exists on your site. Day posts now go to \"Journal\".").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismissCategoryNotice"), calls)
    }
}
