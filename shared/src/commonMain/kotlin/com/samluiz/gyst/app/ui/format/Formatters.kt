package com.samluiz.gyst.app

import com.samluiz.gyst.domain.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

internal fun formatBrl(cents: Long): String {
    val abs = kotlin.math.abs(cents)
    val reais = abs / 100
    val cent = abs % 100
    val signal = if (cents < 0) "-" else ""
    val grouped = groupThousands(reais)
    return "$signal R$ $grouped,${pad2(cent)}"
}

internal fun formatBrlFromCentsDigits(centsDigits: String): String {
    val digits = centsDigits.filter(Char::isDigit).ifBlank { "0" }
    val cents = digits.toLongOrNull() ?: 0L
    val reais = cents / 100
    val cent = cents % 100
    val grouped = groupThousands(reais)
    return "R$ $grouped,${pad2(cent)}"
}

internal fun formatSigned(cents: Long): String {
    val prefix = if (cents > 0) "+" else ""
    return "$prefix${formatBrl(cents)}"
}

internal fun pad2(value: Long): String {
    return if (value < 10L) "0$value" else value.toString()
}

internal fun pad2(value: Int): String {
    return if (value < 10) "0$value" else value.toString()
}

internal fun groupThousands(value: Long): String {
    return value.toString().reversed().chunked(3).joinToString(".").reversed()
}

internal fun formatYearMonthHuman(
    yearMonth: YearMonth,
    languageCode: String,
): String {
    val month = dateLocale(languageCode).months[yearMonth.month - 1]
    return "$month ${yearMonth.year}"
}

internal fun formatLocalDateHuman(
    date: LocalDate,
    languageCode: String,
): String {
    val relative = relativeDateLabel(date, languageCode)
    if (relative != null) return relative
    val locale = dateLocale(languageCode)
    return locale.absoluteDate(date)
}

internal fun formatInstantHuman(
    iso: String,
    languageCode: String,
): String {
    return runCatching {
        val local = Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
        val timeLabel = "${pad2(local.hour)}:${pad2(local.minute)}"
        val relative = relativeDateLabel(local.date, languageCode)
        if (relative != null) {
            "$relative, $timeLabel"
        } else {
            val dateLabel = formatLocalDateHuman(local.date, languageCode)
            "$dateLabel, $timeLabel"
        }
    }.getOrDefault(iso)
}

internal fun relativeDateLabel(
    date: LocalDate,
    languageCode: String,
): String? {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val diff = date.toEpochDays() - today.toEpochDays()
    val locale = dateLocale(languageCode)
    return when {
        diff == 0L -> locale.today
        diff == -1L -> locale.yesterday
        diff == 1L -> locale.tomorrow
        diff in 2L..7L -> locale.inDays(diff)
        diff in -7L..-2L -> locale.daysAgo(-diff)
        else -> null
    }
}

internal fun monthShortName(
    month: Int,
    languageCode: String,
): String {
    val safeIndex = (month - 1).coerceIn(0, 11)
    return dateLocale(languageCode).months[safeIndex]
}

private data class DateLocale(
    val months: List<String>,
    val today: String,
    val yesterday: String,
    val tomorrow: String,
    val absoluteDate: (LocalDate) -> String,
    val inDays: (Long) -> String,
    val daysAgo: (Long) -> String,
)

private val portugueseDateLocale =
    DateLocale(
        months = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"),
        today = "Hoje",
        yesterday = "Ontem",
        tomorrow = "Amanhã",
        absoluteDate = { date -> "${pad2(date.day)} ${monthShortName(date.month.ordinal + 1, "pt")} ${date.year}" },
        inDays = { days -> "Em $days dias" },
        daysAgo = { days -> "Há $days dias" },
    )

private val englishDateLocale =
    DateLocale(
        months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        today = "Today",
        yesterday = "Yesterday",
        tomorrow = "Tomorrow",
        absoluteDate = { date -> "${monthShortName(date.month.ordinal + 1, "en")} ${date.day}, ${date.year}" },
        inDays = { days -> "In $days days" },
        daysAgo = { days -> "$days days ago" },
    )

private fun dateLocale(languageCode: String): DateLocale =
    when (languageCode.lowercase()) {
        "pt", "pt-br" -> portugueseDateLocale
        else -> englishDateLocale
    }
