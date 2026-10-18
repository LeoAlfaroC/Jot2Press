package io.github.leoalfaroc.jot2press.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or an emulator: the Android Keystore does not exist anywhere else. */
@RunWith(AndroidJUnit4::class)
class KeystoreBoxTest {
    private val secret = """{"siteUrl":"https://example.com","username":"leo","password":"abcd efgh"}""".toByteArray()

    @Test
    fun whatIsSealedOpensAgainWithAnotherInstance() {
        val sealed = KeystoreBox().seal(secret)

        assertArrayEquals(secret, KeystoreBox().open(sealed))
    }

    @Test
    fun whatIsSealedDoesNotContainTheSecretAndDiffersEachTime() {
        val first = KeystoreBox().seal(secret)
        val second = KeystoreBox().seal(secret)

        assertFalse(String(first, Charsets.ISO_8859_1).contains("abcd efgh"))
        assertFalse(first.contentEquals(second))
        assertEquals(first.size, second.size)
    }

    @Test
    fun whatWasTamperedWithDoesNotOpen() {
        val sealed = KeystoreBox().seal(secret)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()

        assertThrows(Exception::class.java) { KeystoreBox().open(sealed) }
    }
}
