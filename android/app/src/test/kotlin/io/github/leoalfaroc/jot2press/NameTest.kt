package io.github.leoalfaroc.jot2press

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.net.HttpJot2PressApi
import io.github.leoalfaroc.jot2press.send.Sender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Unit tests run with the module folder (android/app) as the working directory. */
private fun declaredName(key: String): String =
    Json.parseToJsonElement(File("../../project.json").readText()).jsonObject.getValue(key).jsonPrimitive.content

fun declaredAppName(): String = declaredName("appName")

fun declaredPluginName(): String = declaredName("pluginName")

@RunWith(AndroidJUnit4::class)
class NameTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app is labelled with the appName of project json`() {
        assertEquals(declaredAppName(), context.getString(R.string.app_name))
        assertEquals(declaredAppName(), context.packageManager.getApplicationLabel(context.applicationInfo).toString())
    }

    @Test
    fun `the messages about the plugin use the pluginName of project json`() {
        val name = declaredPluginName()
        assertEquals("The site answered, but not as the $name plugin does.", HttpJot2PressApi.NOT_THE_PLUGIN)
        assertEquals("The $name plugin does not answer on the site. Check that it is installed and active.", Sender.PLUGIN_MISSING)
    }

    @Test
    fun `no source file spells the name out`() {
        val names = setOf(declaredAppName(), declaredPluginName())
        val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        // In XML, a class or style named after the app (.Jot2PressApp, Theme.Jot2Press) is not the visible name.
        val visible = names.map { Regex("(?<![.\\w])${Regex.escape(it)}(?!\\w)") }
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { file ->
                val source = file.readText()
                if (file.extension == "kt") {
                    literal.findAll(source).any { value -> names.any { it in value.value } }
                } else {
                    visible.any { it.containsMatchIn(source) }
                }
            }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `backups and cleartext traffic are off`() {
        val flags = context.applicationInfo.flags
        assertTrue(flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
        assertTrue(flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC == 0)
    }
}
