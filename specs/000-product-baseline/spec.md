# 000 — OnionHost project map

**Status:** Source reviewed; current-state reference

## Product summary

OnionHost is an Android application that imports content into private app
storage, serves it over an embedded HTTP server, and publishes that server as a
Tor v3 Onion Service. The app also hosts anonymous one-to-one and group chats.

## Platform and distribution

- Android `minSdk` 26, `targetSdk` 34, compile SDK 34; Java/Kotlin target 17.
- Current app version: `1.1.2` (`versionCode` 6).
- Jetpack Compose UI with Navigation Compose, Hilt dependency injection, Room
  persistence, Preferences DataStore, and Kotlin coroutines/flows.
- Bundled native Tor binaries target `armeabi-v7a`, `arm64-v8a`, `x86`, and
  `x86_64`.
- Debug builds use an application ID suffix `.debug`; release builds shrink
  code/resources and use production signing only when `keystore.properties`
  provides all four required signing properties.
- Gradle tasks publish debug or production output to
  `releases/OnionHost-latest.apk`.

## Architecture map

- `ui/` provides screens and view models for Home, Chat, Websites, Analytics,
  Logs, Settings, and About.
- `hosting/` owns the foreground service and boot/package-replaced receiver.
- `http/` serves static files and the browser chat page/API.
- `tor/` installs/launches Tor, manages its hidden service and status, and
  validates v3 Onion addresses.
- `storage/` imports selected content into app-private directories.
- `database/`, `repository/`, and `analytics/` persist website records,
  request statistics, and logs.
- `security/` contains path resolution, MIME selection, and request limiting.

## Persistent data

- Room database `onion_host_db`: website records, visitor statistics, and
  system logs.
- Preferences DataStore `onionhost_settings`: boot auto-start, default port,
  and request-limit preference.
- App files: imported website directories, Tor state, and
  `anonymous_chat.json` with a local backup.

## User-visible surfaces

- Home: import content, hosting status/actions, Onion address/QR, and metrics.
- Chat: private chats and rooms, messages, attachments, activity, and message
  deletion, with separate Share and Join invite actions. Username search is not
  exposed in the current Chat UI.
- Websites: rename, duplicate, activate/deactivate, and delete website records.
- Analytics: visits, downloads, popular paths, and daily counts.
- Logs: recent diagnostics and clear action.
- Settings: boot auto-start, default port, and request-limit preferences.
- About: application information and external links.

## Evidence and limits

This track and the domain specs below were derived by reading `README.md`,
Android manifest/build configuration, and the Kotlin sources under
`app/src/main/java/com/onionhost/app/`. They describe observed source behavior,
not a claim that every behavior has been exercised on a device. On 2026-10-15,
36 JVM unit tests passed, `lintDebug` completed with 0 errors and 55 warnings,
and `assembleRelease` produced an unsigned APK. No Android device or emulator
was attached, so live Tor and UI behavior remain unverified. See spec 005 for
the verification details.

The app is not production-ready as an anonymous messenger: the chat host stores
readable message JSON, no E2EE protocol is implemented, username discovery is
not deployed, and the release artifact is unsigned. Tor transport and an Onion
address do not change those application-layer limitations.

No feature change was requested for this baseline. Future changes belong in a
separate numbered change track with goals, scope, acceptance criteria, plan,
tasks, and verification results.
