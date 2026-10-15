package io.github.leoalfaroc.journal

import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TitleLanguage(val label: String) {
    ENGLISH("English"),
    SPANISH("Spanish"),
    DEVICE("Device language"),
}

/** Builds the title of a day post from the date pattern and the language chosen in settings. */
object Titles {
    const val DEFAULT_PATTERN = "yyyy-MM-dd '·' EEEE, MMMM d"

    /** The plugin rejects a longer title. */
    const val MAX_LENGTH = 200

    fun locale(language: TitleLanguage, device: Locale): Locale = when (language) {
        TitleLanguage.ENGLISH -> Locale.ENGLISH
        TitleLanguage.SPANISH -> Locale.forLanguageTag("es")
        TitleLanguage.DEVICE -> device
    }

    /**
     * The title for `day`, or null when the pattern cannot make one: it is not a date pattern,
     * it asks for a time of day, or its result is empty or too long.
     */
    fun build(pattern: String, language: TitleLanguage, day: LocalDate, device: Locale): String? = try {
        DateTimeFormatter.ofPattern(pattern, locale(language, device)).format(day).trim()
            .takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_LENGTH }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: DateTimeException) {
        null
    }
}
