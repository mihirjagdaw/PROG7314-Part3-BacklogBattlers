package com.backlogbattlers.app.util

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// unit tests for the biometric lock's decisions: when it closes, when it comes back, and what it says
class BiometricSupportTest {

    @Test
    //------------------------------
    // verifies the lock screen is not in the way when the setting is turned off
    fun `the lock is not required when the setting is off`() {
        assertFalse(shouldRequireBiometricUnlock(biometricEnabled = false))
    }

    @Test
    //------------------------------
    // verifies the lock is required whenever the user turned it on
    fun `the lock is required when the setting is on`() {
        assertTrue(shouldRequireBiometricUnlock(biometricEnabled = true))
    }

    @Test
    //------------------------------
    // verifies a phone that can't unlock any more is told why instead of letting the app open
    fun `a phone that lost its sensor or screen lock fails closed with the unavailable reason`() {
        assertEquals(BiometricLockReason.UNAVAILABLE, lockReasonForAvailability(BiometricAvailability.NoHardware))
        assertEquals(BiometricLockReason.UNAVAILABLE, lockReasonForAvailability(BiometricAvailability.NotEnrolled))
        assertEquals(BiometricLockReason.UNAVAILABLE, lockReasonForAvailability(BiometricAvailability.Unavailable))
    }

    @Test
    //------------------------------
    // verifies a phone that can unlock has no reason to stay locked
    fun `a phone that can unlock has no lock reason`() {
        assertNull(lockReasonForAvailability(BiometricAvailability.Available))
    }

    @Test
    //------------------------------
    // verifies the app locks again after a minute or more in the background
    fun `the app locks again after the timeout in the background`() {
        assertTrue(shouldRelock(biometricEnabled = true, alreadyLocked = false, backgroundedAt = 1_000, now = 61_000))
        assertTrue(shouldRelock(biometricEnabled = true, alreadyLocked = false, backgroundedAt = 1_000, now = 500_000))
    }

    @Test
    //------------------------------
    // verifies a quick trip out of the app (the notification shade, a permission dialog) doesn't lock it
    fun `the app does not lock again after a short time away`() {
        assertFalse(shouldRelock(biometricEnabled = true, alreadyLocked = false, backgroundedAt = 1_000, now = 60_999))
        assertFalse(shouldRelock(biometricEnabled = true, alreadyLocked = false, backgroundedAt = 1_000, now = 1_000))
    }

    @Test
    //------------------------------
    // verifies the exact boundary: away for exactly the timeout locks
    fun `being away for exactly the timeout locks the app`() {
        assertTrue(shouldRelock(biometricEnabled = true, alreadyLocked = false, backgroundedAt = 0, now = RELOCK_AFTER_MILLIS))
    }

    @Test
    //------------------------------
    // verifies nothing locks when the user never turned the lock on
    fun `the app never locks again when the lock is off`() {
        assertFalse(shouldRelock(biometricEnabled = false, alreadyLocked = false, backgroundedAt = 0, now = 10_000_000))
    }

    @Test
    //------------------------------
    // verifies the lock isn't stacked on top of the lock screen, the login screen or the splash
    fun `the app does not lock again while already locked or signed out`() {
        assertFalse(shouldRelock(biometricEnabled = true, alreadyLocked = true, backgroundedAt = 0, now = 10_000_000))
    }

    @Test
    //------------------------------
    // verifies an app that never went to the background has nothing to lock
    fun `the app does not lock again when it was never in the background`() {
        assertFalse(shouldRelock(biometricEnabled = true, alreadyLocked = false, backgroundedAt = null, now = 10_000_000))
    }

    @Test
    //------------------------------
    // verifies a custom timeout is respected
    fun `the timeout can be changed`() {
        assertTrue(shouldRelock(true, false, backgroundedAt = 0, now = 5_000, timeoutMillis = 5_000))
        assertFalse(shouldRelock(true, false, backgroundedAt = 0, now = 4_999, timeoutMillis = 5_000))
    }

    @Test
    //------------------------------
    // verifies turning the lock off is confirmed with the fingerprint or screen lock when the phone has one
    fun `turning the lock off is confirmed first on a phone that can unlock`() {
        assertTrue(shouldConfirmBeforeTurningOff(BiometricAvailability.Available))
    }

    @Test
    //------------------------------
    // verifies a phone with nothing to confirm with can still turn the lock off, so it is never stuck on
    fun `turning the lock off needs no confirmation on a phone that cannot unlock`() {
        assertFalse(shouldConfirmBeforeTurningOff(BiometricAvailability.NoHardware))
        assertFalse(shouldConfirmBeforeTurningOff(BiometricAvailability.NotEnrolled))
        assertFalse(shouldConfirmBeforeTurningOff(BiometricAvailability.Unavailable))
    }

    @Test
    //------------------------------
    // verifies the "unlock with your fingerprint?" question comes after a first sign-in on a capable phone
    fun `fingerprint unlock is offered once, on a phone that can do it`() {
        assertTrue(shouldOfferBiometricUnlock(biometricEnabled = false, alreadyOffered = false, availability = BiometricAvailability.Available))
    }

    @Test
    //------------------------------
    // verifies the question is never repeated, or asked when it is already on
    fun `fingerprint unlock is not offered again, or when it is already on`() {
        assertFalse(shouldOfferBiometricUnlock(biometricEnabled = false, alreadyOffered = true, availability = BiometricAvailability.Available))
        assertFalse(shouldOfferBiometricUnlock(biometricEnabled = true, alreadyOffered = false, availability = BiometricAvailability.Available))
    }

    @Test
    //------------------------------
    // verifies a phone with nothing to unlock with is never asked
    fun `fingerprint unlock is not offered on a phone that cannot do it`() {
        assertFalse(shouldOfferBiometricUnlock(biometricEnabled = false, alreadyOffered = false, availability = BiometricAvailability.NoHardware))
        assertFalse(shouldOfferBiometricUnlock(biometricEnabled = false, alreadyOffered = false, availability = BiometricAvailability.NotEnrolled))
    }

    @Test
    //------------------------------
    // verifies each way the prompt can close maps onto what the lock screen should say
    fun `prompt errors map onto the lock screen's reasons`() {
        assertEquals(BiometricLockReason.CANCELLED, lockReasonFor(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(BiometricLockReason.CANCELLED, lockReasonFor(BiometricPrompt.ERROR_CANCELED))
        assertEquals(BiometricLockReason.LOCKED_OUT, lockReasonFor(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(BiometricLockReason.LOCKED_OUT, lockReasonFor(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
        assertEquals(BiometricLockReason.UNAVAILABLE, lockReasonFor(BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertEquals(BiometricLockReason.UNAVAILABLE, lockReasonFor(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL))
    }
}
//------------------------------EOF------------------------------\\
