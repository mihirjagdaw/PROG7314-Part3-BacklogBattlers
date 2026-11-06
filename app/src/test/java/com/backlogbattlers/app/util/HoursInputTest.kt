package com.backlogbattlers.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

// what typing into the "hours played" field comes to, and how hours are shown
class HoursInputTest {

    @Test
    fun `a whole or decimal number is accepted`() {
        assertEquals(HoursInput.Valid(12f), parseHours("12"))
        assertEquals(HoursInput.Valid(12.5f), parseHours("12.5"))
        assertEquals(HoursInput.Valid(0f), parseHours("0"))
        assertEquals(HoursInput.Valid(0.5f), parseHours(".5"))
    }

    @Test
    fun `a comma counts as the decimal point and spaces around the number are ignored`() {
        assertEquals(HoursInput.Valid(12.5f), parseHours("12,5"))
        assertEquals(HoursInput.Valid(7f), parseHours("  7  "))
    }

    @Test
    fun `hours are rounded to a tenth`() {
        assertEquals(HoursInput.Valid(12.3f), parseHours("12.34"))
        assertEquals(HoursInput.Valid(12.4f), parseHours("12.36"))
    }

    @Test
    fun `the limit itself is allowed and anything past it is too large`() {
        assertEquals(HoursInput.Valid(100_000f), parseHours("100000"))
        assertEquals(HoursInput.TooLarge, parseHours("100001"))
        assertEquals(HoursInput.TooLarge, parseHours("999999999999"))
    }

    @Test
    fun `nothing typed is empty`() {
        assertEquals(HoursInput.Empty, parseHours(""))
        assertEquals(HoursInput.Empty, parseHours("   "))
    }

    @Test
    fun `a minus sign makes a negative number, not a typo`() {
        assertEquals(HoursInput.Negative, parseHours("-1"))
        assertEquals(HoursInput.Negative, parseHours("-0.5"))
    }

    @Test
    fun `anything that is not a plain number is rejected`() {
        listOf("abc", "12h", "1e3", "NaN", "Infinity", "1.2.3", "--1", "+5", "1 2", ".", "-").forEach {
            assertEquals("'$it'", HoursInput.NotANumber, parseHours(it))
        }
    }

    @Test
    fun `hours are shown without a decimal point when whole, otherwise with one decimal`() {
        assertEquals("12", formatHoursPlayed(12f))
        assertEquals("12.5", formatHoursPlayed(12.5f))
        assertEquals("0", formatHoursPlayed(0f))
        assertEquals("0.3", formatHoursPlayed(0.3f))
        assertEquals("100000", formatHoursPlayed(100_000f))
    }
}
//------------------------------EOF------------------------------\\
