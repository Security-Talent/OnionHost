# 002 — Anonymous chat

**Status:** Source reviewed; current-state reference

## Scope

Host-side chat UI/state, browser chat pages and API, room links, message
ownership, attachments, activity, and persistence.

## Requirements

- **FR-001:** Chat is available in the Android Chat screen and through
  `/chat`, `/invite`, `/chat/<room>`, and `/invite/<room>` browser routes on the
  hosted HTTP server. Android app clients can also join an invite using the
  app's loopback Tor SOCKS client.
- **FR-002:** Personal chat rooms use generated `personal-<UUID>` identifiers;
  host-created group room names are normalized to lowercase letters, digits,
  hyphens, and underscores, then prefixed with `room-`.
- **FR-003:** Browser visitors use a persistent browser-local client ID and
  receive a deterministic anonymous display name. Host messages are labeled
  Host.
- **FR-004:** Clients fetch messages after a monotonically increasing message
  ID, poll every two seconds, and see an active-client count based on activity
  during the previous 15 seconds.
- **FR-005:** Messages are limited to 1,000 characters. The browser and host
  accept PNG, JPEG, GIF, WebP, MP4, WebM, MPEG audio, OGG, and WAV attachments
  up to 5 MB. Browser upload data must be a matching base64 data URL within the
  configured encoded-size ceiling.
- **FR-006:** A browser visitor can delete a message only when the message
  owner ID matches the supplied client ID. Host UI can delete any message in
  the active room and can delete the active room.
- **FR-007:** The store retains at most 200 messages per room in memory and
  persists room names/messages to app-private `anonymous_chat.json` and a `.bak`
  copy. On initialization it attempts the primary archive, then the backup.
- **FR-008:** Chat responses set no-store and nosniff headers. The HTML response
  sets a restrictive content security policy and uses textContent for message
  text in the browser page.
- **FR-009:** The Android chat list provides a **Share** action per personal
  chat/room and a separate **Join** action for a received invite. Share opens
  the Android share sheet; Join opens invite validation and connection flow.

## Acceptance criteria for current behavior

- **AC-001:** A visitor using the same room URL sees messages for that room and
  not messages from a different room.
- **AC-002:** Browser message polling returns only messages newer than its
  cursor and exposes the active count for the selected room.
- **AC-003:** Empty messages without a valid attachment and invalid/short
  client IDs are rejected; supported messages and attachments are accepted
  within documented limits.
- **AC-004:** A visitor can delete their own message and cannot delete another
  visitor's message through the owner-delete API.
- **AC-005:** Saved rooms/messages are restored across a normal app restart when
  either local archive is readable; retention is bounded to the latest 200
  messages per room.

## Source observations and known gaps

- The host-side local chat writes messages with `ownerId == null`, and the
  browser API's delete-by-owner check does not let a visitor delete those host
  messages.
- Client IDs are stored in browser localStorage and are not an authentication
  secret; the current API trusts the supplied identifier for ownership.
- Browser attachment validation checks allowed MIME values, data URL prefix,
  encoded length, and base64 alphabet. The host-side importer separately
  checks MIME and decoded byte length.
- Chat endpoints pass through rate limiting and optional website Basic auth.
- Browser and app clients supply their own client IDs. These IDs are used for
  approval, presence, and owner-delete checks, but are not cryptographically
  authenticated identities and can be spoofed if learned.
- The app uses a Tor SOCKS tunnel for outbound onion chat requests; external
  browser guests need a Tor-capable browser. Both connect to the host's Onion
  Service, where the host can read message content.
- Host personal-chat identity is scoped to a website ID, while browser room
  membership is determined by the room string in the URL/query.
- Messages and attachment data are persisted as readable JSON in app-private
  storage; they are not encrypted at rest. There is no end-to-end encryption,
  forward secrecy, cryptographic peer verification, or secure session
  protocol. Onion/Tor transport does not provide E2EE against the host.
- Join approval controls room access but does not verify a guest's real-world
  or cryptographic identity. Anyone with an invite may request entry.
- Username search and discovery-node configuration are not exposed in the
  current app UI. Record/lookup code exists, but there is no built-in directory
  or complete username-to-chat flow.

## Verification record

Source reviewed in `AnonymousChatScreen`, `HomeViewModel`, `TorChatClient`,
`TorOnionSocketConnector`, `AnonymousChatStore`, and `HttpServerEngine`.
Selected unit tests cover discovery records/repository, SOCKS framing, and
helpers; there is no end-to-end Android/browser chat test. On 2026-10-15, 36
JVM unit tests passed, but no device/emulator was connected for live chat or
Tor verification.
