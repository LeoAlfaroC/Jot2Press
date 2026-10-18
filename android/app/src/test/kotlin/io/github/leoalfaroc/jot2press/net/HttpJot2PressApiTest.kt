package io.github.leoalfaroc.jot2press.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.data.Category
import io.github.leoalfaroc.jot2press.data.Credentials
import io.github.leoalfaroc.jot2press.data.SiteStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HttpJot2PressApiTest {
    private val server = MockWebServer()
    private val entry = EntryPayload(
        id = "0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e",
        writtenAt = "2026-10-11T14:32:05-05:00",
        text = "Café with \"Ana\" <3\nsecond line",
        title = "2026-10-11 · Sunday, October 11",
        category = "travel",
        postStatus = "private",
    )

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun api(path: String = "", password: String = "abcd efgh", client: OkHttpClient = jot2PressHttpClient()) =
        HttpJot2PressApi(client, Credentials(server.url(path).toString(), "leo", password))

    private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) {
        val response = MockResponse.Builder().code(status).body(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response.build())
    }

    @Test
    fun `the status endpoint is read with the credentials`() = runTest {
        answer(
            200,
            """{"ok":true,"user":"leo","category":{"slug":"journal","name":"Journal"},"categories":[{"slug":"journal","name":"Journal"},{"slug":"travel","name":"Travel"}],"max_upload_bytes":67108864,"version":"1.0.0"}""",
        )

        val result = api().status()

        assertEquals(
            ApiResult.Ok(
                SiteStatus("leo", Category("journal", "Journal"), listOf(Category("journal", "Journal"), Category("travel", "Travel")), 67_108_864, "1.0.0"),
            ),
            result,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/wp-json/jot2press/v1/status", request.url.encodedPath)
        assertEquals(okhttp3.Credentials.basic("leo", "abcd efgh"), request.headers["Authorization"])
    }

    @Test
    fun `a site in a subfolder and a password with accents are addressed correctly`() = runTest {
        answer(200, """{"status":"created","post_id":7}""")

        api(path = "/blog/", password = "contraseña").sendEntry(entry)

        val request = server.takeRequest()
        assertEquals("/blog/wp-json/jot2press/v1/entries", request.url.encodedPath)
        assertEquals(okhttp3.Credentials.basic("leo", "contraseña", Charsets.UTF_8), request.headers["Authorization"])
    }

    @Test
    fun `an entry is posted as the plugin expects it`() = runTest {
        answer(201, """{"status":"created","post_id":7}""")

        val result = api().sendEntry(entry)

        assertEquals(ApiResult.Ok(EntrySent(duplicate = false, categoryFallback = null)), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = request.body!!.utf8()
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e","written_at":"2026-10-11T14:32:05-05:00","text":"Café with \"Ana\" <3\nsecond line","title":"2026-10-11 · Sunday, October 11","category":"travel","post_status":"private"}""",
            ),
            Json.parseToJsonElement(body),
        )
        assertFalse("photo_ids" in body)
    }

    @Test
    fun `a title or a category that is not set is left out`() = runTest {
        answer(201, """{"status":"created","post_id":7}""")

        api().sendEntry(entry.copy(title = null, category = null))

        assertEquals(
            setOf("id", "written_at", "text", "post_status"),
            Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.keys,
        )
    }

    @Test
    fun `a duplicate and a category fallback are reported`() = runTest {
        answer(200, """{"status":"duplicate","post_id":7}""")
        answer(201, """{"status":"created","post_id":8,"category_fallback":{"slug":"journal","name":"Journal"}}""")

        assertEquals(ApiResult.Ok(EntrySent(duplicate = true, categoryFallback = null)), api().sendEntry(entry))
        assertEquals(ApiResult.Ok(EntrySent(duplicate = false, categoryFallback = Category("journal", "Journal"))), api().sendEntry(entry))
    }

    @Test
    fun `a WordPress error keeps its code and its message`() = runTest {
        answer(400, """{"code":"empty_entry","message":"An entry needs text or photos.","data":{"status":400}}""")
        answer(401, """{"code":"rest_not_logged_in","message":"You are not currently logged in.","data":{"status":401}}""")
        answer(404, """{"code":"rest_no_route","message":"No route was found matching the URL and request method.","data":{"status":404}}""")

        assertEquals(ApiResult.Refused(400, "empty_entry", "An entry needs text or photos."), api().sendEntry(entry))
        assertEquals(ApiResult.Refused(401, "rest_not_logged_in", "You are not currently logged in."), api().sendEntry(entry))
        assertEquals(
            ApiResult.Refused(404, "rest_no_route", "No route was found matching the URL and request method."),
            api().status(),
        )
    }

    @Test
    fun `an error that is not a WordPress error has no code`() = runTest {
        answer(502, "<html><body>Bad Gateway</body></html>")
        answer(404, """{"error":"not found"}""")
        answer(500, """{"code":500,"message":["odd"]}""")

        assertEquals(ApiResult.Refused(502, null, "The site answered with an error (HTTP 502)."), api().sendEntry(entry))
        assertEquals(ApiResult.Refused(404, null, "The site answered with an error (HTTP 404)."), api().sendEntry(entry))
        assertEquals(ApiResult.Refused(500, null, "The site answered with an error (HTTP 500)."), api().sendEntry(entry))

        answer(413, "<html><body>Request Entity Too Large</body></html>")
        assertEquals(ApiResult.Refused(413, null, HttpJot2PressApi.TOO_LARGE), api().sendEntry(entry))
    }

    @Test
    fun `a success that the plugin did not write is not a confirmation`() = runTest {
        answer(200, "<html><body>Sign in to the hotel Wi-Fi</body></html>")
        answer(200, "{}")
        answer(201, """{"status":"ok"}""")
        answer(200, """{"ok":true,"user":"leo"}""")

        repeat(3) {
            assertEquals(ApiResult.Refused(if (it == 2) 201 else 200, null, HttpJot2PressApi.NOT_THE_PLUGIN), api().sendEntry(entry))
        }
        assertEquals(ApiResult.Refused(200, null, HttpJot2PressApi.NOT_THE_PLUGIN), api().status())
    }

    @Test
    fun `a redirect is reported and not followed`() = runTest {
        answer(301, "", "Location" to "https://www.example.com/wp-json/jot2press/v1/entries")

        val result = api().sendEntry(entry)

        assertEquals(ApiResult.Refused(301, null, "The site redirects to https://www.example.com. Use that address as the site URL."), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect names an address to use only when it leads to the same route of a site`() = runTest {
        fun refused(message: String) = ApiResult.Refused(302, null, message)
        val here = server.url("").toString().trimEnd('/')

        // A site in a subfolder, and an address given without its host.
        answer(302, "", "Location" to "https://example.com/blog/wp-json/jot2press/v1/status/?lang=en")
        assertEquals(refused("The site redirects to https://example.com/blog. Use that address as the site URL."), api().status())
        answer(302, "", "Location" to "/wp-login.php")
        assertEquals(refused("The site redirects to $here/wp-login.php. Check the site URL."), api().status())

        // An address the settings would not take, and no address at all.
        answer(302, "", "Location" to "http://example.com/wp-json/jot2press/v1/status")
        assertEquals(refused("The site redirects to http://example.com/wp-json/jot2press/v1/status. Check the site URL."), api().status())
        answer(302, "")
        assertEquals(refused("The site redirects to another address. Check the site URL."), api().status())
    }

    @Test
    fun `a site that does not answer is unreachable`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("""{"status":"created","post_id":7}""").headersDelay(2, TimeUnit.SECONDS).build())
        val impatient = jot2PressHttpClient().newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        assertTrue(api(client = impatient).sendEntry(entry) is ApiResult.Unreachable)

        val gone = api()
        server.close()
        assertTrue(gone.sendEntry(entry) is ApiResult.Unreachable)
        assertTrue(gone.status() is ApiResult.Unreachable)
    }

    @Test
    fun `an address that is not one is unreachable`() = runTest {
        val api = HttpJot2PressApi(jot2PressHttpClient(), Credentials("not an address", "leo", "abcd"))
        assertEquals(ApiResult.Unreachable("The site URL is not a valid address."), api.status())
    }

    @Test
    fun `a photo goes as multipart with its id, and created or duplicate both count`() = runTest {
        val photo = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(-1, -40, -1, 1, 2, 3)) }
        answer(201, """{"status":"created","attachment_id":12}""")
        answer(200, """{"status":"duplicate","attachment_id":12}""")

        assertEquals(ApiResult.Ok(Unit), api().uploadPhoto("0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e", photo, "image/jpeg"))
        assertEquals(ApiResult.Ok(Unit), api().uploadPhoto("0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e", photo, "image/jpeg"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.url.encodedPath.endsWith("/jot2press/v1/photos"))
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("name=\"id\""))
        assertTrue(body.contains("0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e"))
        assertTrue(body.contains("name=\"photo\"; filename=\"0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
    }

    @Test
    fun `a refused photo says why, and a 413 without a body says it was too large`() = runTest {
        val photo = kotlin.io.path.createTempFile(suffix = ".jpg").toFile().apply { writeBytes(byteArrayOf(-1, -40, -1)) }
        answer(413, "<html>Request Entity Too Large</html>")

        val refused = api().uploadPhoto("0b6d3f0e-5c1a-4f6e-9d57-1c2a3b4c5d6e", photo, "image/jpeg") as ApiResult.Refused

        assertEquals(413, refused.status)
        assertEquals(HttpJot2PressApi.TOO_LARGE, refused.message)
    }

    @Test
    fun `an entry with photos lists their ids in order`() = runTest {
        answer(201, """{"status":"created","post_id":7}""")

        api().sendEntry(entry.copy(text = "", photoIds = listOf("a", "b")))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("[\"a\",\"b\"]", body.getValue("photo_ids").toString())
    }

    @Test
    fun `a location goes as numbers under lat and lng`() = runTest {
        answer(201, """{"status":"created","post_id":7}""")

        api().sendEntry(entry.copy(location = Coordinates(4.60971, -74.08175)))

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(Json.parseToJsonElement("""{"lat":4.60971,"lng":-74.08175}"""), body.getValue("location"))
    }
}
