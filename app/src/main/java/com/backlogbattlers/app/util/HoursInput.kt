package com.backlogbattlers.app.util

import kotlin.math.round

// the most hours a player can log on one game. the backend refuses more, so the field says so first
const val MAX_HOURS_PLAYED = 100_000f

//------------------------------
// what typing into the "hours played" field came to: a number to save, or why it can't be saved
sealed interface HoursInput {
    // [hours] is rounded to a tenth of an hour, which is as fine as anyone keeps track
    data class Valid(val hours: Float) : HoursInput
    data object Empty : HoursInput
    data object NotANumber : HoursInput
    data object Negative : HoursInput
    data object TooLarge : HoursInput
}

//------------------------------
// reads what the player typed. a comma counts as a decimal point (12,5 is 12.5), because that is how
// many phones in South Africa type it. leading and trailing spaces are ignored. nothing but a plain
// number is accepted: "12h", "1e3" and "NaN" are not numbers here
fun parseHours(text: String): HoursInput {
    val cleaned = text.trim().replace(',', '.')
    if (cleaned.isEmpty()) return HoursInput.Empty
    if (!PLAIN_NUMBER.matches(cleaned)) {
        // a minus sign in front of an otherwise fine number is a negative number, not a typo
        return if (cleaned.startsWith("-") && PLAIN_NUMBER.matches(cleaned.drop(1))) HoursInput.Negative else HoursInput.NotANumber
    }
    val hours = cleaned.toFloatOrNull() ?: return HoursInput.NotANumber
    if (hours > MAX_HOURS_PLAYED) return HoursInput.TooLarge
    return HoursInput.Valid(round(hours * 10f) / 10f)
}

private val PLAIN_NUMBER = Regex("""\d+(\.\d+)?|\.\d+""")

//------------------------------
// hours as shown: a whole number without a decimal point (12), otherwise one decimal (12.5)
fun formatHoursPlayed(hours: Float): String {
    val tenths = round(hours * 10f).toLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
//------------------------------EOF------------------------------\\
