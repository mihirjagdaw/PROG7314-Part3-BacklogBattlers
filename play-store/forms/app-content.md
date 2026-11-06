# App Content Declarations

Our answers to the rest of Play Console's **Policy > App content** page. These forms only exist inside Play Console, so we wrote them out here. Data safety has its own file, [data-safety.md](data-safety.md).

## Privacy policy

https://github.com/mihirjagdaw/PROG7314-Part3-BacklogBattlers/blob/main/play-store/privacy-policy.md

The app shows the same summary under Settings > About > Privacy Policy.

## App access

**All or some functionality is restricted.** Everything past the first screen needs a sign in.

Instructions for the review team:

```
Tap "Continue with Google" and sign in with any Google account. The first sign in creates the account, so no test account or password is needed.

Linking Steam is optional (Settings > Connected Accounts). It works with any Steam profile whose game details are set to Public.

After the first sign in the app offers fingerprint unlock. You can skip it and change it later in Settings > Privacy & Data.
```

## Ads

**No, the app does not contain ads.** There are no ad SDKs in `app/build.gradle.kts`.

## Content rating

The answers we would give in the IARC questionnaire. IARC issues the rating when the questionnaire is submitted.

| Question | Answer |
|---|---|
| Category | Social networking, forums, blogs and UGC sharing. Friends and the monthly leaderboard are the core of the app |
| Violence, sexuality, language, controlled substances or gambling made by the app itself | No |
| Can users interact or exchange content? | Yes. Friends see each other's profile and game activity. There is no chat or messaging |
| Does the app share the user's current physical location with other users? | No |
| Does the app allow users to purchase digital goods? | No |
| Does the app contain any simulated gambling? | No |
| Does the app let users browse the open internet? | No |
| Is the app a web browser or search engine? | No |

The app shows game covers and achievement icons from IGDB and Steam. Some of these games are rated for older players, so their artwork can appear in search results.

## Target audience and content

| Question | Answer |
|---|---|
| Target age groups | 13 and over. We would tick every age group from 13 upward |
| Could the app unintentionally appeal to children? | No. It is a tracker for people who already play games on Steam and other platforms. The listing and graphics are not aimed at children |
| Families policy | Not applicable. The app is not aimed at children under 13 |

## Other declarations

| Declaration | Answer |
|---|---|
| News app | No |
| Government app | No |
| Financial features | None |
| Health app | No |
| COVID contact tracing or status | No |
| Account deletion | In the app under Settings > Delete Account. On the web through the [Delete your account](../privacy-policy.md#delete-your-account) section of the privacy policy |
