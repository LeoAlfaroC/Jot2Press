package io.github.leoalfaroc.journal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.tempStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings by lazy { SettingsStore(tempStore(folder.root, scope)) }

    private val journal = Category("journal", "Journal")
    private val travel = Category("travel", "Travel")
    private fun site(vararg categories: Category) =
        SiteStatus(user = "leo", defaultCategory = journal, categories = categories.toList(), maxUploadBytes = 67_108_864, version = "1.0.0")

    @After
    fun close() = scope.cancel()

    @Test
    fun `a new install starts with the defaults`() = runTest {
        assertEquals(
            Settings(
                graceSeconds = 60,
                wifiOnly = false,
                categorySlug = null,
                postStatus = PostStatus.PRIVATE,
                titleLanguage = TitleLanguage.ENGLISH,
                titlePattern = Titles.DEFAULT_PATTERN,
                configured = false,
                authError = false,
            ),
            settings.get(),
        )
    }

    @Test
    fun `each setting is kept`() = runTest {
        settings.setGraceSeconds(0)
        settings.setWifiOnly(true)
        settings.setPostStatus(PostStatus.PUBLIC)
        settings.setTitleLanguage(TitleLanguage.SPANISH)
        settings.setTitlePattern("d MMM yyyy")
        settings.setAuthError(true)

        val kept = settings.get()
        assertEquals(0, kept.graceSeconds)
        assertEquals(true, kept.wifiOnly)
        assertEquals(PostStatus.PUBLIC, kept.postStatus)
        assertEquals("publish", kept.postStatus.wire)
        assertEquals(TitleLanguage.SPANISH, kept.titleLanguage)
        assertEquals("d MMM yyyy", kept.titlePattern)
        assertEquals(true, kept.authError)
    }

    @Test
    fun `the grace window stays between 0 and 600 seconds`() = runTest {
        settings.setGraceSeconds(-5)
        assertEquals(0, settings.get().graceSeconds)
        settings.setGraceSeconds(601)
        assertEquals(600, settings.get().graceSeconds)
    }

    @Test
    fun `the first successful connection configures the app and chooses the default category`() = runTest {
        settings.setAuthError(true)
        settings.connectionSucceeded(site(journal, travel))

        val kept = settings.get()
        assertEquals(true, kept.configured)
        assertEquals(false, kept.authError)
        assertEquals("journal", kept.categorySlug)
        assertEquals(listOf(journal, travel), kept.categories)
        assertEquals(journal, kept.defaultCategory)
        assertEquals(67_108_864L, kept.maxUploadBytes)
        assertNull(kept.categoryNotice)
    }

    @Test
    fun `a later connection keeps the chosen category while the site still has it`() = runTest {
        settings.connectionSucceeded(site(journal, travel))
        settings.chooseCategory("travel")
        settings.connectionSucceeded(site(journal, travel, Category("work", "Work")))

        assertEquals("travel", settings.get().categorySlug)
        assertNull(settings.get().categoryNotice)
    }

    @Test
    fun `a connection that no longer lists the chosen category switches to the default and says so`() = runTest {
        settings.connectionSucceeded(site(journal, travel))
        settings.chooseCategory("travel")
        settings.connectionSucceeded(site(journal))

        assertEquals("journal", settings.get().categorySlug)
        assertEquals(CategoryNotice(missing = "Travel", used = "Journal"), settings.get().categoryNotice)
        assertEquals(listOf(journal), settings.get().categories)
    }

    @Test
    fun `a fallback reported by the plugin switches the setting and says so`() = runTest {
        settings.connectionSucceeded(site(journal, travel))
        settings.chooseCategory("travel")
        settings.categoryFellBack(Category("diary", "Diary"))

        assertEquals("diary", settings.get().categorySlug)
        assertEquals(CategoryNotice(missing = "Travel", used = "Diary"), settings.get().categoryNotice)
        assertEquals(listOf(Category("diary", "Diary"), journal), settings.get().categories)
    }

    @Test
    fun `a fallback to the category already chosen says nothing`() = runTest {
        settings.connectionSucceeded(site(journal, travel))
        settings.categoryFellBack(journal)
        assertNull(settings.get().categoryNotice)
        assertEquals(listOf(journal, travel), settings.get().categories)
    }

    @Test
    fun `the notice is cleared by OK or by choosing a category`() = runTest {
        settings.connectionSucceeded(site(journal, travel))
        settings.chooseCategory("travel")
        settings.connectionSucceeded(site(journal))
        settings.dismissCategoryNotice()
        assertNull(settings.get().categoryNotice)
        assertEquals("journal", settings.get().categorySlug)

        settings.categoryFellBack(travel)
        settings.chooseCategory("journal")
        assertNull(settings.get().categoryNotice)
        assertEquals("journal", settings.get().categorySlug)
    }
}
