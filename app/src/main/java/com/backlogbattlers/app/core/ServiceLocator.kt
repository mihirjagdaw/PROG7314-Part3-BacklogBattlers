package com.backlogbattlers.app.core

import android.content.Context
import android.util.Log
import com.backlogbattlers.app.BuildConfig
import com.backlogbattlers.app.data.local.AppDatabase
import com.backlogbattlers.app.data.local.TokenStorage
import com.backlogbattlers.app.data.remote.AuthApi
import com.backlogbattlers.app.data.remote.DevicesApi
import com.backlogbattlers.app.data.remote.ActivityApi
import com.backlogbattlers.app.data.remote.FriendsApi
import com.backlogbattlers.app.data.remote.GameApi
import com.backlogbattlers.app.data.remote.LeaderboardApi
import com.backlogbattlers.app.data.remote.CompletionApi
import com.backlogbattlers.app.data.remote.ProfileApi
import com.backlogbattlers.app.data.remote.LibraryApi
import com.backlogbattlers.app.data.remote.SettingsApi
import com.backlogbattlers.app.data.remote.SteamApi
import com.backlogbattlers.app.data.repository.AccessTokenSource
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.AuthRepositoryImpl
import com.backlogbattlers.app.data.repository.ActivityRepository
import com.backlogbattlers.app.data.repository.ActivityRepositoryImpl
import com.backlogbattlers.app.data.repository.FriendsRepository
import com.backlogbattlers.app.data.repository.FriendsRepositoryImpl
import com.backlogbattlers.app.data.repository.GameRepository
import com.backlogbattlers.app.data.repository.GameRepositoryImpl
import com.backlogbattlers.app.data.repository.LeaderboardRepository
import com.backlogbattlers.app.data.repository.LeaderboardRepositoryImpl
import com.backlogbattlers.app.data.repository.LibraryRepository
import com.backlogbattlers.app.data.repository.LibraryRepositoryImpl
import com.backlogbattlers.app.data.repository.LibrarySync
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepository
import com.backlogbattlers.app.data.repository.CompletionRepository
import com.backlogbattlers.app.data.repository.CompletionRepositoryImpl
import com.backlogbattlers.app.data.repository.ProfileRepository
import com.backlogbattlers.app.data.repository.ProfileRepositoryImpl
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepositoryImpl
import com.backlogbattlers.app.data.repository.SearchHistoryRepository
import com.backlogbattlers.app.data.repository.SearchHistoryRepositoryImpl
import com.backlogbattlers.app.data.repository.SettingsRepository
import com.backlogbattlers.app.data.repository.SteamAccountRepository
import com.backlogbattlers.app.data.repository.SteamAccountRepositoryImpl
import com.backlogbattlers.app.data.repository.SettingsRepositoryImpl
import com.backlogbattlers.app.data.repository.SettingsSync
import com.backlogbattlers.app.data.repository.SyncCoordinator
import com.backlogbattlers.app.notifications.PushTokenRegistrar
import com.backlogbattlers.app.util.AndroidBiometricAvailabilityChecker
import com.backlogbattlers.app.util.BiometricAvailabilityChecker
import com.backlogbattlers.app.util.UnlockKey
import com.backlogbattlers.app.util.RequestIds
import com.backlogbattlers.app.util.applyLanguage
import com.backlogbattlers.app.util.applyThemeMode
import com.backlogbattlers.app.util.currentLanguage
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.ContentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// dependency locator holding application singletons for database, network, and repository instances
object ServiceLocator {

    // the android engine defaults both timeouts to 100s, which leaves screens loading for over a minute when the backend is unreachable
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val TAG = "ServiceLocator"
    private const val HTTP_TAG = "Http"

    @Volatile
    private var applicationContext: Context? = null

    // for writes that have to finish even though the screen that started them
    // is going away, such as saving the appearance right before the activity
    // is recreated to pick the new theme up
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val database: AppDatabase by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing database")
        AppDatabase.getInstance(context, currentUserId = { tokenStorage.getUserId() })
    }

    val tokenStorage: TokenStorage by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing tokenStorage")
        TokenStorage(context)
    }

    val httpClient: HttpClient by lazy {
        createHttpClient()
    }

    val authApi: AuthApi by lazy {
        AuthApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL)
    }

    val gameApi: GameApi by lazy {
        GameApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL)
    }

    // fires when the backend will no longer accept the signed in session (its refresh token was
    // rejected). MainActivity answers it by going back to login with a message
    private val _sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionEnded: SharedFlow<Unit> = _sessionEnded.asSharedFlow()

    val authRepository: AuthRepository by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing authRepository")
        AuthRepositoryImpl(
            context = context,
            userDao = database.userDao(),
            authApi = authApi,
            tokenStorage = tokenStorage,
            serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            onSessionEnded = { _sessionEnded.tryEmit(Unit) },
            // stops pushes for this account reaching the phone, while its access token still works
            beforeSignOut = { accessToken -> pushTokens.unregister(accessToken) },
        )
    }

    // how the API clients that need a sign in get a valid access token (renewed when it has
    // expired, or when the backend refuses it)
    val accessTokenSource: AccessTokenSource by lazy {
        AccessTokenSource(
            fresh = { authRepository.freshAccessToken() },
            renew = { authRepository.renewAccessToken() },
            endSession = { authRepository.endSession() },
        )
    }

    val friendsApi: FriendsApi by lazy {
        FriendsApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL, tokens = accessTokenSource)
    }

    val friendsRepository: FriendsRepository by lazy {
        FriendsRepositoryImpl(friendsApi)
    }

    val activityApi: ActivityApi by lazy {
        ActivityApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL, tokens = accessTokenSource)
    }

    val profileApi: ProfileApi by lazy {
        ProfileApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL, tokens = accessTokenSource)
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepositoryImpl(
            profileApi,
            // the wide picture first, as on the other game tiles, then the portrait cover
            artFor = { gameId ->
                gameRepository.getGame(gameId)?.let { game -> game.landscapeArtUrls + listOfNotNull(game.coverImageUrl) }.orEmpty()
            },
        )
    }

    val completionApi: CompletionApi by lazy {
        CompletionApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL, tokens = accessTokenSource)
    }

    val completionRepository: CompletionRepository by lazy {
        CompletionRepositoryImpl(
            completionApi,
            syncLibrary = { librarySync.sync() },
            isSynced = { libraryEntryId -> libraryRepository.isSynced(libraryEntryId) },
        )
    }

    val activityRepository: ActivityRepository by lazy {
        ActivityRepositoryImpl(activityApi)
    }

    val leaderboardApi: LeaderboardApi by lazy {
        LeaderboardApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL, tokens = accessTokenSource)
    }

    val leaderboardRepository: LeaderboardRepository by lazy {
        LeaderboardRepositoryImpl(leaderboardApi)
    }

    val gameRepository: GameRepository by lazy {
        GameRepositoryImpl(database.cachedGameDao(), gameApi)
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepositoryImpl(
            database.libraryEntryDao(),
            currentUserId = { tokenStorage.getUserId() },
            // uploads each change as it is made; if that fails it stays pending until the next sync
            onChanged = { applicationScope.launch { librarySync.sync() } },
        )
    }

    val libraryApi: LibraryApi by lazy {
        LibraryApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL)
    }

    val librarySync: LibrarySync by lazy {
        LibrarySync(
            dao = database.libraryEntryDao(),
            libraryApi = libraryApi,
            accessToken = { authRepository.freshAccessToken() },
            currentUserId = { tokenStorage.getUserId() },
            cacheMissingGames = { games ->
                // The sync carries saved summaries. Keep any fuller details already on the phone.
                gameRepository.cacheFromApi(games.filter { gameRepository.getGame(it.gameId) == null })
            },
        )
    }

    val searchHistoryRepository: SearchHistoryRepository by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing searchHistoryRepository")
        SearchHistoryRepositoryImpl(context, currentUserId = { tokenStorage.getUserId() })
    }

    val profileCustomizationRepository: ProfileCustomizationRepository by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing profileCustomizationRepository")
        ProfileCustomizationRepositoryImpl(context, currentUserId = { tokenStorage.getUserId() })
    }

    val settingsRepository: SettingsRepository by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing settingsRepository")
        SettingsRepositoryImpl(context)
    }

    val settingsApi: SettingsApi by lazy {
        SettingsApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL)
    }

    val settingsSync: SettingsSync by lazy {
        SettingsSync(
            settingsRepository = settingsRepository,
            settingsApi = settingsApi,
            accessToken = { authRepository.freshAccessToken() },
            // the theme and the language are applied to the whole app, which recreates the
            // activity on screen; everything else is read from the settings as screens open
            applyToApp = { before, after ->
                withContext(Dispatchers.Main) {
                    if (after.themeMode != before.themeMode) applyThemeMode(after.themeMode)
                    if (after.language != currentLanguage()) applyLanguage(after.language)
                }
            },
        )
    }

    // whether the phone has a working connection. MainApplication starts it listening on launch
    val connectivityMonitor: ConnectivityMonitor by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing connectivityMonitor")
        ConnectivityMonitor(context)
    }

    // uploads what was changed offline as soon as the connection is back, and drives the banner
    // across the top of the app. built on launch (see MainApplication) so it is already listening
    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            online = connectivityMonitor.isOnline,
            scope = applicationScope,
            syncAll = {
                // both always run, so a library that can't be reached still lets the settings through
                val libraryDone = librarySync.sync()
                val settingsDone = settingsSync.sync()
                libraryDone && settingsDone
            },
            hasPending = { librarySync.hasPendingChanges() || settingsSync.hasPendingChanges() },
        )
    }

    val steamApi: SteamApi by lazy {
        SteamApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL)
    }

    val steamAccountRepository: SteamAccountRepository by lazy {
        SteamAccountRepositoryImpl(
            steamApi,
            accessToken = { authRepository.freshAccessToken() },
            cacheGames = { games -> gameRepository.cacheFromApi(games) },
            syncLibrary = { librarySync.sync() },
        )
    }

    val devicesApi: DevicesApi by lazy {
        DevicesApi(httpClient = httpClient, baseUrl = BuildConfig.API_BASE_URL)
    }

    val pushTokens: PushTokenRegistrar by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing pushTokens")
        PushTokenRegistrar(context, devicesApi, accessToken = { authRepository.freshAccessToken() })
    }

    val biometricAvailabilityChecker: BiometricAvailabilityChecker by lazy {
        val context = applicationContext ?: error("ServiceLocator must be initialized with context before accessing biometricAvailabilityChecker")
        AndroidBiometricAvailabilityChecker(context)
    }

    // the keystore key the lock screen's prompt is tied to
    val unlockKey: UnlockKey by lazy { UnlockKey() }

    //------------------------------
    // initializes the ServiceLocator with the application context
    fun init(context: Context) {
        if (applicationContext == null) {
            synchronized(this) {
                if (applicationContext == null) {
                    applicationContext = context.applicationContext
                    Log.d(TAG, "Initialised with the application context")
                }
            }
        }
    }

    //------------------------------
    // builds and configures an HTTP client with Ktor Android engine and JSON content negotiation
    private fun createHttpClient(): HttpClient {
        return HttpClient(Android) {
            engine {
                connectTimeout = CONNECT_TIMEOUT_MS
                socketTimeout = SOCKET_TIMEOUT_MS
            }
            install(ContentNegotiation) {
                json(
                    json = Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                    contentType = ContentType.Any,
                )
            }
        }.apply {
            // one Logcat line per request: method, path, status and how long it took.
            // the id is sent to the api and printed here, so this Logcat line matches the api's
            // line for the same request. query, headers and body are never logged, so no token
            // or personal detail ends up in the log

            plugin(HttpSend).intercept { request ->
                val requestId = RequestIds.next()
                request.headers.append(RequestIds.HEADER, requestId)
                val call = "${request.method.value} ${request.url.encodedPath}"
                val started = System.currentTimeMillis()
                try {
                    execute(request).also {
                        Log.d(HTTP_TAG, "[$requestId] $call -> ${it.response.status.value} in ${System.currentTimeMillis() - started}ms")
                    }
                } catch (e: CancellationException) {
                    // the screen stopped waiting (a newer search replaced it, or it closed): not a failure
                    Log.d(HTTP_TAG, "[$requestId] $call cancelled after ${System.currentTimeMillis() - started}ms")
                    throw e
                } catch (e: Exception) {
                    Log.w(HTTP_TAG, "[$requestId] $call failed after ${System.currentTimeMillis() - started}ms: ${e.javaClass.simpleName}")
                    throw e
                }
            }
        }
    }
}
//------------------------------EOF------------------------------\\
