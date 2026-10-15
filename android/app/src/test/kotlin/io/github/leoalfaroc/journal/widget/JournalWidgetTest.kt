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
    fun `the write button opens the capture dialog, and the status area opens settings`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(configured = true)) }

        onNode(hasText("Write")).assertExists()
        onNode(hasStartActivityClickAction<CaptureActivity>()).assertExists()
        onNode(hasContentDescription("Open settings")).assertHasStartActivityClickAction<SettingsActivity>()
        onAllNodes(hasStartActivityClickAction<SettingsActivity>()).assertCountEquals(1)
    }

    @Test
    fun `until the app is configured the write button opens settings`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(configured = false)) }

        onNode(hasStartActivityClickAction<CaptureActivity>()).assertDoesNotExist()
        onAllNodes(hasStartActivityClickAction<SettingsActivity>()).assertCountEquals(2)
    }

    @Test
    fun `it shows how many entries are in the queue, and nothing when there are none`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(count = 3, configured = true)) }

        onNode(hasText("3")).assertExists()
        onNode(hasText("!")).assertDoesNotExist()
        onNode(hasContentDescription("3 in the queue. Open settings")).assertExists()
    }

    @Test
    fun `with an empty queue it shows no number`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(configured = true)) }

        onNode(hasText("0")).assertDoesNotExist()
    }

    @Test
    fun `it alerts when something needs attention, and marks an unfinished draft`() = runGlanceAppWidgetUnitTest {
        setContext(world.context)
        provideComposable { WidgetContent(WidgetState(count = 2, hasDraft = true, alert = true, configured = true)) }

        onNode(hasText("!")).assertExists()
        onNode(hasText("2")).assertExists()
        onNode(hasContentDescription("Needs attention. 2 in the queue. Unfinished draft. Open settings")).assertExists()
    }

    @Test
    fun `what it shows follows the queue and the settings`() = runTest {
        val states = widgetStates(world.container)
        assertEquals(WidgetState(), states.first())

        world.connect()
        world.write("Coffee")
        world.queue.saveDraft("Unfinished")
        assertEquals(WidgetState(count = 1, hasDraft = true, alert = false, configured = true), states.first())

        world.settings.chooseCategory("travel")
        world.settings.categoryFellBack(Category("journal", "Journal"))
        assertEquals(true, states.first().alert)
    }

    @Test
    fun `the widget is listed under the app's name`() {
        val packages = world.context.packageManager
        val receiver = packages.getReceiverInfo(ComponentName(world.context, JournalWidgetReceiver::class.java), 0)

        assertEquals(declaredAppName(), receiver.loadLabel(packages).toString())
    }
}
