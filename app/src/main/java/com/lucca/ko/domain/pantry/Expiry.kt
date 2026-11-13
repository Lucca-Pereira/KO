package com.lucca.ko.domain.pantry

import java.time.LocalDate

/** Items expiring within this many days (or already expired) count as "use soon". */
const val USE_SOON_DAYS = 3L

/** Days until [expiresAt] (an epoch day) from [today]; negative once expired. */
fun daysUntilExpiry(expiresAt: Long, today: LocalDate = LocalDate.now()): Long =
    expiresAt - today.toEpochDay()

fun isUseSoon(expiresAt: Long?, today: LocalDate = LocalDate.now()): Boolean =
    expiresAt != null && daysUntilExpiry(expiresAt, today) <= USE_SOON_DAYS

fun expiryLabel(expiresAt: Long, today: LocalDate = LocalDate.now()): String {
    val d = daysUntilExpiry(expiresAt, today)
    return when {
        d < 0 -> "expired"
        d == 0L -> "expires today"
        d == 1L -> "expires in 1 day"
        else -> "expires in $d days"
    }
}
