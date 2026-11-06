package com.backlogbattlers.app.util

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// what the lock screen gets when it asks for the key to unlock with
sealed class UnlockChallenge {

    // this phone can't tie the unlock to a key (android 10 and older can't pair a key with the
    // screen lock, or the keystore failed), so the lock is the prompt alone, as before
    data object NotSupported : UnlockChallenge()

    // a cipher the prompt must authorise: only a real fingerprint, face or screen lock opens it
    data class Ready(val cipher: Cipher) : UnlockChallenge()

    // a fingerprint or face was added or removed since the key was made, so the key is gone
    data object Invalidated : UnlockChallenge()
}

//------------------------------
// the key behind the lock. the fingerprint prompt on its own is only a yes or no that the app has
// to believe: anything that makes the callback fire would unlock it. this key lives in the
// phone's keystore (in secure hardware where there is any) and is set to be usable only right
// after the user authenticates, once per use. the prompt is handed a cipher made from it (a
// CryptoObject), and the app only counts the unlock if that cipher then really works, which it can
// only do if the keystore saw a fingerprint, face or screen lock succeed.
// the key protects nothing stored: it is there to prove the user was present.
// it is made when the lock is first needed, removed when the lock is turned off or the account is
// deleted, and is destroyed by the system when a fingerprint or face is enrolled (see Invalidated).
// pattern: Android Biometric guide, "Use a cryptographic solution that depends on authentication"
// https://developer.android.com/identity/sign-in/biometric-auth#crypto
class UnlockKey {

    //------------------------------
    // makes the key if there is none yet, and returns a cipher for the prompt to authorise
    fun challenge(): UnlockChallenge {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return UnlockChallenge.NotSupported
        return try {
            val key = existingKey() ?: createKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // can't be used until the prompt succeeds, and throws if the key was invalidated
            cipher.init(Cipher.ENCRYPT_MODE, key)
            UnlockChallenge.Ready(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "The unlock key was invalidated, fingerprints changed")
            // gone for good: the next challenge, after signing in again, makes a fresh one
            delete()
            UnlockChallenge.Invalidated
        } catch (e: Exception) {
            Log.w(TAG, "The unlock key could not be used, falling back to the prompt alone: ${e.message}")
            UnlockChallenge.NotSupported
        }
    }

    //------------------------------
    // true when the keystore really let this cipher run, which it only does after the user
    // authenticated. a cipher that was never authorised, or none at all, is not a yes
    fun isAuthorised(cipher: Cipher?): Boolean {
        if (cipher == null) return false
        return try {
            val challenge = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
            cipher.doFinal(challenge)
            true
        } catch (e: Exception) {
            Log.w(TAG, "The unlock key did not confirm the unlock: ${e.message}")
            false
        }
    }

    //------------------------------
    // removes the key: the lock was turned off or the account is gone
    fun delete() {
        try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "The unlock key could not be removed: ${e.message}")
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    //------------------------------
    // a 256 bit AES key that needs the user to authenticate for every single use, with a strong
    // biometric or the screen lock, matching what the prompt allows. replaced by the system the
    // moment someone enrols another fingerprint
    @RequiresApi(Build.VERSION_CODES.R)
    private fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            // 0 seconds: authenticate again for every use, with the key's cipher in the prompt
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val TAG = "UnlockKey"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "backlog_battlers_unlock_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val CHALLENGE_BYTES = 16
    }
}
//------------------------------EOF------------------------------\\
