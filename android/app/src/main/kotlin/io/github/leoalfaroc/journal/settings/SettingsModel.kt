package io.github.leoalfaroc.journal.settings

import io.github.leoalfaroc.journal.AppContainer
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.MAX_GRACE_SECONDS
import io.github.leoalfaroc.journal.data.PostStatus
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.data.SiteStatus
import io.github.leoalfaroc.journal.data.normalizeSiteUrl
import io.github.leoalfaroc.journal.location.Permission
import io.github.leoalfaroc.journal.net.ApiResult
import io.github.leoalfaroc.journal.queueFacts
import io.github.leoalfaroc.journal.queueSummary
import io.github.leoalfaroc.journal.send.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.Locale

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Passed(val message: String) : TestState
    data class Failed(val message: String) : TestState
}

/** What is typed on the settings screen, which is not always what is stored: an invalid value stays here only. */
data class SettingsForm(
    /** Goes up each time the model, not the user, fills the fields: they then start again from these values. 0 before `load`. */
    val revision: Int = 0,
    /** The sign-in is open for typing. Until credentials are saved, it always is. */
    val editing: Boolean = false,
    /** Credentials are stored, so Cancel has something to go back to. */
    val hasSaved: Boolean = false,
    /** Credentials were saved, but this phone can no longer decrypt them: they have to be entered again. */
    val unreadable: Boolean = false,
    val siteUrl: String = "",
    val username: String = "",
    val password: String = "",
    val test: TestState = TestState.Idle,
    /** The last thing done to the sign-in was a Save. */
    val saved: Boolean = false,
    val grace: String = "",
    val graceError: String? = null,
    val pattern: String = "",
    val patternError: String? = null,
    /** A location permission is held, as of the last look. */
    val locationPermitted: Boolean = false,
    /** The last request was refused, and Android no longer shows its prompt. */
    val locationBlocked: Boolean = false,
)

data class SettingsUi(
    val form: SettingsForm,
    val settings: Settings,
    /** The one line of the queue row. */
    val queueSummary: String,
    /** Today's title with the pattern as typed, or null when the pattern cannot make one. */
    val titlePreview: String?,
) {
    /** `Attach location` shows on: stored on, with a location permission held. */
    val locationOn: Boolean get() = settings.attachLocation && form.locationPermitted
}

const val GRACE_ERROR = "A whole number from 0 to $MAX_GRACE_SECONDS."
const val PATTERN_ERROR = "Not a date pattern. It was not saved."
const val URL_ERROR = "The site URL must be an https:// address."
const val SIGN_IN_ERROR = "Enter the username and the application password."
const val REJECTED_ERROR = "The site rejected the username or the application password."

/** The settings screen without its screen. */
class SettingsModel(private val container: AppContainer, private val deviceLocale: () -> Locale = { Locale.getDefault() }) {
    private val form = MutableStateFlow(SettingsForm())
    private val settings = container.settings

    /** What is stored, for Cancel to go back to. */
    @Volatile
    private var stored: Credentials? = null

    /** The last credentials the site accepted, with what it reported: kept when exactly these are saved. */
    @Volatile
    private var accepted: Pair<Credentials, SiteStatus>? = null

    val ui: Flow<SettingsUi> = combine(form, settings.flow, container.queue.observe()) { form, settings, entries ->
        SettingsUi(
            form = form,
            settings = settings,
            queueSummary = queueSummary(queueFacts(entries, container.clock.millis()), settings.authError),
            titlePreview = Titles.build(form.pattern, settings.titleLanguage, LocalDate.now(container.clock), deviceLocale()),
        )
    }

    /** Fills the fields with what is stored. */
    suspend fun load() {
        val signIn = container.credentials.get()
        stored = signIn
        val kept = settings.get()
        form.update {
            it.copy(
                revision = it.revision + 1,
                editing = signIn == null,
                hasSaved = signIn != null,
                unreadable = signIn == null && kept.configured,
                siteUrl = signIn?.siteUrl.orEmpty(),
                username = signIn?.username.orEmpty(),
                password = signIn?.password.orEmpty(),
                grace = kept.graceSeconds.toString(),
                pattern = kept.titlePattern,
            )
        }
        checkLocation()
    }

    /** Opens the saved sign-in for typing. */
    fun edit() = form.update { if (it.test == TestState.Running) it else it.copy(editing = true, test = TestState.Idle, saved = false) }

    /** Drops what was typed and shows the saved sign-in again. */
    fun cancel() {
        val signIn = stored ?: return
        form.update {
            if (!it.editing || it.test == TestState.Running) return@update it
            it.copy(
                revision = it.revision + 1,
                editing = false,
                siteUrl = signIn.siteUrl,
                username = signIn.username,
                password = signIn.password,
                test = TestState.Idle,
            )
        }
    }

    fun editSiteUrl(text: String) = typing { it.copy(siteUrl = text) }
    fun editUsername(text: String) = typing { it.copy(username = text) }
    fun editPassword(text: String) = typing { it.copy(password = text) }

    /** Typing counts only while editing, and not while a test runs: the screen locks the fields then. */
    private fun typing(change: (SettingsForm) -> SettingsForm) = form.update {
        if (!it.editing || it.test == TestState.Running) it else change(it).copy(test = TestState.Idle, saved = false)
    }

    /** Stores what is typed, tested or not. Checking it with the site is `Test connection`'s job. */
    suspend fun save() {
        val typed = form.value
        if (!typed.editing || typed.test == TestState.Running) return
        val signIn = signIn(typed) ?: return
        container.credentials.save(signIn)
        stored = signIn
        settings.credentialsSaved()
        accepted?.takeIf { it.first == signIn }?.let { settings.connectionSucceeded(it.second) }
        // Entries held by a rejected sign-in can go now.
        container.scheduler.sync()
        form.update {
            it.copy(
                revision = it.revision + 1,
                editing = false,
                hasSaved = true,
                unreadable = false,
                siteUrl = signIn.siteUrl,
                username = signIn.username,
                password = signIn.password,
                test = TestState.Idle,
                saved = true,
            )
        }
    }

    /** Asks the site whether the sign-in shown works. Stores no credentials: only Save does. */
    suspend fun testConnection() {
        val typed = form.value
        if (typed.test == TestState.Running) return
        val signIn = signIn(typed) ?: return

        form.update { it.copy(test = TestState.Running, saved = false) }
        val test = when (val result = container.apiFor(signIn).status()) {
            is ApiResult.Ok -> {
                accepted = signIn to result.value
                if (signIn == stored) {
                    settings.connectionSucceeded(result.value)
                    // Whatever waited for working credentials can go now.
                    container.scheduler.sync()
                }
                TestState.Passed("Connected as ${result.value.user}. Upload limit: ${megabytes(result.value.maxUploadBytes)}.")
            }
            is ApiResult.Refused -> TestState.Failed(
                when {
                    result.status == 401 -> REJECTED_ERROR
                    result.code == "rest_no_route" -> Sender.PLUGIN_MISSING
                    else -> result.message
                },
            )
            is ApiResult.Unreachable -> TestState.Failed("Could not reach the site: ${result.reason}")
        }
        form.update { it.copy(test = test) }
    }

    /** The credentials as typed, cleaned up, or null after saying what is wrong with them. */
    private fun signIn(typed: SettingsForm): Credentials? {
        val site = normalizeSiteUrl(typed.siteUrl)
        return when {
            site == null -> null.also { failed(URL_ERROR) }
            typed.username.isBlank() || typed.password.isBlank() -> null.also { failed(SIGN_IN_ERROR) }
            else -> Credentials(site, typed.username.trim(), typed.password.trim())
        }
    }

    /** Stores the grace window when it is a number from 0 to 600; otherwise keeps the stored one and says why. */
    suspend fun editGrace(text: String) {
        val seconds = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_GRACE_SECONDS }
        form.update { it.copy(grace = text, graceError = if (seconds == null) GRACE_ERROR else null) }
        if (seconds != null) settings.setGraceSeconds(seconds)
    }

    /** The send job is replaced at once by one that requires the right kind of network. */
    suspend fun setWifiOnly(on: Boolean) {
        settings.setWifiOnly(on)
        container.scheduler.reschedule()
    }

    /** Looks again at the location permission, which can change in Android settings at any time. */
    fun checkLocation() {
        val held = container.location.permission() != Permission.NONE
        form.update { it.copy(locationPermitted = held, locationBlocked = it.locationBlocked && !held) }
    }

    suspend fun setAttachLocation(on: Boolean) = settings.setAttachLocation(on)

    /** Android's answer to the request made when `Attach location` was turned on. `blocked`: refused, and Android will not ask again. */
    suspend fun locationAnswered(granted: Boolean, blocked: Boolean) {
        checkLocation()
        if (granted) settings.setAttachLocation(true) else form.update { it.copy(locationBlocked = blocked) }
    }

    suspend fun chooseCategory(slug: String) = settings.chooseCategory(slug)
    suspend fun setPostStatus(status: PostStatus) = settings.setPostStatus(status)
    suspend fun setTitleLanguage(language: TitleLanguage) = settings.setTitleLanguage(language)
    suspend fun dismissCategoryNotice() = settings.dismissCategoryNotice()

    /** Stores the pattern when it can make a title; otherwise keeps the stored one and says so. */
    suspend fun editPattern(text: String) {
        val valid = Titles.build(text, settings.get().titleLanguage, LocalDate.now(container.clock), deviceLocale()) != null
        form.update { it.copy(pattern = text, patternError = if (valid) null else PATTERN_ERROR) }
        if (valid) settings.setTitlePattern(text)
    }

    private fun failed(message: String) = form.update { it.copy(test = TestState.Failed(message)) }
}

/** `67108864` as `64 MB`; one decimal when it is not a whole number of megabytes. */
fun megabytes(bytes: Long): String {
    val tenths = bytes * 10 / (1024 * 1024)
    return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
}
