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
    val month = monthShortName(yearMonth.month, languageCode)
    return "$month ${yearMonth.year}"
}

internal fun formatLocalDateHuman(
    date: LocalDate,
    languageCode: String,
): String {
    val relative = relativeDateLabel(date, languageCode)
    if (relative != null) return relative
    val month = monthShortName(date.month.ordinal + 1, languageCode)
    return if (languageCode == "pt") {
        "${pad2(date.day)} $month ${date.year}"
    } else {
        "$month ${date.day}, ${date.year}"
    }
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
    return when {
        diff == 0L -> if (languageCode == "pt") "Hoje" else "Today"
        diff == -1L -> if (languageCode == "pt") "Ontem" else "Yesterday"
        diff == 1L -> if (languageCode == "pt") "Amanha" else "Tomorrow"
        diff in 2L..7L -> if (languageCode == "pt") "Em $diff dias" else "In $diff days"
        diff in -7L..-2L -> {
            val days = -diff
            if (languageCode == "pt") "Ha $days dias" else "$days days ago"
        }
        else -> null
    }
}

internal fun monthShortName(
    month: Int,
    languageCode: String,
): String {
    val pt =
        listOf(
            "jan", "fev", "mar", "abr", "mai", "jun",
            "jul", "ago", "set", "out", "nov", "dez",
        )
    val en =
        listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
    val safeIndex = (month - 1).coerceIn(0, 11)
    return if (languageCode == "pt") pt[safeIndex] else en[safeIndex]
}


