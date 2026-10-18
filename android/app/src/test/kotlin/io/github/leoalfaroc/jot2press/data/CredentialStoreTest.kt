package io.github.leoalfaroc.jot2press.data

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.FakeBox
import io.github.leoalfaroc.jot2press.tempStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val box = FakeBox()
    private val store by lazy { tempStore(folder.root, scope) }
    private val credentials by lazy { CredentialStore(store, box) }
    private val mine = Credentials("https://example.com/blog", "leó", "abcd EFGH 1234 ñandú")

    @After
    fun close() = scope.cancel()

    @Test
    fun `nothing is stored at first`() = runTest {
        assertNull(credentials.get())
    }

    @Test
    fun `what is saved comes back and is not stored in the clear`() = runTest {
        credentials.save(mine)

        assertEquals(mine, credentials.get())
        val raw = store.data.first()[stringPreferencesKey("credentials")]!!
        val plain = """{"site_url":"https://example.com/blog","username":"leó","password":"abcd EFGH 1234 ñandú"}""".toByteArray(Charsets.UTF_8)
        // What is stored is what the box made of the plain JSON, and not the JSON itself.
        assertArrayEquals(box.seal(plain), Base64.getDecoder().decode(raw))
        assertFalse(plain.contentEquals(Base64.getDecoder().decode(raw)))
    }

    @Test
    fun `credentials that can no longer be decrypted count as none`() = runTest {
        credentials.save(mine)
        box.broken = true
        assertNull(credentials.get())
    }

    @Test
    fun `the password is never printed`() {
        assertFalse(mine.password in mine.toString())
    }

    @Test
    fun `only an https address is a site URL`() {
        assertEquals("https://example.com", normalizeSiteUrl(" https://example.com/ "))
        assertEquals("https://example.com/blog", normalizeSiteUrl("https://example.com/blog/"))
        assertEquals("https://example.com", normalizeSiteUrl("example.com"))
        assertEquals("https://example.com:8443/wp", normalizeSiteUrl("example.com:8443/wp"))
        assertNull(normalizeSiteUrl("http://example.com"))
        assertNull(normalizeSiteUrl("ftp://example.com"))
        assertNull(normalizeSiteUrl("https://example.com/?p=1"))
        assertNull(normalizeSiteUrl("https://exa mple.com"))
        assertNull(normalizeSiteUrl(""))
    }
}
