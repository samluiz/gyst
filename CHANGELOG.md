# Changelog

All notable changes are documented here. Versions follow Semantic Versioning.

## [1.5.2] - 2026-07-14

### Fixed

- Simplified image imports with current-day date defaults, conservative category and payment inference, recovered merchant descriptions, and expense-only filtering.
- Made fallback-category creation atomic with confirmed imports and preserved deterministic multi-image provenance.
- Refreshed ledger totals immediately after image imports and displayed imported merchant descriptions in existing expense rows and edit forms.
- Stopped generating an automatic advisor overview when opening an empty or newly created conversation.
- Improved the image-import layout on narrow screens, accessible touch targets, editable preview stability, and localized review guidance.

### Changed

- Installed applications in Android automatic-detection settings now show their actual app icons when available.
- Finance applications are not guessed or automatically enabled because Android does not expose a reliable finance-app classification for installed packages.
- The default application version is now `1.5.2` (`versionCode` `10502`).

## [1.5.0] - 2026-07-14

### Added

- Persistent advisor conversations with independent histories, deterministic ordering, titles, rename/delete actions, durable message states, cancellation, and retry-safe exchanges.
- Explicit AI-provider capability profiles for text, vision, structured output, streaming, and tool calling.
- Multi-image transaction extraction through the configured BYOK provider, followed by an editable validation and duplicate-review preview before atomic insertion.
- A shared provider-independent transaction-candidate pipeline for image imports and Android notification detection.
- Opt-in Android notification-listener integration with per-application source controls and local Portuguese/English financial filtering.
- Durable transaction suggestions, a dedicated `Transaction detections` Android notification channel, stable notification identifiers, and deep links to the exact review item.
- Android 13+ application-notification permission handling, independently from notification-listener access.
- Durable bounded background analysis with network constraints, unique work, retry/backoff behavior, and cancellation when detection is disabled.

### Changed

- The default application version is now `1.5.0` (`versionCode` `10500`).
- Advisor context is loaded from the selected persisted conversation rather than a process-global in-memory list.
- Provider credentials remain outside the finance database while provider profiles and capability metadata are persisted locally.
- SQLDelight schema version 8 is reached through additive migration `7.sqm`, preserving all existing finance rows.

### Privacy and safety

- Images are selected explicitly, held only in app-controlled temporary storage, and cleaned up after completion, cancellation, or expiry; only hashes and import provenance remain.
- Notification content is read only after feature/source checks, locally filtered before optional AI use, and authentication-code notifications are rejected before durable ingestion.
- Optional AI notification analysis sends only bounded normalized relevant text to the provider selected by the user.
- API keys, complete images, complete notification bodies, one-time passwords, and sensitive provider payloads are excluded from logs; keys and temporary images are also excluded from database backups.
- System detection notifications use private lock-screen visibility and redacted summaries.

### Current ledger limitation

- Extraction preserves expense, income, transfer, refund, and unknown candidate types, but confirmed import currently supports BRL expenses only. Unsupported types remain reviewable and are never silently converted or inserted.

## [1.4.4] - 2026-07-14

- Kept the advisor composer visible above the Android software keyboard.

## [1.4.3] - 2026-07-14

- Added secure in-app Android update download and installation flow.

## [1.4.2] - 2026-07-13

- Improved advisor prompting, expense workflows, input ergonomics, and active navigation state.

## [1.4.1] - 2026-07-13

- Corrected desktop ktlint release validation.

## [1.4.0] - 2026-07-13

- Added the BYOK financial advisor and provider templates.
