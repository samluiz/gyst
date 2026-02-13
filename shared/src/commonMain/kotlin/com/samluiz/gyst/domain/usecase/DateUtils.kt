package com.samluiz.gyst.domain.usecase

import com.samluiz.gyst.domain.model.YearMonth
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlin.time.Clock

internal fun monthBounds(yearMonth: YearMonth): Pair<LocalDate, LocalDate> {
    val first = LocalDate(yearMonth.year, yearMonth.month, 1)
    val next = yearMonth.plusMonths(1)
    val nextMonthFirst = LocalDate(next.year, next.month, 1)
    val last = nextMonthFirst.minus(DatePeriod(days = 1))
    return first to last
}

internal fun nowInstantUtc() = Clock.System.now()

internal fun clampBillingDay(yearMonth: YearMonth, billingDay: Int): Int {
    val (_, last) = monthBounds(yearMonth)
    val maxDay = last.dayOfMonth
    return billingDay.coerceIn(1, maxDay)
}

internal fun dueDateForMonth(yearMonth: YearMonth, billingDay: Int): LocalDate {
    val day = clampBillingDay(yearMonth, billingDay)
    return LocalDate(yearMonth.year, yearMonth.month, day)
}

internal fun localDateToInstant(date: LocalDate) = date.atStartOfDayIn(TimeZone.UTC)
