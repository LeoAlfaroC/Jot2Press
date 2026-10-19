package io.github.leoalfaroc.jot2press.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.leoalfaroc.jot2press.TitleLanguage
import io.github.leoalfaroc.jot2press.Titles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The status a day post is created with. `wire` is what the plugin calls it. */
enum class PostStatus(val wire: String, val label: String) {
    PRIVATE("private", "Private"),
    DRAFT("draft", "Draft"),
    PUBLIC("publish", "Public"),
}

data class Category(val slug: String, val name: String)

/** What the status endpoint reports about the site. */
data class SiteStatus(
    val user: String,
    val defaultCategory: Category,
    val categories: List<Category>,
    val maxUploadBytes: Long,
    val version: String,
    /** The photo types the site accepts; null from a plugin too old to say, which takes all of [PLUGIN_PHOTO_TYPES]. */
    val photoTypes: Set<String>? = null,
)

/** The chosen category disappeared from the site: `missing` was chosen, day posts now go to `used`. Both are names. */
data class CategoryNotice(val missing: String, val used: String)

data class Settings(
    val graceSeconds: Int = 60,
    val wifiOnly: Boolean = false,
    /** Null until the first successful `Test connection`; the plugin then uses its default category. */
    val categorySlug: String? = null,
    val postStatus: PostStatus = PostStatus.PRIVATE,
    val titleLanguage: TitleLanguage = TitleLanguage.ENGLISH,
    val titlePattern: String = Titles.DEFAULT_PATTERN,
    /** The site's categories as of the last successful `Test connection`. */
    val categories: List<Category> = emptyList(),
    val defaultCategory: Category? = null,
    val maxUploadBytes: Long? = null,
    /** The photo types the site accepts, stored with [maxUploadBytes]; null takes all of [PLUGIN_PHOTO_TYPES]. */
    val photoTypes: Set<String>? = null,
    /** True once credentials have been saved. */
    val configured: Boolean = false,
    /** The site rejected the credentials; nothing is sent until others are saved or a `Test connection` of these succeeds. */
    val authError: Boolean = false,
    /** Goes up at every Save: a rejection is recorded only for the credentials it was read with. */
    val credentialsVersion: Long = 0,
    val categoryNotice: CategoryNotice? = null,
    /** `Attach location` as stored. It shows on only while a location permission is held too. */
    val attachLocation: Boolean = false,
    /** The capture dialog's pin, as it was last left. */
    val pinOn: Boolean = false,
)

const val MAX_GRACE_SECONDS = 600

class SettingsStore(private val store: DataStore<Preferences>) {
    val flow: Flow<Settings> = store.data.map(::read)

    suspend fun get(): Settings = flow.first()

    suspend fun setGraceSeconds(seconds: Int) = change { it[GRACE] = seconds.coerceIn(0, MAX_GRACE_SECONDS) }
    suspend fun setWifiOnly(on: Boolean) = change { it[WIFI_ONLY] = on }
    suspend fun setPostStatus(status: PostStatus) = change { it[POST_STATUS] = status.name }
    suspend fun setTitleLanguage(language: TitleLanguage) = change { it[TITLE_LANGUAGE] = language.name }
    suspend fun setTitlePattern(pattern: String) = change { it[TITLE_PATTERN] = pattern }
    /** The upload limit photos are prepared for: what the site reports, or less once its web server refused a photo. */
    suspend fun setMaxUploadBytes(bytes: Long) = change { it[MAX_UPLOAD] = bytes }

    /** What the site reports about uploads: its limit and the photo types it accepts. */
    suspend fun setUploadRules(status: SiteStatus) = change { it.uploadRules(status) }
    suspend fun setAuthError(on: Boolean) = change { it[AUTH_ERROR] = on }

    /**
     * The site rejected the credentials of [version]. Nothing is recorded once Save has stored others since: in the
     * same edit, so that a Save in between cannot have its new credentials marked as rejected.
     */
    suspend fun credentialsRejected(version: Long): Boolean {
        var recorded = false
        change { p ->
            if ((p[CREDENTIALS_VERSION] ?: 0) == version) {
                p[AUTH_ERROR] = true
                recorded = true
            }
        }
        return recorded
    }

    /** Turning it on turns the dialog's pin on too. */
    suspend fun setAttachLocation(on: Boolean) = change {
        it[ATTACH_LOCATION] = on
        if (on) it[PIN_ON] = true
    }

    suspend fun setPinOn(on: Boolean) = change { it[PIN_ON] = on }

    /** Choosing a category also answers the notice about a missing one. */
    suspend fun chooseCategory(slug: String) = change {
        it[CATEGORY] = slug
        it.clearNotice()
    }

    suspend fun dismissCategoryNotice() = change { it.clearNotice() }

    /** Credentials were saved, tested or not: there is somewhere to send to, and the rejection was of other credentials. */
    suspend fun credentialsSaved() = change { p ->
        p[CONFIGURED] = true
        p[AUTH_ERROR] = false
        p[CREDENTIALS_VERSION] = (p[CREDENTIALS_VERSION] ?: 0) + 1
        // The limit and the types are the site's: these credentials may be for another one. A tested connection
        // stores its own next.
        p.remove(MAX_UPLOAD)
        p.remove(PHOTO_TYPES)
    }

    /** `Test connection` of the saved credentials succeeded: keeps what the site reported and lets sending resume. */
    suspend fun connectionSucceeded(status: SiteStatus) = change { p ->
        val chosen = p[CATEGORY]
        if (chosen == null) {
            p[CATEGORY] = status.defaultCategory.slug
        } else if (status.categories.none { it.slug == chosen }) {
            p.fallBack(from = chosen, to = status.defaultCategory)
        }
        p[CATEGORIES] = encode(status.categories)
        p[DEFAULT_SLUG] = status.defaultCategory.slug
        p[DEFAULT_NAME] = status.defaultCategory.name
        p.uploadRules(status)
        p[CONFIGURED] = true
        p[AUTH_ERROR] = false
    }

    /** The plugin created a day post in `used` because the chosen category no longer exists. */
    suspend fun categoryFellBack(used: Category) = change { p ->
        val chosen = p[CATEGORY]
        if (chosen == used.slug) return@change
        val known = decode(p[CATEGORIES])
        if (chosen != null) p.fallBack(from = chosen, to = used)
        p[CATEGORY] = used.slug
        p[CATEGORIES] = encode((known.filter { it.slug != chosen && it.slug != used.slug } + used).sortedBy { it.name.lowercase() })
    }

    private suspend fun change(block: (MutablePreferences) -> Unit) {
        store.edit(block)
    }

    /** Switches the setting to `to` and raises the notice, so that the setting always shows where posts really go. */
    private fun MutablePreferences.fallBack(from: String, to: Category) {
        this[NOTICE_MISSING] = decode(this[CATEGORIES]).firstOrNull { it.slug == from }?.name ?: from
        this[NOTICE_USED] = to.name
        this[CATEGORY] = to.slug
    }

    private fun MutablePreferences.uploadRules(status: SiteStatus) {
        this[MAX_UPLOAD] = status.maxUploadBytes
        if (status.photoTypes == null) remove(PHOTO_TYPES) else this[PHOTO_TYPES] = status.photoTypes
    }

    private fun MutablePreferences.clearNotice() {
        remove(NOTICE_MISSING)
        remove(NOTICE_USED)
    }

    private fun read(p: Preferences): Settings {
        val defaultSlug = p[DEFAULT_SLUG]
        val defaultName = p[DEFAULT_NAME]
        val missing = p[NOTICE_MISSING]
        val used = p[NOTICE_USED]
        return Settings(
            graceSeconds = p[GRACE] ?: 60,
            wifiOnly = p[WIFI_ONLY] ?: false,
            categorySlug = p[CATEGORY],
            postStatus = PostStatus.entries.firstOrNull { it.name == p[POST_STATUS] } ?: PostStatus.PRIVATE,
            titleLanguage = TitleLanguage.entries.firstOrNull { it.name == p[TITLE_LANGUAGE] } ?: TitleLanguage.ENGLISH,
            titlePattern = p[TITLE_PATTERN] ?: Titles.DEFAULT_PATTERN,
            categories = decode(p[CATEGORIES]),
            defaultCategory = if (defaultSlug != null && defaultName != null) Category(defaultSlug, defaultName) else null,
            maxUploadBytes = p[MAX_UPLOAD],
            photoTypes = p[PHOTO_TYPES],
            configured = p[CONFIGURED] ?: false,
            authError = p[AUTH_ERROR] ?: false,
            credentialsVersion = p[CREDENTIALS_VERSION] ?: 0,
            categoryNotice = if (missing != null && used != null) CategoryNotice(missing, used) else null,
            attachLocation = p[ATTACH_LOCATION] ?: false,
            pinOn = p[PIN_ON] ?: false,
        )
    }

    private fun encode(categories: List<Category>): String = buildJsonArray {
        categories.forEach { add(buildJsonObject { put("slug", it.slug); put("name", it.name) }) }
    }.toString()

    private fun decode(json: String?): List<Category> = try {
        Json.parseToJsonElement(json ?: "[]").jsonArray.map {
            Category(it.jsonObject.getValue("slug").jsonPrimitive.content, it.jsonObject.getValue("name").jsonPrimitive.content)
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** Permanent: the settings of an installed app are found under this name. */
        const val NAME = "settings"

        private val GRACE = intPreferencesKey("grace_seconds")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val CATEGORY = stringPreferencesKey("category")
        private val POST_STATUS = stringPreferencesKey("post_status")
        private val TITLE_LANGUAGE = stringPreferencesKey("title_language")
        private val TITLE_PATTERN = stringPreferencesKey("title_pattern")
        private val CATEGORIES = stringPreferencesKey("categories")
        private val DEFAULT_SLUG = stringPreferencesKey("default_category_slug")
        private val DEFAULT_NAME = stringPreferencesKey("default_category_name")
        private val MAX_UPLOAD = longPreferencesKey("max_upload_bytes")
        private val PHOTO_TYPES = stringSetPreferencesKey("photo_types")
        private val CONFIGURED = booleanPreferencesKey("configured")
        private val AUTH_ERROR = booleanPreferencesKey("auth_error")
        private val CREDENTIALS_VERSION = longPreferencesKey("credentials_version")
        private val NOTICE_MISSING = stringPreferencesKey("category_notice_missing")
        private val NOTICE_USED = stringPreferencesKey("category_notice_used")
        private val ATTACH_LOCATION = booleanPreferencesKey("attach_location")
        private val PIN_ON = booleanPreferencesKey("location_pin")
    }
}
