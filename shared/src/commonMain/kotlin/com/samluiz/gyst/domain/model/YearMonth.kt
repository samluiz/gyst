package com.samluiz.gyst.domain.model

import kotlinx.datetime.LocalDate

data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    init {
        require(month in 1..12) { "Month must be between 1 and 12" }
    }

    override fun compareTo(other: YearMonth): Int {
        return if (year != other.year) year.compareTo(other.year) else month.compareTo(other.month)
    }

    override fun toString(): String {
        val y = year.toString().padStart(4, '0')
        val m = month.toString().padStart(2, '0')
        return "$y-$m"
    }

    fun atDay(day: Int): LocalDate = LocalDate(year, month, day)

    fun plusMonths(months: Int): YearMonth {
        val absolute = year * 12 + (month - 1) + months
        val newYear = absolute / 12
        val newMonth = absolute % 12 + 1
        return YearMonth(newYear, newMonth)
    }

    companion object {
        fun parse(value: String): YearMonth {
            val parts = value.split("-")
            require(parts.size == 2) { "Invalid YearMonth: $value" }
            return YearMonth(parts[0].toInt(), parts[1].toInt())
        }

        fun fromDate(date: LocalDate): YearMonth = YearMonth(date.year, date.month.ordinal + 1)
    }
}
