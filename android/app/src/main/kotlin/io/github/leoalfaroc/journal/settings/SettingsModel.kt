package io.github.leoalfaroc.journal.settings

import io.github.leoalfaroc.journal.AppContainer
import io.github.leoalfaroc.journal.TitleLanguage
import io.github.leoalfaroc.journal.Titles
import io.github.leoalfaroc.journal.data.Credentials
import io.github.leoalfaroc.journal.data.MAX_GRACE_SECONDS
import io.github.leoalfaroc.journal.data.PostStatus
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.data.normalizeSiteUrl
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
    val siteUrl: String = "",
    val username: String = "",
    val password: String = "",
    val test: TestState = TestState.Idle,
    val grace: String = "",
    val graceError: String? = null,
    val pattern: String = "",
    val patternError: String? = null,
)

data class SettingsUi(
    val form: SettingsForm,
    val settings: Settings,
    /** The one line of the queue row. */
    val queueSummary: String,
    /** Today's title with the pattern as typed, or null when the pattern cannot make one. */
    val titlePreview: String?,
)

const val GRACE_ERROR = "A whole number from 0 to $MAX_GRACE_SECONDS."
const val PATTERN_ERROR = "Not a date pattern. It was not saved."
const val URL_ERROR = "The site URL must be an https:// address."
const val SIGN_IN_ERROR = "Enter the username and the application password."
const val REJECTED_ERROR = "The site rejected the username or the application password."

/** The settings screen without its screen. */
class SettingsModel(private val container: AppContainer, private val deviceLocale: () -> Locale = { Locale.getDefault() }) {
    private val form = MutableStateFlow(SettingsForm())
    private val settings = container.settings

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
        val stored = settings.get()
        form.update {
            it.copy(
                revision = it.revision + 1,
                siteUrl = signIn?.siteUrl.orEmpty(),
                username = signIn?.username.orEmpty(),
                password = signIn?.password.orEmpty(),
                grace = stored.graceSeconds.toString(),
                pattern = stored.titlePattern,
            )
        }
    }

    fun editSiteUrl(text: String) = form.update { it.copy(siteUrl = text, test = TestState.Idle) }
    fun editUsername(text: String) = form.update { it.copy(username = text, test = TestState.Idle) }
    fun editPassword(text: String) = form.update { it.copy(password = text, test = TestState.Idle) }

    /**
     * Calls the status endpoint with what is typed. Only credentials that worked are stored:
     * a typing mistake never replaces credentials the sender is using.
     */
    suspend fun testConnection() {
        val typed = form.value
        if (typed.test == TestState.Running) return
        val site = normalizeSiteUrl(typed.siteUrl) ?: return failed(URL_ERROR)
        if (typed.username.isBlank() || typed.password.isBlank()) return failed(SIGN_IN_ERROR)
        val signIn = Credentials(site, typed.username.trim(), typed.password.trim())

        form.update { it.copy(test = TestState.Running) }
        when (val result = container.apiFor(signIn).status()) {
            is ApiResult.Ok -> {
                container.credentials.save(signIn)
                settings.connectionSucceeded(result.value)
                // Whatever waited for working credentials can go now.
                container.scheduler.sync()
                form.update {
                    it.copy(
                        revision = it.revision + 1,
                        siteUrl = signIn.siteUrl,
                        username = signIn.username,
                        password = signIn.password,
                        test = TestState.Passed("Connected as ${result.value.user}. Upload limit: ${megabytes(result.value.maxUploadBytes)}."),
                    )
                }
            }
            is ApiResult.Refused -> failed(
                when {
                    result.status == 401 -> REJECTED_ERROR
                    result.code == "rest_no_route" -> Sender.PLUGIN_MISSING
                    else -> result.message
                },
            )
            is ApiResult.Unreachable -> failed("Could not reach the site: ${result.reason}")
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
