# 006 — Chat usernames and discovery

**Status:** Signed-record and lookup-client groundwork exists. The user-facing search/configuration UI has been removed from the current Chat screen. No discovery nodes, registration service, peer chat, endpoint ownership challenge, or E2EE are shipped.

## Problem

Today, a person joins a browser chat by receiving an Onion URL that includes a
room name, such as `/chat/personal-<id>`. The user wants to share a memorable
username (for example, `securi3ytalent`) and let another person find/connect to
the chat without copying a long Onion URL.

## Goal

Allow a person to share a username and let another OnionHost user enter or
search for that username to reach the intended chat.

## Proposed user story

As an OnionHost user, I want to choose a username and share it, so another
person can find my chat without manually exchanging the full room URL.

## Proposed functional requirements

- **FR-001:** A user can claim and manage a username that is unique within the
  chosen discovery system.
- **FR-002:** Another user can enter a username in OnionHost and request a chat
  with its owner.
- **FR-003:** The lookup result connects the requester to the intended Onion
  chat while preserving the existing room separation and message behavior.
- **FR-004:** Usernames follow documented character, length, case-normalization,
  reserved-name, and reassignment rules.
- **FR-005:** A user can revoke or change a username; stale usernames must not
  silently route to a different owner.
- **FR-006:** The feature must state what information a username lookup
  exposes, how long lookup records remain, and how abuse/spam is limited.

## Proposed acceptance criteria

- **AC-001:** Alice claims a username and sees whether it is available.
- **AC-002:** Bob can enter Alice's username and get a clear result that either
  opens/requests the intended chat or reports that the name is unavailable.
- **AC-003:** A changed or revoked username no longer routes to its former chat.
- **AC-004:** A username lookup cannot cause a chat with a different user due
  to case differences, stale records, or reassignment.
- **AC-005:** Existing direct Onion URL invitations continue to work.

## Architecture constraint

An Onion URL is currently the network locator. A username is only a human
readable alias; Tor cannot route to it by itself. If users must be able to find
one another without already knowing an Onion address, the system needs a
username-to-Onion-address discovery path. Possible approaches include:

1. **Central directory service:** easiest to search and reserve unique names;
   requires operating a service and deciding what lookup metadata it stores.
2. **Existing OnionHost user's host address plus an introducer/directory:**
   preserves direct Tor connections, but still needs a reachable place to
   resolve usernames to current Onion addresses.
3. **Local address-book alias:** no shared directory, but the username only
   works for people who already imported/received the contact record; this
   does not meet the stated "share only username" goal by itself.

Even with username discovery, the client still needs a route to the Onion
Service. The feature can hide the long URL from the sharing workflow, but it
cannot eliminate the underlying network locator or discovery dependency.

## Repository architecture review

Inspection of the current implementation found:

- Before the Tor transport increment, `TorManager` ran an inbound v3 hidden
  service forwarding port 80 to the embedded HTTP server and explicitly set
  `SocksPort 0`. The current client uses the added loopback SOCKS adapter for
  configured discovery lookups, but not for peer messaging.
- `HttpServerEngine` is a NanoHTTPD server bound to `127.0.0.1`. Its chat API is
  HTTP polling/POST/DELETE against one host's in-memory/local JSON archive.
- The Android app has no peer messaging layer, DHT, built-in bootstrap node,
  registration publisher, or endpoint contact-verification flow. Search nodes
  are user-configured.
- Browser chat uses a caller-supplied localStorage client ID. Messages are
  persisted as readable JSON on the hosting phone; there is no end-to-end
  encryption protocol.
- Bouncy Castle is already present and is used for Tor Ed25519/SHA3 helpers, so
  the app can safely add pure local username and signed-record primitives
  without adding a dependency. That does not provide network discovery or
  prove control of an onion endpoint.

### Architecture decisions and implementation plan

The present app is an inbound-only Tor host. Its daemon is an Android native
`libtor.so` subprocess; the repository does not include an outbound SOCKS
client, directory/DHT, peer client, identity store, or message crypto. The
existing HTTP polling endpoint stores plaintext JSON. Therefore the UI must not
present global search, remote chat, or E2EE as available until the corresponding
network and crypto paths exist and are tested.

The first network milestone is a local-only Tor SOCKS listener alongside the
existing hidden service. The app's outbound adapter will speak SOCKS5 with the
domain-name address form, validate v3 onion addresses before opening a socket,
and refuse non-onion targets. It will not use platform DNS or fall back to a
direct socket. Tor must bind SOCKS only to loopback and reject non-onion targets
(`OnionTrafficOnly` where supported by the bundled daemon); daemon startup must
fail closed if the required config is rejected. Existing inbound hosting stays
on `127.0.0.1`. The project currently has no two-device Tor integration
environment, so local protocol tests alone cannot establish real network reachability.

For discovery, prefer replicated signed records queried from multiple
independently operated onion endpoints, with client-side signature, expiry,
pinning and conflict validation. Nodes replicate/serve records but are not
authoritative: clients retain signed records and reject silent key changes.
This is more incremental than introducing a DHT with no peer/bootstrap
protocol already in the app. A fresh installation still needs an initial set
of bootstrap onion addresses distributed in the app or imported by the user;
these are availability entry points, not identity authorities. No bootstrap
endpoints are configured in this repository, so a usable distributed network
cannot yet be deployed or truthfully tested. Lookup should be exact-handle,
rate-limited by nodes, and made opt-in to reduce enumeration; the operator and
network metadata exposure remains a documented tradeoff.

The search client implements a small query protocol for user-configured nodes:
`GET /.well-known/onionhost/users/{username}` over HTTP/1.0 inside the Tor SOCKS
tunnel. A 200 response uses `text/plain`, a bounded `Content-Length`, and
newline-delimited `UsernameRegistration.encode()` records; 204/404 means no
record. The app supports up to 12 manually entered onion endpoints (port 80 by
default), queries them in parallel, and validates each record locally. Different
valid fingerprints for the same handle are a conflict. Locally pinned
fingerprints are stored in DataStore; a different valid key is reported as an
identity change. First-use pinning requires an explicit user action. There are
no running/configured nodes and no registration publisher/server implementation,
so this client protocol cannot produce a global result out of the box.

The current Android Chat screen does not expose username search or node
configuration. The repository still contains signed-record validation,
pinning, and a configurable Tor lookup client as groundwork, but no configured
or shipped node network exists. A verified record is not a verified binding
between its signing key and advertised onion service. There is no registration
publisher, endpoint challenge, peer chat, or E2EE. Existing hosted room chat is
separate from username lookup and remains plaintext at the application layer.

Onion endpoint ownership must be proven by a fresh nonce challenge signed by
the record's Ed25519 identity over a Tor connection. A valid signed mapping
alone does not prove that the signer controls the advertised onion service.
Conflicting identities are surfaced and block connection until the user
resolves them through an independently verified fingerprint. Identity changes
require explicit user confirmation and a new pin; automated recovery is not
available without a trusted recovery mechanism.

For end-to-end messaging, evaluate an established protocol/library (candidate:
Signal Protocol) before integrating it. The repository has no prekey/service
backend or session persistence. Adding a crypto library alone would not provide
asynchronous setup, forward secrecy, offline delivery, replay protection, or
identity authentication. Do not send message plaintext to the current host
API, and do not label existing room chat as E2EE. Library API stability,
licensing, Android ABI compatibility, and protocol service requirements must
be reviewed before dependency selection.

Identity private material should be encrypted at rest using an Android Keystore
AES key, with private-key bytes decrypted only in memory for signing. Keystore
support does not make a Bouncy Castle Ed25519 private key itself non-exportable.
The current signed-record wire format remains version 1 pending an explicit
migration.

The signature proves that a record was signed by the key embedded in that
record. It does **not** by itself prove that the signer controls the advertised
onion service, make a username globally unique, prevent squatting, or make the
record discoverable. A later network milestone must add a challenge over a
verified Tor connection and define a decentralized conflict/ownership policy.

### Remaining architecture decisions and blockers

1. Confirm bundled Tor version support for the chosen SOCKS flags on supported
   devices and add a two-device onion connectivity test.
2. Obtain or define independently operated bootstrap onion endpoints and a
   wire protocol for signed-record replication, revocation, and abuse controls.
3. Select an E2EE protocol and implement its required key/prekey exchange,
   persistent sessions, encrypted storage, offline delivery, replay handling,
   and identity change UX.
4. Define user-controlled recovery and username release/reassignment policy.

## Current verification and product status

On 2026-10-15 the repository's 36 JVM unit tests passed and `lintDebug` finished
with 0 errors and 55 warnings. Release packaging completed but the APK is
unsigned. No device/emulator test was available. The username feature must not
be described as usable global discovery or as a way to start a secure chat.

## Open decisions

- Is username lookup global across OnionHost users, or only among people who
  already know each other / share a directory?
- Is an external directory service acceptable, or must the product avoid
  operating any central service?
- Should a username open a personal chat immediately, send a chat request
  first, or allow either user to choose?
- Should usernames be public/searchable, or should lookup require an exact
  handle and an opt-in setting?
- What should happen when the host phone is offline or its Tor service is not
  published?
- Are usernames permanent, changeable, or claimable again after release?

## Non-goals for this track

- Replacing Tor or making a username itself a Tor address.
- Claiming that usernames provide end-to-end encryption or anonymity from the
  discovery service.
- Changing existing chat message, room, attachment, or persistence behavior
  until the discovery design is agreed.

## Implemented milestone

- Username normalization: lowercase ASCII handles, 3–32 characters, begin with
  a letter, followed by letters, digits, or underscore.
- Ed25519 key generation and signing/verification for bounded-lifetime records
  (maximum 30 days), including a versioned bounded wire encoding.
- Fingerprint pin verification and conflict reporting when different keys
  claim the same normalized username.
- Unit tests cover valid signing/round-trip, field tampering, expiry, malformed
  records, identity mismatch, and duplicate claims.

Local cryptographic groundwork does not register a username with peers or persist
a private key. The lookup client performs queries when compatible nodes are
configured, but no such nodes are shipped. This does not encrypt chat messages
end to end.

## Plan and tasks

- [x] Inspect existing chat and Tor networking support.
- [x] Implement local registration record validation and signatures.
- [x] Add unit tests for record verification and conflict handling.
- [ ] Add secure identity-key persistence and rotation.
- [x] Add loopback-only onion-restricted SOCKS and SOCKS5 domain transport; test no-DNS/no-direct-fallback behavior.
- [ ] Verify outbound Tor connectivity between two Android devices.
- [ ] Select and implement a multi-bootstrap distributed discovery protocol.
- [ ] Prove onion endpoint control and handle username conflicts/recovery.
- [ ] Integrate an established E2EE protocol for peer chat.
- [x] Add a clearly scoped username lookup UI and configurable multi-node Tor client; no bootstrap directory is shipped.
- [ ] Add a registration publisher and deploy/test independently operated discovery nodes.
- [ ] Bind signed identities to onion endpoints with a challenge and integrate a peer chat protocol before enabling Start Chat.
- [ ] Add multi-device Tor integration tests and update user-facing privacy docs.

## Verification record

Packaged native daemon inspected: Tor 0.4.9.11; the binary includes the
`OnionTrafficOnly` option. The app config now requests a loopback-only SOCKS5
listener on port 9050, restricted to onion destinations, while preserving the
existing inbound v3 service. The new socket adapter validates the onion
address, sends the target as the SOCKS domain-name address type (no Android DNS
resolution), and rejects SOCKS failures. Local tests exercise the SOCKS
handshake, domain-form request, non-onion rejection, and unsuccessful response.

Verification: `:app:testDebugUnitTest` passed (33 tests, 0 failures) and
`:app:assembleDebug` succeeded. APK:
`app/build/outputs/apk/debug/app-debug.apk`.

Local test coverage includes the protocol client running over a local SOCKS test
proxy, signed-record resolution, cancellation, retry, and identity conflicts. No cross-device Tor connectivity, live
discovery-node lookup, username registration network, or end-to-end chat test
can be claimed. No bootstrap nodes are configured. Keystore-backed identity
persistence, node-side registration/replication, endpoint challenge, and E2EE
remain unimplemented. Existing chat still sends and stores readable messages.
