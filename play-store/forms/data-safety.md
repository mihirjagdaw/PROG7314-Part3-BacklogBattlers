# Data Safety Form

Our answers to Play Console's **Policy > App content > Data safety** form. The form only exists inside Play Console, so we wrote it out here. Each answer names where the data lives in the code so it can be checked. The [privacy policy](../privacy-policy.md) says the same thing in plain language.

## Overview questions

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes. The release build only talks to the API over HTTPS. Plain HTTP is only allowed in debug builds (`app/src/debug/AndroidManifest.xml`). Push tokens go to Firebase over TLS |
| Which of the following methods of account creation does your app support? | OAuth (Sign in with Google) |
| Do you provide a way for users to request that their data is deleted? | Yes. In the app under Settings > Delete Account. On the web through the [Delete your account](../privacy-policy.md#delete-your-account) section of the privacy policy |
| Delete account URL | https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers/blob/main/play-store/privacy-policy.md#delete-your-account |

## Data collected

None of this data is shared. Play does not count service providers as sharing. Ours are Microsoft Azure, which hosts the API and database, and Firebase Cloud Messaging, which delivers notifications. A Steam ID only goes to Steam when the user links Steam themselves, which Play also excludes.

| Data type | Collected | Optional | Purposes | What it is | Where it lives |
|---|---|---|---|---|---|
| Personal info: Name | Yes | Required | App functionality, Account management | The display name from Google, which the user can change | `users.display_name` |
| Personal info: Email address | Yes | Required | Account management | The Google account's email. Other users never see it | `users.email` |
| Personal info: User IDs | Yes | Required (Steam ID optional) | App functionality, Account management | The Google account ID and our own account ID. Also the Steam ID when the user links Steam | `users.google_subject_id`, `user_steam_accounts.steam_id` |
| Personal info: Other info | Yes | Required | App functionality | The Google profile picture link and the app picture the user picked | `users.avatar_url`, `user_pictures` |
| App activity: Other actions | Yes | Required | App functionality | The game library with hours played, unlocked achievements, completions, points, friends, activity feed events, settings and profile visibility | `library_entries`, `unlocked_achievements`, `completion_records`, `monthly_leaderboard_entries`, `friendships`, `friend_requests`, `activity_events`, `user_settings`, `profile_visibility` |
| App info and performance: Diagnostics | Yes | Required | App functionality | Server request logs with the method, path, status, time taken and internal account ID. They never contain tokens, emails, names, query strings or request bodies | Azure App Service log stream |
| Device or other IDs | Yes | Required | App functionality | The phone's Firebase Cloud Messaging token, so the API can send push notifications | `devices.token` |

## Not collected

Location, financial info, health and fitness, messages, photos and videos, audio, files and documents, calendar, contacts, web browsing history, installed apps and the advertising ID.

Fingerprint data never reaches the app. Android's BiometricPrompt checks it on the phone and only tells the app whether the check passed.

Game searches go through the API to IGDB. The search text is not stored, and the request logs leave out query strings. Play counts this as processed in memory only.

## Deletion

Settings > Delete Account calls `DELETE /users/me` (`api/.../routes/AccountRoutes.kt`). Every user table points at `users` with `ON DELETE CASCADE`, so one delete removes all of the user's data. That covers the library, achievements, completions, points, friends, settings, push tokens, Steam link and feed events.

Signing out removes the session and the saved profile from the phone. Uninstalling removes everything the app kept on the phone.
