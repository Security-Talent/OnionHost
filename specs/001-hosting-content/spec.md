# 001 — Hosting and content

**Status:** Source reviewed; current-state reference

## Scope

Importing website content, storing it privately, serving HTTP content, and
publishing the local service over Tor. This records the existing behavior.

## Requirements

- **FR-001:** The user can import a folder, ZIP archive, or single file through
  Android document URIs. Imported content is copied under the app's
  `hosted_websites/<websiteId>` directory.
- **FR-002:** ZIP entries are resolved against the target directory and entries
  that fail path-safety checks are skipped. Folder trees are recursively
  copied. A non-`index.html` HTML single-file import is also copied as
  `index.html`.
- **FR-003:** Starting hosting selects the requested website or the enabled
  website, marks it active, starts an embedded HTTP server on that record's
  port, then starts Tor with a v3 hidden service forwarding port 80 to the
  local HTTP port.
- **FR-004:** Tor status progresses through start/bootstrap states. The app
  only stores/shows the address as live after Tor log evidence indicates
  descriptor publication and a valid hostname file is available. Publication
  times out after ten minutes.
- **FR-005:** The service runs in the foreground with a persistent notification
  that provides stop and restart actions. Stop shuts down HTTP and Tor and
  disables all website records; restart stops both, waits briefly, then starts
  the selected site again.
- **FR-006:** HTTP requests resolve within the configured website root; an
  invalid/escaping path returns 403. Missing files return 404. Directories
  serve `index.html` when present, otherwise a directory listing is returned.
- **FR-007:** Files are served using an extension-based MIME map, with unknown
  extensions as `application/octet-stream`. Selected archive/media/document
  extensions receive attachment download headers.
- **FR-008:** Single byte-range requests return 206 and `Content-Range`; an
  invalid range returns 416. Full and partial file responses advertise byte
  ranges.
- **FR-009:** When website basic authentication is enabled and the username is
  nonblank, static-file requests require valid HTTP Basic credentials;
  unauthenticated requests receive 401 and a challenge header.
- **FR-010:** Static requests use the configured rate limit (default 120
  requests per minute, clamped to 1–10,000). Chat pages/APIs use a separate
  fixed 1,800 requests-per-minute limiter. Counters are keyed by the address
  observed by the local HTTP server and reset on the limiter's time window.

## Acceptance criteria for current behavior

- **AC-001:** Folder, ZIP, and single-file imports create isolated content
  directories; ZIP traversal entries do not write outside the target.
- **AC-002:** A hosting session starts its HTTP server on the selected website
  port and asks Tor to forward onion port 80 to that port.
- **AC-003:** The Onion address is not represented as live from the hostname
  file alone; publication evidence is also required.
- **AC-004:** Requests for an in-root file, directory, missing path, and
  traversal path produce the corresponding content, listing/index, 404, or 403
  behavior described above.
- **AC-005:** Static HTTP responses honor the documented MIME, download, range,
  and optional authentication behavior.

## Source observations and known gaps

- `PathTraversalSanitizer.getSafeFile` canonicalizes and bounds resolved
  request paths; the separate `isSafeToServe` helper is not used by the
  handler. ZIP extraction has its own path checks; folder import uses
  `DocumentFile` traversal.
- Chat routes are rate-limited and pass through the optional website Basic
  authentication check before dispatch.
- The field is named `authPasswordHash`, but the HTTP handler compares the
  supplied Basic-auth password directly with that value. The UI/storage must
  therefore be treated as containing a plaintext-equivalent password, not a
  salted password hash.
- The default port preference is used when creating a website, and the
  configured rate limit is passed to the static-file limiter. The chat limiter
  remains fixed and separate.
- The local HTTP service is plain HTTP on loopback and the Onion Service maps
  remote port 80 to it. Tor's Onion Service transport does not provide
  end-to-end encryption from a visitor through the hosting app; the host is
  the endpoint and can read requests.
- These are observations for accurate tracking, not implementation changes.

## Verification record

Source reviewed in `StorageManager`, `PathTraversalSanitizer`, `MimeValidator`,
`RateLimiter`, `HttpServerEngine`, `OnionHostingService`, and `TorManager`.
The debug and release Gradle builds passed on 2026-10-15 (release APK unsigned);
live device and Tor network behavior were not verified.
