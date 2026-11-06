<a id="readme-top"></a>
<div align="center">
  <a href="https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers">
    <img src="images/BacklogBattlers_Logo.png" alt="Logo" width="120" height="90">
  </a>

<h3 align="center">Backlog Battlers</h3>
  <p align="center">
    PROG7314 Final POE. Backlog Battlers is an Android app that turns finishing your game backlog into a monthly competition with friends.
    <br />
    <a href="https://backlog-battlers-api-7314-gyancxcndrh0akgn.uaenorth-01.azurewebsites.net/health">Live API</a>
    ·
    <a href="#video-demonstration">Video</a>
    ·
    <a href="#release-notes">Release notes</a>
    <br />
    <br />
    <a href="https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers/actions/workflows/android-ci.yml">
      <img src="https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers/actions/workflows/android-ci.yml/badge.svg" alt="Android CI status">
    </a>
    <a href="https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers/actions/workflows/api-ci.yml">
      <img src="https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers/actions/workflows/api-ci.yml/badge.svg" alt="API CI status">
    </a>
  </p>
</div>

## Team Members
<table>
  <tr>
    <td align="center"><img src="https://github.com/ST10449392.png" width="80"/></td>
    <td>
      <b>Mihir Jagdaw</b><br/>
      Student Number: 10449392<br/>
      <a href="https://github.com/ST10449392">@ST10449392</a>
    </td>
  </tr>
  <tr>
    <td align="center"><img src="https://github.com/AndreFourie12.png" width="80"/></td>
    <td>
      <b>Andre Fourie</b><br/>
      Student Number: 10438312<br/>
      <a href="https://github.com/AndreFourie12">@AndreFourie12</a>
    </td>
  </tr>
  <tr>
    <td align="center"><img src="https://github.com/Doingle.png" width="80"/></td>
    <td>
      <b>Dylan Jurgens</b><br/>
      Student Number: 10434135<br/>
      <a href="https://github.com/Doingle">@Doingle</a>
    </td>
  </tr>
</table>

# A note on font:
**Please note that the font which appears on the video demonstration as well as all application screenshots does not genuinly reflect the font utilised by our application, it is a custom font applied to the phone's system settings (Dylan's phone) used for recording, to view the genuine fonts utilised, please either run the built APK, or follow the build process to run on the emulator in Android Studios. Thanks for your understanding.**

##  Table of Contents
<ol>
  <li>
    <a href="#project-overview">Project Overview</a>
    <ul>
      <li><a href="#built-with">Built With</a></li>
    </ul>
  </li>
  <li><a href="#core-purpose-and-scope">Core Purpose and Scope</a></li>
  <li><a href="#where-to-find-each-requirement">Where to Find Each Requirement</a></li>
  <li>
    <a href="#features">Features</a>
    <ul>
      <li><a href="#core-features">Core Features</a></li>
      <li><a href="#final-poe-features">Final POE Features</a></li>
      <li><a href="#user-defined-features">User Defined Features</a></li>
    </ul>
  </li>
  <li><a href="#architecture">Architecture</a></li>
  <li><a href="#design-consideration-and-architectural-choices">Design Consideration and Architectural Choices</a></li>
  <li><a href="#logging-and-request-tracing">Logging and Request Tracing</a></li>
  <li><a href="#hosting-and-deployment">Hosting and Deployment</a></li>
  <li>
    <a href="#comprehensive-summary">Comprehensive Summary</a>
    <ul>
      <li><a href="#implementation-of-version-control">Implementation of Version Control</a></li>
      <li><a href="#github-actions">GitHub Actions</a></li>
    </ul>
  </li>
  <li><a href="#release-notes">Release Notes</a></li>
  <li><a href="#play-store-readiness">Play Store Readiness</a></li>
  <li><a href="#video-demonstration">Video Demonstration</a></li>
  <li><a href="#references">References</a></li>
  <li><a href="#declaration-of-ai-usage">Declaration of AI Usage</a></li>
</ol>

## Project Overview

### Built With
- Kotlin (Both the Android app and the REST API)
- [Ktor](https://ktor.io): served as the framework for the API server and its HTTP client
- [Exposed](https://www.jetbrains.com/help/exposed/home.html): Enables access to the SQL for the API
- [IGDB API](https://api-docs.igdb.com/): provided the game catalogue and average time to complete a game
- [Steam Web API](https://partner.steamgames.com/doc/webapi/ISteamUserStats): provided the achievements and their rarity
- Google Sign-In, which is verified server side with JWT
- Room (SQLite): stores the library and settings on the phone so they work offline
- AndroidX Biometric and the Android Keystore: fingerprint, face or screen lock unlock
- Firebase Cloud Messaging: push notifications, sent from the API with the Firebase Admin SDK (local copy of app/google-services.json present if emulating or using built apk on a samsung device. It is in .gitignore for security.)
- AndroidX app language support: English, Afrikaans and isiZulu
- Azure App Service: runs the API
- Azure Database for PostgreSQL: production database
- GitHub Actions: CI for both the API and the app, and deployment of the API to Azure
<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Core Purpose and Scope

Backlog Battlers provides a rarity and time to complete weighted scoring for its users. Meaning that if someone were to acquire an achievement only 0.8% of players have, they will be awarded more points compared to an achievement 40% of players have. Similarly, if a user finished a game in 70 hours, whereas the average time to beat is 100 hours, that user will be awarded more points than someone who finished the same game in 95 hours. To accomplish this a REST API did most of the heavy lifting. 

Part 2 had delivered Google sign in, settings, the REST API and database, game search, library management, achievement scoring and the monthly leaderboard. The Final POE adds biometric unlock, offline mode with automatic sync, push notifications, Afrikaans and isiZulu, a user profile with levels and badges, and an activity feed. The API is hosted on Azure and the app talks to it from any network.
<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Where to Find Each Requirement

Each requirement from the brief, where to see it in the app, and where it lives in the code. App paths are under `app/src/main/java/com/backlogbattlers/app/`, API paths under `api/src/main/kotlin/com/backlogbattlers/api/`.

| Requirement | In the app | Main code | Section |
|---|---|---|---|
| Runs on a physical phone | Every screen, tested on my (Dylan | st10434135) personal Samsung Galaxy A55 5G against the hosted API | | [Hosting and Deployment](#hosting-and-deployment) |
| Single sign on (Google) | Sign in screen | `ui/login/LoginViewModel.kt`, `data/repository/AuthRepository.kt`, API `routes/AuthRoutes.kt` | [Google sign in](#google-sign-in) |
| Settings | Settings tab | `fragments/SettingsFragment.kt`, `data/repository/SettingsSync.kt`, API `routes/SettingsRoutes.kt` | [Settings](#settings) |
| REST API and database | Every online screen | `api/`, [docs/api.md](docs/api.md) | [Hosting and Deployment](#hosting-and-deployment) |
| Biometric authentication | Lock screen on launch and after a minute away | `util/BiometricSupport.kt`, `util/UnlockKey.kt`, `fragments/BiometricLockFragment.kt` | [Biometric unlock](#biometric-unlock) |
| Offline mode with sync | Banner at the top and markers on library games | `core/ConnectivityMonitor.kt`, `data/repository/SyncCoordinator.kt`, `data/repository/LibrarySync.kt` | [Offline mode and sync](#offline-mode-and-sync) |
| Real time notifications | Push notifications, Settings > Notifications | `notifications/`, API `notifications/` | [Push notifications](#push-notifications) |
| Multi language | Settings > Language | `util/Language.kt`, `res/values-af/`, `res/values-zu/` | [Multi language](#multi-language) |
| User defined feature 1 | Search and Games tabs | `fragments/SearchFragment.kt`, `fragments/GamesFragment.kt`, API `routes/GameRoutes.kt`, `steam/SteamImport.kt` | [UD1](#ud1-game-search-and-library) |
| User defined feature 2 | Game detail screen, Mark as complete | `data/repository/CompletionRepository.kt`, API `scoring/`, `steam/SteamVerification.kt` | [UD2](#ud2-achievement-scoring) |
| User defined feature 3 | Friends tab, Standings | `fragments/FriendsFragment.kt`, `fragments/StandingsFragment.kt`, API `routes/FriendRoutes.kt`, `scoring/Leaderboard.kt` | [UD3](#ud3-friends-and-monthly-leaderboard) |
| User defined feature 4 | Profile tab | `fragments/ProfileFragment.kt`, API `profile/` | [UD4](#ud4-user-profile) |
| User defined feature 5 | Activity tab | `fragments/ActivityFragment.kt`, API `activity/Activity.kt` | [UD5](#ud5-activity-feed) |
| Logging | Logcat and the Azure log stream | `core/ServiceLocator.kt`, API `logging/RequestLogging.kt` | [Logging and Request Tracing](#logging-and-request-tracing) |
| GitHub, tests and CI | | `.github/workflows/` | [Comprehensive Summary](#comprehensive-summary) |
| Release notes | | | [Release Notes](#release-notes) |
| Play Store readiness | Signed APK and AAB, store graphics, screenshots, policy and forms | `play-store/` | [Play Store Readiness](#play-store-readiness) |
| Video | | | [Video Demonstration](#video-demonstration) |
| AI write up | | | [Declaration of AI Usage](#declaration-of-ai-usage) |

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Features

### Core Features

#### Google sign in
**How it works.** The user signs in with their Google account on the phone. The API checks the Google ID token and creates the account the first time, then issues its own access token (one hour) and refresh token. The app renews an expired access token once and retries the request. A refused renewal ends the session and returns to sign in with a message.

<img src="images/app/01-sign-in.png" width="250" alt="Google sign in screen">

#### Settings
**What can be changed.** Theme and home backdrop, language, default platform, the four notification switches, fingerprint unlock and the linked Steam account. Privacy and data has account deletion, which removes everything the user owns on the server.

**Saved to the account.** Settings are saved on the phone and sent to `PUT /users/me/settings`, so they follow the user to another phone. The newest save wins.

<img src="images/app/02-settings.png" width="250" alt="Settings screen">

### Final POE Features

#### Biometric unlock
**What it does.** After the first sign in the app offers to lock itself behind the fingerprint, face or the phone's screen lock. With the lock on, it shows the lock screen on every launch and again when the app has been in the background for a minute.

**Why it is more than a screen.** The unlock uses a key in the Android Keystore that only works straight after the user authenticates. The prompt is handed a cipher from that key, and the app only unlocks when the cipher really works. A fingerprint added or removed since the lock was turned on invalidates the key.

**It fails closed.** A phone that loses its fingerprint or screen lock still shows the lock screen, which explains the problem and offers to sign in again. Turning the lock off asks for a fingerprint first. The pattern follows the Android Biometric guide's "cryptographic solution that depends on authentication".

<img src="images/app/03-biometric-lock.png" width="250" alt="Fingerprint lock screen">

#### Offline mode and sync
**What works offline.** Adding, editing and removing library games, ticking achievements and changing settings. Each change is saved in Room first and marked as waiting to sync.

**When it syncs.** `ConnectivityMonitor` listens to the system's default network and only counts a connection that really reaches the internet. When the phone comes back online, `SyncCoordinator` waits a moment for the connection to settle, then uploads every waiting change and pulls the account's latest data. A failed upload is retried with a growing wait of up to a minute. Every upload is safe to repeat, so a sync cut off halfway loses and duplicates nothing.

**What the user sees.** A strip at the top of the app says "You're offline", then "Syncing your changes", then "All changes synced". Library games that are still waiting show a marker.

**What needs a connection.** Search, game details, marking a game complete, friends, the leaderboard, the profile and the activity feed read live data from the API, so they need a connection.

<img src="images/app/04-offline-banner.png" width="250" alt="Offline banner and waiting to sync marker">

```mermaid
sequenceDiagram
    participant U as User
    participant R as Room on the phone
    participant S as SyncCoordinator
    participant A as API on Azure
    U->>R: Edit library while offline
    R-->>U: Saved, marked waiting to sync
    Note over S: Connection comes back
    S->>A: Upload waiting changes
    A-->>S: Saved to PostgreSQL
    S->>A: Pull latest library and settings
    S-->>U: All changes synced
```

#### Push notifications
**Six types of notifications that have one switch each.** The API sends pushes through Firebase Cloud Messaging for a friend request received, a friend request accepted, a friend completing a game, being overtaken on the leaderboard, achievements unlocked and a new monthly season. Before sending, it checks the matching switch in the receiver's saved settings.

**Opening the right screen.** Tapping a push opens Friends for requests, Activity for a friend's completion, Standings for being overtaken or a new season, and Profile for achievements.

**Setup.** The phone registers its push token with `POST /devices` after sign in and removes it on sign out. Android 13 and newer ask for notification permission first. The app needs `app/google-services.json` from the Firebase project, which is kept out of git, and CI builds without it. The API reads its Firebase service account from the `FIREBASE_SERVICE_ACCOUNT_JSON` Azure setting.

**Season announcements.** A job in the API checks every 10 minutes whether a new month has started. The `season_announcements` table records each month that was announced, so a restart or a second server never sends it twice.

<img src="images/app/05-push-notification.png" width="250" alt="Push notification on the phone">

#### Multi language
**Languages.** English, Afrikaans and isiZulu. Every translatable string in `values/strings.xml` has a match in `values-af` and `values-zu`, including the push notification text.

**Switching.** Settings > Language switches the whole app straight away through AndroidX app language support. Android 13 and newer also list the app under the phone's own language settings, and the choice survives the app being closed.

<img src="images/app/06-language-afrikaans.png" width="250" alt="The app in Afrikaans"> <img src="images/app/07-language-isizulu.png" width="250" alt="The app in isiZulu">

### User Defined Features

#### UD1: Game search and library
**Search and browse.** Search runs on the IGDB catalogue through our API, with recent searches kept per account. The home screen suggests popular games from Steam's most played chart.

**Library.** Games are added to a personal library by platform, and the library syncs with the account. A Steam account can be linked in Settings and its games imported into the library with Steam's hours played. Hours already logged are never lowered.

<img src="images/app/08-search.png" width="250" alt="Game search"> <img src="images/app/09-game-detail.png" width="250" alt="Game detail with achievements">

#### UD2: Achievement scoring
**Rarity points.** Each achievement is worth points by how rare it is on Steam: Common (50% or more) 5, Uncommon (20%) 10, Rare (5%) 20, Very Rare (1%) 40 and Ultra Rare (under 1%) 75.

**Completion points.** Mark as complete awards points that scale with IGDB's average hours for the game, plus a speed bonus for finishing at least 10% faster than average. Only the story can be marked as complete directly (i.e. story completion); the game achievements are still checked by Steam and you cannot 100% complete achievements on a game without verification from Steam. Additionally, ticking off achievements yourself works for personal tracking, but does not reflect on the leaderboard to inflate your stats until the game is checked as 100% complete on Steam.

**Verified on Steam.** Achievement points are only paid for unlocks Steam confirms for the linked account. A confirmed unlock can't be unticked, and a game can only be completed to 100% once every achievement is confirmed.

<img src="images/app/10-mark-complete-options.png" width="250" alt="Mark as complete options"> <img src="images/app/10-mark-complete-confirm.png" width="250" alt="Mark as complete confirmation"> <img src="images/app/10-mark-complete.png" width="250" alt="Mark as complete result with points">

#### UD3: Friends and monthly leaderboard
**Friends.** Players search for each other and send friend requests, which the other player accepts or rejects.

**Leaderboard.** Completions add to the month's points. Standings show the monthly leaderboard, which resets each month, and the season push announces the new month.

<img src="images/app/11-friends.png" width="250" alt="Friends screen"> <img src="images/app/11-friends-requests.png" width="250" alt="Friend requests"> <img src="images/app/11-friends-search.png" width="250" alt="Find friends"> <img src="images/app/11-friends-accepted.png" width="250" alt="Accepted friend"> <img src="images/app/12-standings.png" width="250" alt="Monthly standings">

#### UD4: User profile
**What it shows.** Level and XP, 20 badges with their progress, a showcase of the rarest achievements, and the games played in the last two weeks. The name and picture are saved to the account with `PATCH /users/me`, so friends, the leaderboard and the feed show them too. The level rules and badge rules are explained under [Design Consideration and Architectural Choices](#design-consideration-and-architectural-choices).

<img src="images/app/13-profile.png" width="250" alt="Profile with level, badges and showcase">

#### UD5: Activity feed
**What it shows.** A feed of the user and their friends, newest first. It covers four event types: a game completed, achievements unlocked, a new friend, and passing someone on the leaderboard. A long play session shows as one line, not one line per achievement.

**How it loads.** The API writes each event in the same transaction as the action that caused it, so the feed never shows something that didn't happen. The app loads it a page at a time with pull to refresh.

<img src="images/app/14-activity-feed.png" width="250" alt="Activity feed">

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Architecture

```mermaid
flowchart LR
    Phone["Android app<br/>Kotlin, MVVM, Room"]
    Google["Google sign in"]
    API["Ktor API<br/>Azure Web App"]
    DB[("Azure Database<br/>for PostgreSQL")]
    IGDB["IGDB<br/>games and hours"]
    Steam["Steam Web API<br/>achievements and libraries"]
    FCM["Firebase Cloud<br/>Messaging"]
    GH["GitHub Actions"]

    Phone -- "ID token" --> Google
    Phone -- "HTTPS + JWT" --> API
    API --> DB
    API --> IGDB
    API --> Steam
    API -- "push" --> FCM
    FCM -- "notification" --> Phone
    GH -- "deploy on merge to main" --> API
```

**The app.** Each screen has a Fragment and a ViewModel, which read data through repositories built by `ServiceLocator`. Room keeps the library and settings on the phone. One shared Ktor HTTP client talks to the API.

**The API.** One Ktor project in `api/`, with routes per feature, Exposed for the database and clients for IGDB, Steam and Firebase. It does the scoring, the leaderboard, the feed and the pushes, so the phone stays thin.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Design Consideration and Architectural Choices
**Two separate Gradle builds.** The API (`api/`) is its own Ktor project, to prevent the Android Gradle Plugin and the JVM/Kotlin toolchain from conflicting. Each has its own CI job.

**Authentication.** The app signs in with Google, which the API verifies the resulting ID token and issues its own short-lived JWT access token plus a longer-lived refresh token. Additionally, every endpoint that needs a signed-in user reads that token from the `Authorization: Bearer` header. If invalid, expired, or wrong-type token is rejected without leaking why and a deleted user's still-valid token is also rejected.

**Scoring.** Achievement points come from five rarity tiers (Common through Ultra Rare), which we sourced from Steam's global unlock percentages. Completion points scale with IGDB's average time-to-beat for that game, and a separate speed bonus rewards finishing faster than that average. An entry can be completed twice (story, then 100%) without ever being paid for the same achievement twice.

**Level and XP.** XP is the total points a user has earned across every month: the sum of their completion records. The monthly leaderboard resets, XP never does. Level 1 starts at 0 XP, and each level costs 100 XP more than the one before it, so the XP needed to reach level *n* is:

```
XP to reach level n = 50 × n × (n − 1)
```

That is 100 XP for level 2, 300 for level 3, 600 for level 4 and 1,000 for level 5. The API works it out every time the profile is read (`GET /users/me/profile`) and sends the level, the XP into it and the XP the whole level takes (`api/.../profile/Levels.kt`); the app only draws the bar.

**Badges.** There are 20, and each is worked out from stored data every time the profile is read, so nothing is saved per badge and a rule can change without a migration (`api/.../profile/Badges.kt`). Every badge reports its progress and target, so the profile can show "3 / 10" on one still to earn. The rules read what the database holds, which means a few are close approximations:

- **Dust Off:** nothing records when a game was last played, so it counts 90 days between two unlocks in the same game.
- **Shelf Cleaner:** a game completed more than six calendar months after it was added to the library.
- **Genre Tourist:** IGDB's genres are stored in `game_genres` when a game is saved. A game saved before that table existed gets its genres the next time its detail screen is opened.
- **Top of the Table, Photo Finish and Nemesis** compare the user with their friends over finished months only. **Comeback** uses the rank on the global leaderboard.

**Data.** Exposed tables mirror the domain: users, games, achievements, library entries, unlocked and Steam verified achievements, completion records, monthly leaderboard entries, friendships, settings, push devices and activity events. Games and achievements are fetched from IGDB and Steam once and cached in our database, so repeat requests don't call those services again. New features add new tables rather than columns, because Exposed creates missing tables on start but never changes an existing one.

**Storage choices.** All server data is relational and lives in PostgreSQL. The activity feed is one indexed query over the user and their friends, and its rows are removed with the account, which a relational table handles well. The app has no blob storage because profile pictures ship inside the app and users upload no files.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Logging and Request Tracing
**What the API logs.** We log one line per request (status, method, path, time, user UUID), plus one line whenever data changes (sign in, completion and points, friend requests, settings, account deletion). `/health` is skipped.

**Request IDs.** Every response carries an `X-Request-Id`, and every API log line shows it. The app sends an ID with every call and prints it in its Logcat line, so one tap can be followed from the phone to the API log and then to the database row.

**What we never log.** Tokens, the Authorization header, emails, names, query strings and request bodies, and a unit test checks this.

**Log levels and log destinations.** `LOG_LEVEL` sets the level (default INFO). Output goes to the console, where container hosts capture it. Requests slower than 1000 ms also get a WARN line.

**Android app logging.** The Android app logs throughout its ViewModels and repositories using Android's `Log` class and component `TAG` constants (for instance in `AuthRepository`, `FriendsViewModel`, and `SettingsViewModel`). In `ServiceLocator.kt`, an `HttpSend` interceptor on the shared Ktor HTTP client logs every outbound call under `HTTP_TAG` ("Http"), recording the method, path, response status, and duration in milliseconds, as well as cancellations and failures.

```
14:02:11.532 INFO  [3f9a1c7e2b40] RequestLogging - 201 POST /users/me/library in 84ms user=7f3c2a1e-5b9d-4c11-9a0e-2d6f8b7e1c34
14:02:11.530 INFO  [3f9a1c7e2b40] LibraryRoutes - User 7f3c2a1e-… added game 113112 on PC as library entry 0c5e…
D/Http: [3f9a1c7e2b40] POST /users/me/library -> 201 in 97ms
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Hosting and Deployment
**Where it runs.** The API runs on an Azure Web App (Linux, Java 21, Basic B1, Always On, doesn't deactivate) in UAE North, at [https://backlog-battlers-api-7314-gyancxcndrh0akgn.uaenorth-01.azurewebsites.net](https://backlog-battlers-api-7314-gyancxcndrh0akgn.uaenorth-01.azurewebsites.net).

![Azure Web App overview showing Running, URL and UAE North](images/hosting/01-azure-webapp-overview.png)

**The database.** Azure Database for PostgreSQL flexible server (Burstable B1ms) in the same region, over TLS. The API creates its tables on first start. With `APP_ENV=production` it refuses to start without a database.

![Azure PostgreSQL tables with live rows](images/hosting/02-azure-postgres-tables.png)

**How a change reaches the server.** On every push to `main`, `api-ci.yml` runs the tests. If they pass, its `deploy` job builds `app.jar` stamped with the commit hash, uploads it with `azure/webapps-deploy`, and checks `/health` until that commit reports `database: ok`. Pull requests and feature branches never deploy.

![GitHub Actions deploy job with the health check passing](images/hosting/03-github-actions-deploy.png)

**Configuration and secrets.** Every setting is an Azure App Setting (encrypted), read by the same `Config` class as locally. Setting names: `APP_ENV`, `LOG_LEVEL`, `PORT`, `WEBSITES_PORT`, `JWT_ISSUER`, `JWT_SECRET`, `GOOGLE_OAUTH_CLIENT_ID`, `IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET`, `STEAM_API_KEY`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FIREBASE_SERVICE_ACCOUNT_JSON`. GitHub holds only the publish profile as a secret (`AZURE_WEBAPP_PUBLISH_PROFILE`), with `AZURE_WEBAPP_NAME` and `AZURE_WEBAPP_URL` stored as repository variables.

**Checking it is live.** `/health` returns status, database and version. Example response:

```json
{
  "status": "ok",
  "database": "ok",
  "version": "xxxxx"
}
```

![/health response from the hosted API](images/hosting/04-health-endpoint.png)
![Azure Log stream showing request-ID lines](images/hosting/05-log-stream-request-ids.png)

**Running against a laptop.** Developers can point a local build at a laptop by setting `apiBaseUrl=http://<laptop IPv4>:8080` in `local.properties`. Details are documented in [docs/api.md](docs/api.md).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Comprehensive Summary

### Implementation of Version Control

Branches follow `feat/<initials>/<topic>` and are merged into `main` through reviewed pull requests. The history has over 360 commits from all three members. The repository moved from https://github.com/AndreFourie12/Backlog-Battlers to this one during the Final POE because GitHub Actions was locked on the original account, and the full history came with it.

**<img width="1283" height="569" alt="image" src="https://github.com/user-attachments/assets/217b2679-00fd-4e73-94d9-eccd71695334" />**

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### GitHub Actions

Two workflows run on every push. `android-ci.yml` runs the app's unit tests and builds the debug app. `api-ci.yml` builds the API and runs its unit and integration tests, and on `main` it also deploys the API to Azure and checks `/health` (see [Hosting and Deployment](#hosting-and-deployment)). The app has 278 tests and the API has 308.

**<img width="1352" height="571" alt="image" src="https://github.com/user-attachments/assets/11478388-1659-45cf-9ecd-93622960c4bb" />**


<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Release Notes

**Version 1.0 (Final POE).** Changes since the Part 2 prototype:

**New features**
- Biometric unlock with a Keystore key, relocking after a minute in the background
- Offline mode that syncs on its own when the connection returns, with an offline banner and waiting to sync markers
- Push notifications for six events, each behind its own switch in Settings
- Afrikaans and isiZulu
- UD4 user profile with levels, XP, 20 badges and a showcase of the rarest achievements
- UD5 activity feed for the user and their friends
- Mark as complete, which awards completion points and the speed bonus
- Steam account linking, library import and Steam verified achievements

**Fixes from the Part 2 feedback**
- The API is hosted on Azure with PostgreSQL, and the app uses it by default from any network
- The library, completions and scoring now go through the API, and each account only sees its own library
- Expired sessions renew themselves, and a session that can't be renewed returns to sign in with a message
- The display name, picture and settings are saved to the account, so friends and other phones see them
- Activity and Profile show real data
- Achievement points need Steam to confirm the unlock

**Under the hood**
- Request IDs shared between Logcat and the API logs
- Deployment to Azure on every merge to `main`, checked through `/health`
- A signed release build and everything a Play Store submission needs, in `play-store/`

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Play Store Readiness

We prepared Backlog Battlers for the Google Play Store but did not publish it as it was not required. Everything Play Console asks for is in [play-store/](play-store/). [MockReviewProcess.md](play-store/MockReviewProcess.md) goes through each Google Play requirement with our response and a link to the evidence from our app.

**Signed release build.** [backlog-battlers-1.0.apk](play-store/release/backlog-battlers-1.0.apk) is the signed production APK, and [backlog-battlers-1.0.aab](play-store/release/backlog-battlers-1.0.aab) is the bundle we would upload to Play. Both are signed with our own upload key instead of the debug key, which [signing-certificate.txt](play-store/release/signing-certificate.txt) shows. The keystore stays outside the repository. I (Dylan | ST10434135) installed the release APK on my Samsung Galaxy A55 5G and tested it against the hosted API.

**Store material.** The [screenshots](play-store/screenshots/) are 1080x1920 and also have captions, and the [graphics](play-store/graphics/) folder has the 512px icon and the 1024x500 feature graphic. The listing text is in [store-listing.md](play-store/forms/store-listing.md), and we checked against Google Play character limits, and the [privacy policy](play-store/privacy-policy.md) is also public in this repository.

<p>
<img src="play-store/screenshots/phone-01-achievement-points.png" width="120">
<img src="play-store/screenshots/phone-02-mark-complete.png" width="120">
<img src="play-store/screenshots/phone-03-game-search.png" width="120">
<img src="play-store/screenshots/phone-04-friends.png" width="120">
<img src="play-store/screenshots/phone-05-notifications.png" width="120">
<img src="play-store/screenshots/phone-06-offline.png" width="120">
<img src="play-store/screenshots/phone-07-languages.png" width="120">
<img src="play-store/screenshots/phone-08-sign-in.png" width="120">
</p>

**Play Console forms.** Data safety, content rating, target audience, app access and the ads declaration only exist inside a developer account, so our answers are written out in [play-store/forms/](play-store/forms/). Each data safety answer names the database table for it's form data.

**Account deletion.** Play requires it in the app and on the web. Users delete their account under Settings: Delete Account, which removes everything linked to it through `DELETE /users/me`. The web route is the [Delete your account](play-store/privacy-policy.md#delete-your-account) section of the privacy policy.

**What was not included, as it could only be done if we were actually submitting the app** Paying a 25 dollar registration fee, identity verification, the 12 tester closed test and Googles review paid developer account. MockReviewProcess.md describes how we would complete each one and is the main Play Store application process document/ master document for playstore readyness with links to all other docs/images/forms.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Video Demonstration

Video Demonstration: https://youtu.be/W1bKa1cO-hw

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## References 

Andrews, A., Irigoyen, M., Wiscombe, C., Richins, M., Simran, GreenTurtwig, Anders, J., Noble, P., Coyle, J. and Stoeckel, Q. (2026). Material design icons - icon library - pictogrammers. [online] pictogrammers.com. Available at: https://pictogrammers.com/library/mdi/ [Accessed 19 Sept. 2026].

---

Gleap Team (2025). 7 Best practices to help you build better mobile apps. [online] Gleap. Available at: https://www.gleap.ai/blog/7-best-practices-to-help-you-build-better-mobile-apps [Accessed 20 Sept. 2026].

---

Gunawan, H. (2023). User friends system & database design. [online] Coderbased.com. Available at: https://www.coderbased.com/p/user-friends-system-and-database [Accessed 19 Sept. 2026].

---

naveen.ctebs (2026). The UI.UX playbook. Tips tricks for exceptional design. [online] Scribd. Available at: https://www.scribd.com/document/805328183/The-UI-UX-Playbook-Tips-Tricks-for-Exceptional-Design [Accessed 20 Sept. 2026].

---

Nishant (2023). UX case study - Enhancing steam's navigation to increase user retention. [online] Medium. Available at: https://medium.com/design-bootcamp/enhancing-steams-navigation-to-increase-user-retention-product-design-case-study-72a165d9572f [Accessed 19 Sept. 2026].

---

OpenAI (2025). ChatGPT. [online] ChatGPT. Available at: https://chatgpt.com/ [Accessed 19 Sept. 2026].

---

Saini, R. (2025). Implementing Google sign-in in your android app (complete 2025 guide - Java + kotlin). [online] Medium. Available at: https://medium.com/@rohitsaini3342/implementing-google-sign-in-in-your-android-app-complete-2025-guide-java-kotlin-7f314689c137 [Accessed 20 Sept. 2026].

---

SHOUT (2025). What makes a successful mobile app? | Shout Digital. [online] Shoutdigital.com. Available at: https://www.shoutdigital.com/insights/what-makes-a-successful-mobile-app/ [Accessed 19 Sept. 2026].

---

Ktor, 2026. Ktor Documentation. [Online] Available at: https://ktor.io/docs/ [Accessed 22 September 2026].

---

JetBrains, 2026. Exposed - Kotlin SQL Framework. [Online] Available at: https://www.jetbrains.com/help/exposed/home.html [Accessed 22 September 2026].

---

IGDB, 2026. IGDB API Documentation. [Online] Available at: https://api-docs.igdb.com/ [Accessed 22 September 2026].

---

Steam, 2026. Steam Web API - ISteamUserStats. [Online] Available at: https://partner.steamgames.com/doc/webapi/ISteamUserStats [Accessed 22 September 2026].

---

Twitch, 2026. Client Credentials Grant Flow. [Online] Available at: https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#client-credentials-grant-flow [Accessed 22 September 2026].

---

HikariCP, 2026. HikariCP - A solid, high-performance, JDBC connection pool. [Online] Available at: https://github.com/brettwooldridge/HikariCP [Accessed 22 September 2026].

---

GitHub, 2026. GitHub Actions Documentation. [Online] Available at: https://docs.github.com/en/actions [Accessed 22 September 2026].

---

Gradle, 2026. Gradle User Manual. [Online] Available at: https://docs.gradle.org/current/userguide/userguide.html [Accessed 22 September 2026].

---

Ktor, 2026. Call logging. [Online] Available at: https://ktor.io/docs/server-call-logging.html [Accessed 4 November 2026].

---

Ktor, 2026. Call ID. [Online] Available at: https://ktor.io/docs/server-call-id.html [Accessed 4 November 2026].

---

QOS.ch, 2026. Logback configuration. [Online] Available at: https://logback.qos.ch/manual/configuration.html [Accessed 4 November 2026].

## Declaration of AI Usage:

Throughout this project, members of our team utilised ChatGPT 5.0 LLM to assist with planning, brainstorming, architecture structuring, feature implementation, debugging and code review. All work involving AI usage has, to the best of our abilities, been credited where due or reworked to be made our own. 
Please find the links to our conversations below.

*Links to chats and detail:*

Friends functionality assistance: <br>
Assistance describing architecture/ design implementations as well as debugging: friendsrepository, FriendsFragment.kt, FriendsApi.kt, item_friend_request.xml <br> 
https://chatgpt.com/share/6ab25643-70c0-83ea-89db-eef5f5e47bd7

Google SSO assitance:<br>
SSO implementation planning, assistance, snippet design, API routing and debugging within: GoogleIdTokenVerifier.kt, JwtService.kt, AuthRoutes.kt <br>
https://chatgpt.com/share/6ab25b54-4b38-83ea-a847-3833c2b818a5

Ktor Setup and miscellaneous: <br>
REST API structure, Android Studio, GitHub, README <br>
https://chatgpt.com/share/6ab2832b-ffe8-83ea-acc4-661d38f2964a

Assistance for English to Afrikaans and Zulu language translation and feature implementation general assistance and large string conversions:
utilised within:
- strings.xml, in values, values-af and values-zu: the text itself in each language.
- locales_config.xml: lists en, af and zu for Android 13 and up.
- AndroidManifest.xml: android:localeConfig, and the AppLocalesMetadataHolderService that keeps the choice on older phones.
- Language.kt: applyLanguage() and currentLanguage(), which switch and read the app language.
- Models.kt: the AppLanguage enum (en, af, zu).
- SettingsFragment.kt and SettingsViewModel.kt: the language row and picker in Settings.
- SettingsRepository.kt and SettingsSync.kt: save the choice and sync it to the account.<br>
https://chatgpt.com/share/6aee3d6c-d178-83ea-87ae-5ed49e7f28ce

