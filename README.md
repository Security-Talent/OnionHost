# OnionHost

[![Download latest version](https://img.shields.io/badge/Download-Latest%20Version-075E54?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Security-Talent/OnionHost/releases/latest)

OnionHost is an Android app that imports files into app-private storage and can publish them through a Tor v3 Onion Service. It also provides host-managed personal chat links and group rooms. The chat is hosted by one user's phone; it is not a peer-to-peer or end-to-end encrypted messenger.

> **Current source version:** 1.1.2 (version code 6) · **Minimum Android:** 8.0 / API 26

## Proof of concept (POC)

The screenshot below shows the complete flow: OnionHost is hosting on the phone, the generated Onion address and QR code are visible, and Tor Browser is opening the hosted directory.

![OnionHost POC: Android host and Tor Browser visitor](POC/POC.png)

## Build and install a development build

Build the debug APK from source using the steps below, then install it only for development/testing. To host content, keep the device powered and online and allow OnionHost to run its foreground service. Wait for the UI to report that the Onion Service is live before sharing its address.

## Hosting a site or file

1. In **Home**, select one of the import options:
   - **Choose Folder** for a complete static site.
   - **Choose ZIP File** to import and safely extract a site archive.
   - **Choose Single File** for an HTML file, document, APK, ISO, image, or media file.
2. OnionHost copies the content into private app storage.
3. Start hosting and wait for Tor bootstrap and hidden-service publication to finish.
4. Copy the Onion address or scan/download its QR code.
5. Stop or restart hosting from the app or its foreground notification.

The HTTP server supports directory listings when no `index.html` exists, safe MIME types, download headers for downloadable files, and byte-range requests for supported media. Optional basic authentication can be configured per website.

## QR code sharing

The Home screen shows a QR code for the active website's Onion address.

- Tap the QR code, or press **Download QR Code**, to choose a location and save it as a PNG.
- The copy icon next to the Onion address copies only the website address.
- Chat links are intentionally managed from the Chat screen, because every personal chat and room has its own separate link.

## Anonymous chat

Chat is served by OnionHost itself. No third-party chat server, phone number, or visitor account is required. A visitor needs Tor Browser and the specific link you share.

### Personal chat tab

Use **Personal** for a one-to-one room hosted by this device.

1. Start hosting from **Home** and wait for Tor publication.
2. Open **Chats → Personal → New chat**.
3. Select **Share** beside the saved chat and send the invite to the intended person.
4. Open the saved chat to view messages and approve incoming join requests.

Personal conversations stay separate from group rooms. While the Personal chat tab is open, room messages cannot be viewed or sent.

### Rooms tab

Use **Rooms** for group conversations.

1. Start hosting from **Home** and wait for Tor publication.
2. Open **Chats → Rooms**, enter a room name and tap the add/create action.
3. Tap a room to open it, then select **Share** to send its invite.
4. Approve visitors from the join-request control before they can send or read chat messages.

Each room has a different URL in this form:

```text
http://your-onion-address.onion/chat/room-room1
```

Room messages stay in their own room. While the Rooms tab is open, personal messages cannot be viewed or sent. A guest can join in OnionHost using **Join** and pasting the invite; a browser guest needs a Tor-capable browser such as Tor Browser.

### Sending, attachments, activity, and history

- Browser users receive an automatically generated anonymous name.
- The green dot shows active users who have recently connected to the currently selected conversation.
- Browser chat displays a loading spinner while the chat is loading or reconnecting.
- Text messages support up to 1,000 characters.
- Supported attachments are PNG, JPEG, GIF, WebP, MP4, WebM, MP3, OGG, and WAV, up to 5 MB.
- The host can delete any message; a browser visitor can delete messages created in that browser.
- The newest messages appear at the bottom. Scroll upward to read earlier messages.
- Saved personal-chat and room lists have a fixed height, so they do not shrink the message area when many chats exist.

### Persistence and limits

- Messages and room names are saved in the app's private storage, not inside hosted website files.
- A primary archive and local backup archive are written on every chat change. If an interrupted write damages the primary archive, the backup is restored on the next launch.
- Chats survive hosting restarts, app restarts, and normal device restarts. Clearing Android app data or uninstalling the app removes locally stored chats.
- Each room retains the latest 200 messages to keep memory use bounded.
- A private link is a locator/access secret, not end-to-end encryption. The host stores message text and attachment data in readable JSON in app-private storage and can read them. Anyone with the link can request access; host approval is the gate, and the current client ID is not a cryptographic identity.
- Tor protects the network route to an Onion Service, but it does not hide content from the hosting device or guarantee anonymity. Host availability depends on the phone, Tor, network, Android background limits, and battery settings.
- Username-based discovery is not available in the Chats UI. The repository has signed-record/client groundwork but ships no configured discovery service or complete username-to-chat workflow.

## Other app functions

| Screen | What it does |
| --- | --- |
| **Home** | Starts/stops/restarts hosting, displays the Onion address, copies the site address, and saves the address QR code. |
| **Chats** | Manages personal rooms and group rooms, invite sharing/joining, host approval, messages, attachments, activity indicators, and deletion. |
| **Websites** | Shows imported websites and lets you choose the active hosted content. |
| **Analytics** | Shows local visit/download request counts, paths, and daily statistics. The app does not persist IP or user-agent fields in visitor-stat rows; requested paths and timestamps are stored locally and may themselves reveal sensitive resource names. |
| **Logs** | Shows application, HTTP, and Tor status messages to help diagnose startup or publication problems. |
| **Settings** | Stores app preferences, including hosting-related settings. |
| **About** | Shows application information. |

## Security and privacy

- Content is imported into app-private storage before serving.
- Path-traversal protection prevents a request from escaping the hosted content root.
- MIME validation restricts served and chat-uploaded content types.
- Chat pages and APIs use no-store cache headers and a restrictive content-security policy.
- The embedded server rate-limits requests per observed address.
- Tor creates and owns the v3 hidden-service key material; OnionHost does not generate a fake Onion address.
- An Onion address only becomes shareable after Tor reports that its service descriptor has been published.
- Chat content is not end-to-end encrypted and is readable by the host. Do not use it for sensitive communications until an audited E2EE protocol, secure key verification, and encrypted storage are implemented and tested.
- Username registration/discovery is not a deployed decentralized network. Do not infer that a signed username record proves control of an Onion Service.
- The Android manifest declares `INTERNET`, network-state, foreground-service, notification, boot, and wake-lock permissions. Review the current manifest and release lint output before distribution.

## Build from source

Requirements: Android SDK API 34, JDK 17, and Android 8.0+ for running the app.

```powershell
$env:ANDROID_HOME = "C:\Users\<you>\AppData\Local\Android\Sdk"
.\gradlew.bat :app:assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`. The
local release output is `app/build/outputs/apk/release/app-release-unsigned.apk`
unless all required persistent signing properties are configured.

Run the local checks with:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The source snapshot was last checked on 2026-10-15: 36 unit tests passed,
lint reported 0 errors/55 warnings, and the unsigned release variant packaged.
No device/emulator was available for runtime Tor or UI checks.

### Publish the current latest APK

Run this task after building and testing a debug APK:

```powershell
.\gradlew.bat :app:publishLatestApk
```

It copies the debug APK to `releases/OnionHost-latest.apk` for local/manual
distribution. This is a debug build, not a production release; it does not
sign the app with a production key or validate readiness. Do not publish it as
a production update.

### Production-signed APK

Production updates must always use the same private signing key. Do not commit that key. Create an untracked `keystore.properties` file in the repository root:

```properties
storeFile=C:\secure\onionhost-release.jks
storePassword=your-store-password
keyAlias=onionhost
keyPassword=your-key-password
```

Then run:

```powershell
.\gradlew.bat :app:publishProductionApk
```

When all signing properties are valid, this task builds and copies the signed
release APK to `releases/OnionHost-latest.apk`. Without them, the task skips;
`assembleRelease` can still create an unsigned APK. Keep the keystore and
properties file out of Git. A production update must use the same persistent
signing key as previous releases.

## Architecture

```mermaid
graph TD
  UI[Jetpack Compose UI] --> VM[ViewModels]
  VM --> REPO[Repositories]
  REPO --> DB[(Room database)]
  REPO --> FS[Private storage]
  SERVICE[Foreground hosting service] --> HTTP[Embedded HTTP server]
  SERVICE --> TOR[Tor manager]
  HTTP --> CHAT[Chat API and chat archive]
  TOR --> HTTP
```

| Area | Responsibility |
| --- | --- |
| `ui/` | Compose screens, navigation, hosting controls, chat, and QR download UI. |
| `hosting/` | Foreground service and boot receiver. |
| `http/` | Static-file routes, chat pages/API, response headers, and media validation. |
| `tor/` | Tor process, hidden-service configuration, publication status, and Onion hostname handling. |
| `storage/` and `security/` | Content import, ZIP extraction, MIME checks, path safety, and rate limiting. |
| `database/` and `analytics/` | Local website configuration, logs, and privacy-preserving statistics. |

## License

OnionHost is **not** released under an unrestricted MIT or Apache license. It
uses the repository's [OnionHost Source-Available License](LICENSE):

- Do not copy, publish, redistribute, modify, fork as a separate product, or
  use this project commercially without prior written permission from
  Security Talent.
- Contributions are welcome through issues and pull requests. Contributors
  retain copyright in their original work while granting the project the
  rights needed to use the contribution in official OnionHost releases.
- Unauthorized use may lead to copyright takedown requests or other remedies
  available under applicable law.

This is a custom license, not legal advice. If you need strong enforcement or
plan to distribute the project, have the license reviewed by a lawyer in the
relevant jurisdiction.

Created by [Security Talent](https://securitytalent.net).
