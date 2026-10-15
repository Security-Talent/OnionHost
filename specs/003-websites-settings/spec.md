# 003 — Website management and preferences

**Status:** Source reviewed; current-state reference

## Requirements

- **FR-001:** Website records persist an ID, display name, content directory,
  import type, Onion address, local HTTP port, enabled state, creation time,
  visit/download counters, and optional basic-auth fields.
- **FR-002:** The Websites screen lists records ordered by creation time. A
  record can be renamed, duplicated, activated/deactivated, or deleted.
- **FR-003:** Duplicating a website creates a new ID/name/time, clears its Onion
  address, disables it, and retains the other copied record fields.
- **FR-004:** Activating one record disables all other website records;
  deactivating a record disables all records.
- **FR-005:** Preferences persist auto-start-on-boot (default false), default
  port (default 8080), and rate limit per minute (default 120).
- **FR-006:** On boot completion or package replacement, the receiver checks
  the database for an enabled website and starts the hosting service for it.
- **FR-007:** Deleting a website through the repository also removes its
  imported content directory.

## Acceptance criteria for current behavior

- **AC-001:** Website changes persist in Room and are reflected in the reactive
  website list.
- **AC-002:** Only the selected website is enabled after selecting a website;
  selecting the active website to turn it off leaves no active record.
- **AC-003:** Duplicated records start disabled with a fresh ID, name, and empty
  Onion address.
- **AC-004:** A boot/package-replaced event starts hosting only when an enabled
  website record exists.
- **AC-005:** Preference changes survive process restart.

## Source observations and known gaps

- `BootReceiver` checks the database's active website directly; the stored
  `autoStartOnBoot` preference is not consulted in the receiver. An active
  website can therefore start on boot/package replacement even when the
  preference appears disabled.
- The default-port preference is applied when creating a website; the
  configured rate limit is used for static-file requests. Chat has a separate
  fixed request limit.
- A dark-theme preference key exists, but the inspected UI uses system theme
  state and does not expose or apply a persisted dark-theme toggle.
- `WebsitesViewModel.deleteWebsite` deletes the record through the repository;
  removal of website files is handled in `WebsiteRepositoryImpl`.

## Verification record

Source reviewed in `WebsiteEntity`, `WebsiteDao`, `WebsiteRepositoryImpl`,
`HomeViewModel`, `WebsitesViewModel`, `SettingsRepository`, `SettingsViewModel`,
and `BootReceiver`. On 2026-10-15 unit tests/builds were run, but no device was
available for settings or boot behavior validation.
