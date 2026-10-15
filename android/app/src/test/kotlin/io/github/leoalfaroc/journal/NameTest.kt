package io.github.leoalfaroc.journal

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Unit tests run with the module folder (android/app) as the working directory. */
fun declaredAppName(): String =
    Json.parseToJsonElement(File("../../project.json").readText()).jsonObject.getValue("appName").jsonPrimitive.content

@RunWith(AndroidJUnit4::class)
class NameTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app is labelled with the appName of project json`() {
        assertEquals(declaredAppName(), context.getString(R.string.app_name))
        assertEquals(declaredAppName(), context.packageManager.getApplicationLabel(context.applicationInfo).toString())
    }

    @Test
    fun `no source file spells the name out`() {
        val name = declaredAppName()
        val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { file ->
                val source = file.readText()
                if (file.extension == "kt") literal.findAll(source).any { name in it.value } else name in source
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
