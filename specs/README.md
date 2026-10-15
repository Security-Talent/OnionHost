# OnionHost Spec Track

This directory contains source-reviewed specifications for the current product
and tracks future product changes through Spec-Driven Development (SDD).
Existing behavior specs are a reference, not a proposal to change behavior.

## Workflow

1. **Specify** — describe the user problem, goals, scope, requirements, and
   measurable acceptance criteria.
2. **Clarify** — resolve open questions and record decisions in the spec.
3. **Plan** — document the technical approach and affected areas without
   changing implementation.
4. **Break down** — list implementation and verification tasks.
5. **Implement** — begin code changes only after the spec, plan, and tasks are
   agreed.
6. **Verify** — check each acceptance criterion and record results.

The status in each track's `spec.md` is the source of truth. Do not start
implementation while a track is in Specify, Clarify, Plan, or Break down.

## Tracks

| Track | Subject | Status |
| --- | --- | --- |
| [000 — Project map](000-product-baseline/spec.md) | Product scope, architecture, platform, and release evidence | Source reviewed; not a production certification |
| [001 — Hosting and content](001-hosting-content/spec.md) | Import, website files, HTTP serving, and Tor hosting lifecycle | Source reviewed; live Tor verification pending |
| [002 — Anonymous chat](002-anonymous-chat/spec.md) | Host and browser/app chat, rooms, invites, messages, and persistence | E2EE and identity security absent |
| [003 — Website management and preferences](003-websites-settings/spec.md) | Website records, selection, settings, and boot behavior | Source reviewed; settings gaps recorded |
| [004 — Analytics and logs](004-analytics-logs/spec.md) | Request measurements, analytics views, and application logs | Source reviewed; local metadata retention recorded |
| [005 — App shell and distribution](005-app-platform/spec.md) | Navigation, Android configuration, tests, lint, and APK distribution | Debug checks pass; release output unsigned |
| [006 — Chat usernames and discovery](006-chat-usernames/spec.md) | Signed-record primitives and discovery client groundwork | User-facing search removed; no deployed discovery network |
| [Template](_template.md) | Copy this file when opening a new change track | Template |

## Starting a track

Create a folder named `NNN-short-title/` and copy `_template.md` to
`spec.md`. Number change tracks sequentially. Keep implementation code out of
the Specify and Plan stages. Current-state specs should be updated when an
approved change alters the corresponding behavior.

## Verification snapshot

Checked against the repository on 2026-10-15:

- `:app:testDebugUnitTest`: 36 unit tests passed, with no failures.
- `:app:lintDebug`: completed with 0 errors and 55 warnings.
- `:app:assembleRelease`: packaged `app-release-unsigned.apk`; signature
  verification fails because no production signing configuration/key is
  present.
- No Android device or emulator was connected. Tor bootstrap, real Onion
  Service reachability, app/browser chat on hardware, and responsive layouts
  remain unverified.
- This snapshot is not production-ready: chat is not end-to-end encrypted,
  username discovery is not deployed, the release is unsigned, and runtime
  validation is incomplete.

Test counts describe the local unit suite only. A successful Gradle build does
not prove device behavior, Tor reachability, anonymity, or security.
