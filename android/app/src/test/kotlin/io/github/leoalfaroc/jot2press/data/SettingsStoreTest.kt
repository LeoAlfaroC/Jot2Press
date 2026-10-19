package io.github.leoalfaroc.jot2press.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.TitleLanguage
import io.github.leoalfaroc.jot2press.Titles
import io.github.leoalfaroc.jot2press.rejectSignIn
import io.github.leoalfaroc.jot2press.tempStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the upload limit can be stored on its own`() = runTest {
        settings.setMaxUploadBytes(2_097_152)
        assertEquals(2_097_152L, settings.get().maxUploadBytes)
    }

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
        settings.rejectSignIn()

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
    fun `saved credentials configure the app and end a rejected sign-in, without inventing what the site reports`() = runTest {
        settings.rejectSignIn()
        settings.credentialsSaved()

        val kept = settings.get()
        assertEquals(true, kept.configured)
        assertEquals(false, kept.authError)
        assertNull(kept.categorySlug)
        assertEquals(emptyList<Category>(), kept.categories)
        assertNull(kept.maxUploadBytes)
    }

    @Test
    fun `saving credentials forgets the upload limit of the site before, and a tested connection stores its own`() = runTest {
        settings.connectionSucceeded(site(journal, travel))
        assertNotNull(settings.get().maxUploadBytes)

        settings.credentialsSaved()
        assertNull(settings.get().maxUploadBytes)

        settings.connectionSucceeded(site(journal, travel))
        assertNotNull(settings.get().maxUploadBytes)
    }

    @Test
    fun `a tested connection stores the photo types the site accepts, and saving credentials forgets them`() = runTest {
        settings.connectionSucceeded(site(journal).copy(photoTypes = setOf("image/jpeg", "image/png")))
        assertEquals(setOf("image/jpeg", "image/png"), settings.get().photoTypes)

        settings.credentialsSaved()
        assertNull(settings.get().photoTypes)

        settings.connectionSucceeded(site(journal).copy(photoTypes = setOf("image/jpeg")))
        settings.connectionSucceeded(site(journal))
        assertNull("an older plugin says nothing, which takes every type", settings.get().photoTypes)
    }

    @Test
    fun `the first successful connection configures the app and chooses the default category`() = runTest {
        settings.rejectSignIn()
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

    @Test
    fun `Attach location is off by default, and turning it on turns the pin on`() = runTest {
        assertFalse(settings.get().attachLocation)
        assertFalse(settings.get().pinOn)

        settings.setAttachLocation(true)
        assertTrue(settings.get().attachLocation)
        assertTrue(settings.get().pinOn)

        settings.setPinOn(false)
        assertTrue(settings.get().attachLocation)
        assertFalse(settings.get().pinOn)

        settings.setAttachLocation(false)
        settings.setAttachLocation(true)
        assertTrue(settings.get().pinOn)
    }
}
