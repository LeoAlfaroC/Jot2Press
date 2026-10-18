package io.github.leoalfaroc.jot2press

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

@RunWith(AndroidJUnit4::class)
class ResourcesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun licence(name: String) = context.assets.open("licenses/$name").bufferedReader().use { it.readText() }

    @Test
    fun `every bundled font has its licence, and Schibsted Grotesk is gone`() {
        val fonts = R.font::class.java.fields.map { it.name }.sorted()
        val licences = context.assets.list("licenses").orEmpty().sorted()

        assertEquals(listOf("atkinson_hyperlegible_bold", "atkinson_hyperlegible_regular", "shantell_sans_bold", "shantell_sans_extrabold"), fonts)
        assertEquals(listOf("AtkinsonHyperlegible-OFL.txt", "ShantellSans-OFL.txt"), licences)
        assertTrue(licence("ShantellSans-OFL.txt").startsWith("Copyright 2022 The Shantell Sans Project Authors"))
        assertTrue(licence("AtkinsonHyperlegible-OFL.txt").startsWith("Copyright 2020 Braille Institute of America, Inc."))
        for (name in licences) assertTrue(name, licence(name).contains("SIL Open Font License, Version 1.1"))
    }

    @Test
    fun `the launcher icon is the adaptive Jot2Press icon, with a monochrome layer`() {
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        // Unit tests run with the module folder (android/app) as the working directory.
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        for (icon in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val layers = mutableMapOf<String, Int>()
            context.resources.getXml(icon).use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name != "adaptive-icon") {
                        layers[xml.name] = xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                    }
                }
            }
            assertEquals(
                mapOf(
                    "background" to R.color.icon_page,
                    "foreground" to R.drawable.ic_launcher_foreground,
                    "monochrome" to R.drawable.ic_launcher_monochrome,
                ),
                layers,
            )
        }
    }

    @Test
    fun `the arrows that point along the line turn round in a right-to-left language`() {
        for (icon in listOf(R.drawable.ic_back, R.drawable.ic_chevron)) {
            assertTrue(context.resources.getResourceEntryName(icon), context.getDrawable(icon)!!.isAutoMirrored)
        }
    }
}
