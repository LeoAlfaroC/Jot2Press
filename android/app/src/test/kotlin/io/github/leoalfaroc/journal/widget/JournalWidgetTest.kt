package io.github.leoalfaroc.journal.widget

import android.content.ComponentName
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasStartActivityClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestWorld
import io.github.leoalfaroc.journal.WidgetState
import io.github.leoalfaroc.journal.capture.CaptureActivity
import io.github.leoalfaroc.journal.data.Category
import io.github.leoalfaroc.journal.declaredAppName
import io.github.leoalfaroc.journal.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalWidgetTest {
    @get:Rule val folder = TemporaryFolder()
    private val world by lazy { TestWorld(folder.root) }

    @After
    fun close() = world.close()

    @Test
    fun `while nothing needs attention it is only the write button, which opens the capture dialog`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(configured = true)) }

        onNode(hasText("Write")).assertExists()
        onNode(hasStartActivityClickAction<CaptureActivity>()).assertExists()
        onAllNodes(hasStartActivityClickAction<SettingsActivity>()).assertCountEquals(0)
    }

    @Test
    fun `until the app is configured the write button opens settings`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(configured = false)) }

        onNode(hasStartActivityClickAction<CaptureActivity>()).assertDoesNotExist()
        onAllNodes(hasStartActivityClickAction<SettingsActivity>()).assertCountEquals(1)
    }

    @Test
    fun `entries that failed show as a badge that opens settings`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(failed = 2, configured = true)) }

        onNode(hasText("2")).assertExists()
        onNode(hasContentDescription("2 failed to send. Open settings")).assertHasStartActivityClickAction<SettingsActivity>()
        onNode(hasStartActivityClickAction<CaptureActivity>()).assertExists()
    }

    @Test
    fun `the badge stops counting at 99`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(failed = 120, configured = true)) }

        onNode(hasText("99+")).assertExists()
        onNode(hasContentDescription("120 failed to send. Open settings")).assertExists()
    }

    @Test
    fun `a rejected sign-in shows in the badge in place of the failed entries`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(failed = 2, signInRejected = true, configured = true)) }

        onNode(hasText("!")).assertExists()
        onNode(hasText("2")).assertDoesNotExist()
        onNode(hasContentDescription("The site rejected the sign-in. Open settings")).assertHasStartActivityClickAction<SettingsActivity>()
    }

    @Test
    fun `what it shows follows the queue and the settings`() = runTest {
        val states = widgetStates(world.container)
        assertEquals(WidgetState(), states.first())

        world.connect()
        world.write("Coffee")
        world.queue.saveDraft("Unfinished")
        world.settings.chooseCategory("travel")
        world.settings.categoryFellBack(Category("journal", "Journal"))
        assertEquals(WidgetState(configured = true), states.first())

        world.settings.setAuthError(true)
        assertEquals(WidgetState(signInRejected = true, configured = true), states.first())
    }

    @Test
    fun `the widget is listed under the app's name`() {
        val packages = world.context.packageManager
        val receiver = packages.getReceiverInfo(ComponentName(world.context, JournalWidgetReceiver::class.java), 0)

        assertEquals(declaredAppName(), receiver.loadLabel(packages).toString())
    }
}
