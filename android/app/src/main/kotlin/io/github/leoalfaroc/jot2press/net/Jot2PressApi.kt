package io.github.leoalfaroc.jot2press.net

import io.github.leoalfaroc.jot2press.BuildConfig
import io.github.leoalfaroc.jot2press.data.Category
import io.github.leoalfaroc.jot2press.data.Credentials
import io.github.leoalfaroc.jot2press.data.SiteStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** What came of one request to the site. */
sealed interface ApiResult<out T> {
    /** The plugin did what was asked. */
    data class Ok<T>(val value: T) : ApiResult<T>

    /**
     * The site answered, but not with what was asked for.
     * `code` is the code of a WordPress error object, and null when the answer was not one.
     */
    data class Refused(val status: Int, val code: String?, val message: String) : ApiResult<Nothing>

    /** No answer at all: no network, a timeout, a failed TLS handshake. */
    data class Unreachable(val reason: String) : ApiResult<Nothing>
}

/** Where an entry was written. */
data class Coordinates(val lat: Double, val lng: Double)

/**
 * One entry as the plugin's `POST /entries` takes it. `title` and `category` are left out when null.
 * `photoIds` are the photos uploaded ahead of the entry, in the order they show; `photo_ids` is left out when empty. `location` is left out when null.
 */
data class EntryPayload(
    val id: String,
    val writtenAt: String,
    val text: String,
    val title: String?,
    val category: String?,
    val postStatus: String,
    val photoIds: List<String> = emptyList(),
    val location: Coordinates? = null,
)

/** The plugin stored the entry, or had stored it before. */
data class EntrySent(val duplicate: Boolean, val categoryFallback: Category?)

interface Jot2PressApi {
    suspend fun status(): ApiResult<SiteStatus>
    suspend fun sendEntry(entry: EntryPayload): ApiResult<EntrySent>

    /** Uploads one photo ahead of the entry that lists it. The site answers the same for a photo it already has. */
    suspend fun uploadPhoto(id: String, file: File, mediaType: String): ApiResult<Unit>
}

/**
 * Redirects are not followed: a redirected POST arrives as a GET, and the plugin would answer that it has no such route.
 */
fun jot2PressHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

class HttpJot2PressApi(private val client: OkHttpClient, private val credentials: Credentials) : Jot2PressApi {
    private val authorization = okhttp3.Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8)

    override suspend fun status(): ApiResult<SiteStatus> = call("status", { get() }) { json ->
        SiteStatus(
            user = json.text("user"),
            defaultCategory = json.getValue("category").category(),
            categories = json.getValue("categories").jsonArray.map { it.category() },
            maxUploadBytes = json.getValue("max_upload_bytes").jsonPrimitive.long,
            version = json.text("version"),
        )
    }

    override suspend fun sendEntry(entry: EntryPayload): ApiResult<EntrySent> {
        val body = buildJsonObject {
            put("id", entry.id)
            put("written_at", entry.writtenAt)
            put("text", entry.text)
            entry.title?.let { put("title", it) }
            entry.category?.let { put("category", it) }
            put("post_status", entry.postStatus)
            if (entry.photoIds.isNotEmpty()) put("photo_ids", buildJsonArray { entry.photoIds.forEach { add(it) } })
            entry.location?.let { put("location", buildJsonObject { put("lat", it.lat); put("lng", it.lng) }) }
        }.toString().toRequestBody(JSON)
        return call("entries", { post(body) }) { json ->
            val duplicate = when (json.text("status")) {
                "created" -> false
                "duplicate" -> true
                else -> throw IllegalArgumentException("Not an answer of the plugin")
            }
            EntrySent(duplicate, (json["category_fallback"] as? JsonObject)?.category())
        }
    }

    override suspend fun uploadPhoto(id: String, file: File, mediaType: String): ApiResult<Unit> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("id", id)
            .addFormDataPart("photo", "$id.${EXTENSIONS[mediaType] ?: "jpg"}", file.asRequestBody(mediaType.toMediaType()))
            .build()
        return call("photos", { post(body) }) { json ->
            when (json.text("status")) {
                "created", "duplicate" -> Unit
                else -> throw IllegalArgumentException("Not an answer of the plugin")
            }
        }
    }

    private suspend fun <T> call(route: String, method: Request.Builder.() -> Request.Builder, read: (JsonObject) -> T): ApiResult<T> {
        val url = endpoint(route) ?: return ApiResult.Unreachable("The site URL is not a valid address.")
        val request = Request.Builder().url(url).method().header("Authorization", authorization).header("Accept", "application/json").build()
        val answer = try {
            client.newCall(request).answer()
        } catch (e: IOException) {
            return ApiResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        val json = try {
            Json.parseToJsonElement(answer.body) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        return when (answer.status) {
            // Only what the plugin itself says counts: a login page served with a 200 is not a confirmation.
            in 200..299 -> json?.let { runCatching { read(it) }.getOrNull() }?.let { ApiResult.Ok(it) }
                ?: ApiResult.Refused(answer.status, null, NOT_THE_PLUGIN)
            in 300..399 -> ApiResult.Refused(answer.status, null, redirected(url, answer.location, route))
            else -> ApiResult.Refused(
                answer.status,
                (json?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                (json?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: if (answer.status == 413) TOO_LARGE else "The site answered with an error (HTTP ${answer.status}).",
            )
        }
    }

    /**
     * A redirect to the same route of another site names that site, which is the address to use.
     * The address it gives is not: it is the route's, and the route would be added to it again.
     */
    private fun redirected(asked: HttpUrl, location: String?, route: String): String {
        val target = location?.let { asked.resolve(it) } ?: return "The site redirects to another address. Check the site URL."
        val address = target.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
        return if (target.isHttps && address.endsWith(ROUTES + route)) {
            "The site redirects to ${address.removeSuffix(ROUTES + route)}. Use that address as the site URL."
        } else {
            "The site redirects to $target. Check the site URL."
        }
    }

    private fun endpoint(route: String): HttpUrl? =
        (credentials.siteUrl.trimEnd('/') + ROUTES + route).toHttpUrlOrNull()

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonElement.category(): Category = Category(jsonObject.text("slug"), jsonObject.text("name"))

    private data class Answer(val status: Int, val body: String, val location: String?)

    /** Runs the call without blocking a thread, and cancels it when the coroutine is cancelled. */
    private suspend fun Call.answer(): Answer = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val answer = try {
                    response.use { Answer(it.code, it.peekBody(MAX_ANSWER_BYTES).string(), it.header("Location")) }
                } catch (e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                    return
                }
                continuation.resume(answer)
            }
        })
    }

    companion object {
        const val NOT_THE_PLUGIN = "The site answered, but not as the ${BuildConfig.PLUGIN_NAME} plugin does."
        const val TOO_LARGE = "The site refused the request as too large (HTTP 413)."
        private const val ROUTES = "/wp-json/jot2press/v1/"
        private const val MAX_ANSWER_BYTES = 256 * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** The file name's extension matches the type, as WordPress checks the two against each other. */
        private val EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp")
    }
}
