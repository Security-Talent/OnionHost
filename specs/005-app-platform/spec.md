# 005 — App shell and distribution

**Status:** Source reviewed; current-state reference

Current source version: `1.1.2` (`versionCode` 6), Android `minSdk` 26 and
`targetSdk` 34.

## Requirements

- **FR-001:** The app launches into a four-item bottom-navigation shell:
  Home, Chats, Sites, and More. Analytics, Logs, Settings, and About are
  reachable through More. Home is the start destination.
- **FR-002:** Navigation uses single-top behavior and restores destination
  state when switching among the bottom navigation items.
- **FR-003:** Android declares internet, network-state, foreground-service,
  special-use foreground-service, notification, boot-completed, and wake-lock
  permissions. The hosting service is non-exported; the boot receiver handles
  boot completion and package replacement.
- **FR-004:** The host runs as a special-use foreground service with a
  user-visible subtype describing a local hidden web service over Tor.
- **FR-005:** The debug publishing task builds the debug APK and copies it to
  `releases/OnionHost-latest.apk`.
- **FR-006:** The production publishing task runs only when all keystore
  properties are present, builds the release APK, and copies it to the same
  destination.
- **FR-007:** Production release builds enable code minification and resource
  shrinking; debug builds are debuggable and carry the `.debug` application ID
  suffix.

## Acceptance criteria for current behavior

- **AC-001:** A fresh launch opens Home; Home, Chats, Sites, and More are on
  the bottom bar, and More opens Analytics, Logs, Settings, and About.
- **AC-002:** Release APK publishing is skipped when required signing
  properties are absent and uses the configured signing key when present.
- **AC-003:** The foreground service declaration is non-exported and carries
  the declared special-use subtype.

## Verification record (2026-10-15)

- `:app:testDebugUnitTest`: 36 tests passed, with 0 failures/errors.
- `:app:lintDebug`: completed with 0 errors and 55 warnings. Warnings include
  target SDK 34, dependency/tooling notices, deprecated UI APIs, and dynamic
  Tor library loading.
- `:app:assembleRelease`: completed, but produced
  `app/build/outputs/apk/release/app-release-unsigned.apk`. `apksigner verify`
  rejected it because it has no signature. No `keystore.properties` release
  signing configuration is present.
- `:app:assembleDebug` succeeded in the same workspace.
- `adb devices` reported no attached devices. No install, startup, foreground
  service, Tor bootstrap, or layout test was possible on hardware/emulator.

Source reviewed in `NavGraph`, `MoreScreen`, `AndroidManifest.xml`, and
`app/build.gradle.kts`. A successful build is not a release-signing or
production-readiness check. The release publish task is gated on persistent
signing properties.
