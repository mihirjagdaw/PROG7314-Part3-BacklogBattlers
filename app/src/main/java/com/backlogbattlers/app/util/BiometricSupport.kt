package com.backlogbattlers.app.util

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt

// whether the device can currently unlock the app and why not if it cant
sealed class BiometricAvailability {

    // a fingerprint, face or screen lock is ready to unlock with
    data object Available : BiometricAvailability()

    // device lacks biometric hardware or it is unavailable
    data object NoHardware : BiometricAvailability()

    // nothing is set up yet: no fingerprint or face, and no screen lock to fall back on
    data object NotEnrolled : BiometricAvailability()

    // biometric authentication is currently unavailable
    data object Unavailable : BiometricAvailability()
}

//------------------------------
// what can unlock the app: a fingerprint or face, with the phone's own PIN, pattern or password
// as the fallback when the scan fails or the user would rather type. android 9 and 10 can't pair
// a strong biometric with the screen lock, so there the weak class is used alongside it
val UNLOCK_AUTHENTICATORS: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }

// checks whether this device can currently unlock the app
fun interface BiometricAvailabilityChecker {
    fun check(): BiometricAvailability
}

// implemented with androidx.biometric.BiometricManager
class AndroidBiometricAvailabilityChecker(
    private val context: Context,
) : BiometricAvailabilityChecker {

    //------------------------------
    // asks BiometricManager whether the unlock (biometric or screen lock) is currently possible and maps its result to a BiometricAvailability
    override fun check(): BiometricAvailability {
        val result = BiometricManager.from(context).canAuthenticate(UNLOCK_AUTHENTICATORS)

        // this when picks the specific reason a check would fail, so callers can explain it
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            else -> BiometricAvailability.Unavailable
        }
    }
}

//------------------------------
// decides whether the lock screen stands between the user and the app. it does whenever the user
// turned the lock on, whatever the phone can do right now: the lock fails closed. a phone that lost
// its fingerprint or screen lock does not let the app open unlocked, the lock screen explains and
// offers to sign in again instead (see lockReasonForAvailability)
fun shouldRequireBiometricUnlock(biometricEnabled: Boolean): Boolean = biometricEnabled

//------------------------------
// why the phone can't unlock right now, or null when it can. what the lock screen says when the
// lock is on but the sensor or the screen lock has gone
fun lockReasonForAvailability(availability: BiometricAvailability): BiometricLockReason? = when (availability) {
    BiometricAvailability.Available -> null
    BiometricAvailability.NoHardware,
    BiometricAvailability.NotEnrolled,
    BiometricAvailability.Unavailable -> BiometricLockReason.UNAVAILABLE
}

// how long the app can sit in the background before it locks again
const val RELOCK_AFTER_MILLIS = 60_000L

//------------------------------
// whether coming back to the app should lock it again: the lock is on, the user is past the first
// unlock, and the app was out of sight for at least [timeoutMillis]. times are from the same
// monotonic clock (SystemClock.elapsedRealtime), so changing the phone's clock can't skip the lock.
// [backgroundedAt] is null when the app was never in the background since it was unlocked
fun shouldRelock(
    biometricEnabled: Boolean,
    alreadyLocked: Boolean,
    backgroundedAt: Long?,
    now: Long,
    timeoutMillis: Long = RELOCK_AFTER_MILLIS,
): Boolean {
    if (!biometricEnabled || alreadyLocked || backgroundedAt == null) return false
    return now - backgroundedAt >= timeoutMillis
}

//------------------------------
// whether turning the lock off must be confirmed with a fingerprint, face or screen lock first, so
// someone holding an unlocked phone can't just switch it off. a phone that can't unlock at all
// (the screen lock was removed) has nothing to confirm with, and must not be stuck with the lock on
fun shouldConfirmBeforeTurningOff(availability: BiometricAvailability): Boolean {
    return availability == BiometricAvailability.Available
}

//------------------------------
// whether to ask "unlock with your fingerprint next time?" right after signing in: only once per
// phone, only if it isn't on already, and only on a phone that can actually do it
fun shouldOfferBiometricUnlock(
    biometricEnabled: Boolean,
    alreadyOffered: Boolean,
    availability: BiometricAvailability,
): Boolean {
    return !biometricEnabled && !alreadyOffered && availability == BiometricAvailability.Available
}

//------------------------------
// why the lock screen is waiting rather than unlocked, so it can say what to do next
enum class BiometricLockReason {
    // the user closed the prompt: they can try again, or sign in again instead
    CANCELLED,

    // too many failed attempts: the phone has locked the sensor for a while
    LOCKED_OUT,

    // the sensor or screen lock stopped being available, e.g. the fingerprint was removed
    UNAVAILABLE,

    // a fingerprint or face was added or removed since the lock was turned on, which invalidates the
    // lock's key. anyone who could add a fingerprint could have been someone else, so the only way
    // on is to sign in again
    CHANGED,

    // the prompt said yes but the lock's key did not confirm it, so the unlock is not trusted
    NOT_VERIFIED,
}

// maps the prompt's error code onto a reason the lock screen can explain
fun lockReasonFor(errorCode: Int): BiometricLockReason = when (errorCode) {
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricLockReason.LOCKED_OUT
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> BiometricLockReason.UNAVAILABLE
    else -> BiometricLockReason.CANCELLED
}
//------------------------------EOF------------------------------\\
