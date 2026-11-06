package com.backlogbattlers.api.notifications

import com.backlogbattlers.api.Config
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/**
 * Delivers a data payload to phones. Returns the tokens that no longer exist, so they can be forgotten.
 *
 * The real implementation, [FcmPushSender], follows the Firebase Cloud Messaging guides: sending
 * from a server with the Admin SDK (https://firebase.google.com/docs/cloud-messaging/send-message),
 * data-only messages that the app turns into a notification itself
 * (https://firebase.google.com/docs/cloud-messaging/android/receive), and removing registration
 * tokens FCM reports as gone (https://firebase.google.com/docs/cloud-messaging/manage-tokens).
 */
fun interface PushSender {
    suspend fun send(tokens: List<String>, data: Map<String, String>): List<String>

    companion object {
        private val log = LoggerFactory.getLogger(PushSender::class.java)

        /**
         * The real sender when a Firebase service account is configured, otherwise one that only
         * logs, so the API still runs on a laptop without Firebase. The service account comes from
         * (see [Config]):
         *   FIREBASE_SERVICE_ACCOUNT_FILE  path to the JSON key downloaded from the Firebase console
         *   FIREBASE_SERVICE_ACCOUNT_JSON  the same JSON pasted in whole, easier on a host such as Render
         */
        fun fromConfig(config: Config = Config.default): PushSender {
            val json = config.get("FIREBASE_SERVICE_ACCOUNT_JSON")
            val file = config.get("FIREBASE_SERVICE_ACCOUNT_FILE")
            val key: InputStream = when {
                json != null -> json.byteInputStream()
                file != null -> File(file).takeIf { it.isFile }?.inputStream()
                    ?: return LoggingPushSender.also { log.warn("FIREBASE_SERVICE_ACCOUNT_FILE {} does not exist - pushes are only logged", file) }
                else -> return LoggingPushSender.also { log.warn("No Firebase service account is set - pushes are only logged") }
            }
            return try {
                FcmPushSender(key.use { firebaseMessaging(it) }).also { log.info("Push notifications go through Firebase Cloud Messaging") }
            } catch (e: Exception) {
                log.error("The Firebase service account could not be read - pushes are only logged", e)
                LoggingPushSender
            }
        }

        private fun firebaseMessaging(serviceAccount: InputStream): FirebaseMessaging {
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()
            val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(options, APP_NAME)
            return FirebaseMessaging.getInstance(app)
        }

        private const val APP_NAME = "backlog-battlers"
    }
}

/** Sends through Firebase Cloud Messaging with the Firebase Admin SDK. */
class FcmPushSender(private val messaging: FirebaseMessaging) : PushSender {

    override suspend fun send(tokens: List<String>, data: Map<String, String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        // high priority wakes the app to show a data message straight away, even in Doze
        val android = AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build()
        // setToken is marked deprecated in favour of Firebase installation ids (setFid), but the
        // registration token is what the app's FirebaseMessaging.getToken() hands out, and FCM
        // still delivers to it
        @Suppress("DEPRECATION")
        val messages = tokens.map { token ->
            Message.builder().setToken(token).putAllData(data).setAndroidConfig(android).build()
        }

        // the Admin SDK blocks while it talks to Google, so it runs off the request threads
        val response = withContext(Dispatchers.IO) { messaging.sendEach(messages) }
        if (response.failureCount > 0) log.warn("{} of {} pushes failed", response.failureCount, tokens.size)

        return response.responses.mapIndexedNotNull { index, result ->
            tokens[index].takeIf { !result.isSuccessful && result.exception?.messagingErrorCode in GONE }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FcmPushSender::class.java)

        // what FCM answers for a token that will never work again
        val GONE = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)
    }
}

/** Stands in when Firebase is not configured: logs the push type and how many phones it was for. */
object LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger(LoggingPushSender::class.java)

    override suspend fun send(tokens: List<String>, data: Map<String, String>): List<String> {
        log.info("Would push {} to {} phone(s)", data["type"], tokens.size)
        return emptyList()
    }
}
//------------------------------EOF------------------------------\\
