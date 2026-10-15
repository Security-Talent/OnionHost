# 004 — Analytics and logs

**Status:** Source reviewed; current-state reference

## Requirements

- **FR-001:** Successful full-file, partial-file, and directory-listing
  responses report the requested path, status, transferred bytes, and download
  classification to the analytics tracker.
- **FR-002:** Request records persist website ID, path, HTTP status, bytes,
  timestamp, date string, and download flag. The tracker increments the
  website visit counter for each recorded response and increments download
  count for download-classified responses.
- **FR-003:** Analytics expose total recorded requests, total downloads, the
  ten most-requested paths, and daily counts for up to 30 grouped dates.
- **FR-004:** The app stores diagnostic logs with timestamp, level, tag, and
  message, exposes a recent-log flow, and supports clearing all logs.
- **FR-005:** Hosting lifecycle and Tor messages are recorded by the service;
  HTTP request records are collected asynchronously through the service scope.

## Acceptance criteria for current behavior

- **AC-001:** A served static response produces a persisted visitor-stat row
  associated with the active website.
- **AC-002:** Analytics group records by website, return the top ten paths, and
  return at most 30 daily groups ordered by date descending.
- **AC-003:** The Logs screen reflects recent persisted logs and clearing logs
  empties the log table.
- **AC-004:** Visitor-stat rows do not contain remote IP addresses or user
  agent values.

## Source observations and known gaps

- The request callback records only the file's basename for file responses,
  while directory listing callbacks pass the request URI.
- `AnalyticsTracker` inserts a visitor-stat row and increments website counters
  for every callback; counters and aggregate queries may therefore represent
  request events rather than unique visitors.
- The analytics record stores status and bytes, but the listed aggregate DAO
  queries count rows and do not filter by status.
- The README says analytics are local and omit IP/user-agent data; source
  fields likewise do not include either value. Requested paths, status codes,
  timestamps, byte counts, and download flags are stored locally; path values
  and timestamps may still reveal sensitive activity/resource names. This is
  not zero-metadata analytics.

## Verification record

Source reviewed in `AnalyticsTracker`, visitor-stat/log entities and DAOs,
`AnalyticsRepository`, view models, and HTTP request callbacks. Unit tests do
not currently exercise analytics retention/privacy end to end. Device
verification was not performed on 2026-10-15.
