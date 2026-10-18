package io.github.leoalfaroc.jot2press

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
