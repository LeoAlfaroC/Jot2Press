package io.github.leoalfaroc.jot2press.widget

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.R
import io.github.leoalfaroc.jot2press.TestWorld
import io.github.leoalfaroc.jot2press.Trouble
import io.github.leoalfaroc.jot2press.WidgetState
import io.github.leoalfaroc.jot2press.capture.CaptureActivity
import io.github.leoalfaroc.jot2press.data.Category
import io.github.leoalfaroc.jot2press.declaredAppName
import io.github.leoalfaroc.jot2press.settings.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class Jot2PressWidgetTest {
    @get:Rule val folder = TemporaryFolder()
    private val world by lazy { TestWorld(folder.root) }

    @After
    fun close() = world.close()

    /** The widget as the launcher shows it, in a cell of the given size. */
    private fun shown(state: WidgetState, widthDp: Int = 51, heightDp: Int = 72): View {
        val context = world.context
        val density = context.resources.displayMetrics.density
        val view = widgetViews(context, state).apply(context, FrameLayout(context))
        view.measure(exactly(widthDp * density), exactly(heightDp * density))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun exactly(px: Float) = View.MeasureSpec.makeMeasureSpec(px.toInt(), View.MeasureSpec.EXACTLY)

    /** Where a view sits in the square, which holds the button and the badge side by side. */
    private fun View.bounds() = Rect(left, top, right, bottom)

    private fun View.write() = findViewById<View>(R.id.write)
    private fun View.badge() = findViewById<TextView>(R.id.badge)

    /** What a tap starts. */
    private fun View.tap(): Intent {
        performClick()
        return shadowOf(world.context as Application).nextStartedActivity
    }

    private fun View.opens(): Class<*> = Class.forName(tap().component!!.className)

    private fun View.opensTheQueue() = tap().let { it.component?.className == SettingsActivity::class.java.name && it.getBooleanExtra(SettingsActivity.OPEN_QUEUE, false) }

    @Test
    fun `in a cell taller than wide the write button is a square as wide as the cell allows`() {
        val write = shown(WidgetState(configured = true), widthDp = 51, heightDp = 72).write()
        assertEquals(write.width, write.height)
        assertEquals(45, write.width)
    }

    @Test
    fun `in a cell wider than tall it is a square as tall as the cell allows`() {
        val write = shown(WidgetState(configured = true), widthDp = 90, heightDp = 64).write()
        assertEquals(write.width, write.height)
        assertEquals(58, write.width)
    }

    @Test
    fun `while nothing needs attention it is only the write button, which opens the capture dialog`() {
        val widget = shown(WidgetState(configured = true))

        assertEquals(View.GONE, widget.badge().visibility)
        assertEquals("Write a journal entry", widget.write().contentDescription)
        assertEquals(CaptureActivity::class.java, widget.write().opens())
    }

    @Test
    fun `until the app is configured the write button opens settings, and says so`() {
        val write = shown(WidgetState(configured = false)).write()
        assertEquals("Set up the journal. Opens settings", write.contentDescription)
        assertEquals(SettingsActivity::class.java, write.opens())
    }

    @Test
    fun `entries that could not be sent show as a badge over the button's corner that opens the queue`() {
        val widget = shown(WidgetState(Trouble.NotSent(2, null), configured = true))
        val badge = widget.badge()
        val write = widget.write().bounds()

        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals("2", badge.text)
        assertEquals("2 entries could not be sent. Opens the queue", badge.contentDescription)
        assertTrue(badge.bounds().contains(write.right - 1, write.top))
        assertTrue(badge.opensTheQueue())
        assertEquals(CaptureActivity::class.java, widget.write().opens())
    }

    @Test
    fun `the badge stops counting at 99`() {
        val badge = shown(WidgetState(Trouble.NotSent(120, null), configured = true)).badge()

        assertEquals("99+", badge.text)
        assertEquals("120 entries could not be sent. Opens the queue", badge.contentDescription)
        assertEquals("1 entry could not be sent. Opens the queue", shown(WidgetState(Trouble.NotSent(1, null), configured = true)).badge().contentDescription)
    }

    @Test
    fun `a rejected sign-in shows in the badge, which opens settings`() {
        val badge = shown(WidgetState(Trouble.SignInRejected, configured = true)).badge()

        assertEquals("!", badge.text)
        assertEquals("The site rejected the sign-in. Opens settings", badge.contentDescription)
        val opened = badge.tap()
        assertEquals(SettingsActivity::class.java.name, opened.component?.className)
        assertFalse(opened.getBooleanExtra(SettingsActivity.OPEN_QUEUE, false))
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
        assertEquals(WidgetState(Trouble.SignInRejected, configured = true), states.first())
    }

    @Test
    fun `the widget is listed under the app's name`() {
        val packages = world.context.packageManager
        val receiver = packages.getReceiverInfo(ComponentName(world.context, Jot2PressWidgetReceiver::class.java), 0)

        assertEquals(declaredAppName(), receiver.loadLabel(packages).toString())
    }

    @Test
    fun `the tile is ink and the pen paper, in the light and in the dark`() {
        val day = world.context
        val night = day.createConfigurationContext(
            Configuration(day.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            },
        )

        assertEquals(0xFF0066FF.toInt(), day.getColor(R.color.widget_ink))
        assertEquals(0xFF7AB0FF.toInt(), night.getColor(R.color.widget_ink))
        assertEquals(0xFFFFFFFF.toInt(), day.getColor(R.color.widget_on_ink))
        assertEquals(0xFF101218.toInt(), night.getColor(R.color.widget_on_ink))
        assertEquals(day.getColor(R.color.widget_on_ink), day.getColor(R.color.widget_paper))
        assertEquals(night.getColor(R.color.widget_on_ink), night.getColor(R.color.widget_paper))

        val widget = shown(WidgetState(configured = true))
        val tile = (widget.write() as ImageView).drawable as GradientDrawable
        assertEquals(day.getColor(R.color.widget_ink), tile.color!!.defaultColor)
        assertEquals(day.getColor(R.color.widget_on_ink), widget.findViewById<ImageView>(R.id.pen).imageTintList!!.defaultColor)
    }
}
