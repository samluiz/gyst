# Gyst - Personal Finance Control (KMP + Compose)

Offline-first personal finance app focused on:
- zero credit-card behavior
- tight monthly budget control
- visibility of subscriptions, installments, and recurring costs
- long-term planning without cloud dependency

This repository currently targets `Android + Desktop`, with shared business logic in Kotlin Multiplatform.

## Current Product State

The app is functional and includes:

- month-by-month budget editing (inline in the budget card)
- expense tracking with recurring flag (`ONE_TIME` / `MONTHLY`)
- subscriptions and installment plans
- payment schedule generation for commitments
- future-month navigation and planning
- month rollover support with recurring copy behavior
- planning screen with forecast and quick scenarios
- persistent, independent advisor conversations with retryable message state
- BYOK image analysis with an editable bulk-import review step
- opt-in Android transaction detection from selected applications' notifications
- durable Android review suggestions and lock-screen-safe detection notifications
- profile/settings with language/theme/guardrails
- local DB migration hardening for legacy schema upgrades

## Module Structure

- `shared`
  - Clean Architecture split:
    - `domain` (models, repositories, use cases, rules)
    - `data` (SQLDelight repositories)
    - `presentation` (`MainStore`, immutable `MainState`)
    - `app` (Compose UI/theme/i18n)
  - Koin shared DI
  - SQLDelight schema + migrations
  - Domain tests
- `androidApp`
  - Android host app (`MainActivity`)
  - Android SQLDelight driver wiring
  - startup DB schema hardening for old installs
- `desktopApp`
  - Desktop host app

## Tech Stack

- Kotlin Multiplatform + Compose Multiplatform
- Koin (DI)
- SQLDelight (persistence)
- kotlinx-datetime
- kotlinx-serialization
- Coroutines + Flow
- ktlint + detekt (static quality gates)

## Business Rules Implemented

- Money stored as `Long` cents.
- No negative amounts.
- No-credit-card mindset: expense payment method flow remains debit-like in app UX.
- Safety guard: "No new installments" lock (default ON).
- Commitments (`subscriptions + installments`) tracked separately from discretionary spend.
- Future months are navigable/editable.
- Month rollover behavior:
  - creates missing budget month
  - copies previous month income when target month is new
  - copies previous month allocations if target month has none
  - copies recurring expenses (`MONTHLY`, non-schedule-linked), deduplicated
- Forecast includes:
  - commitments (subscriptions/installments)
  - recurring expenses
  - projected spend + projected free balance

## Pages and Sections (Current UI)

Top-level layout:
- Header: app title + current month + previous/next month controls (hidden on `Perfil`).
- Bottom navigation: `Resumo`, `Despesas`, `Planejamento`, `Perfil`.

### `Resumo`
- **Orçamento mensal (Budget Hero)**:
  - inline editable budget amount
  - progress bar (`used / budget`)
  - mini-metrics: `Despesas`, `Cobranças`, `Restante`
- **Comparação mensal**:
  - compares current month vs previous month
  - deltas for expenses and billings
- **Ação de mês**:
  - button to advance to next month

### `Despesas`

Read-first cards (lists):

- **Nova despesa** card with “Adicionar” action
- **Assinaturas** list card
- **Parcelamentos** list card
- **Histórico** list card (recent expenses with date/category metadata)

Write is dialog-based:

- **Add Expense** dialog: description, amount, category, recurring switch
- **Add Subscription** dialog: description, amount, billing day, category
- **Add Installment** dialog: description, monthly amount, installments count, category
- **New Category** dialog from category picker (`+ Nova categoria`)
- **Import from images**: select or capture one or more statement, receipt, or banking screenshots; review, edit, select, and validate every extracted row before one atomic import

### `Planejamento`
- **Simulador**:
  - toggle to cancel highest subscription
  - slider for recurring-cost reduction (%)
  - configurable **safety target** (desired monthly leftover)
- **Impacto do cenário**:
  - average monthly delta and 12-month delta
- **Previsão mensal**:
  - future months with projected **Margin** (`income - projected spend`)
  - direct comparison against safety target (`Above target` / `Below target` + delta)
- **Eventos de alívio**:
  - installment-end milestones and freed monthly cash
- **Projeção de meta**:
  - goal amount + monthly contribution inputs
  - projected completion month
- **Consultor (BYOK)**:
  - deterministic priority insights and what-if previews
  - conversational advice grounded in the app's 12-month forecast
  - multiple local conversations ordered by recent activity
  - rename, delete, continue, cancel, and retry conversations without losing completed history
  - configurable OpenAI-compatible base URL, model, and API key
  - persisted conversation context is loaded only from the selected conversation
  - financial context and user messages are sent only after the user configures and uses a BYOK provider

### Advisor provider setup

Open `Planejamento > Consultor` and configure:

1. Select a preset for OpenAI, OpenCode Zen, Gemini, OpenRouter, or Groq.
2. Enter the user's own API key.

The preset supplies the verified base URL, model, and API format. Advanced settings remain available for a different model or any custom OpenAI-compatible provider. User-entered URLs are preserved exactly; endpoint normalization happens only when constructing a request.

The protocol adapters call either `<base-url>/chat/completions` or `<base-url>/responses`. This supports both OpenAI-compatible protocols. Defaults favor free access where the provider offers it: OpenCode Zen uses `deepseek-v4-flash-free`, OpenRouter uses `openrouter/free`, Gemini uses the free-tier-eligible multimodal `gemini-3.1-flash-lite`, and Groq uses the free-plan-eligible `openai/gpt-oss-120b`. OpenAI API models require paid API credits, so its preset remains `gpt-5.4-mini`.

Provider profiles declare text, vision, structured-output, streaming, and tool-calling capabilities. Image import is enabled only for a configured profile that explicitly declares both vision and structured output. The app never silently changes provider or sends an image to a provider the user did not choose. Custom OpenAI-compatible profiles expose the same capability switches instead of inferring support from a model-name string.

Provider and model settings are stored in the local app database. The API key is stored separately: Android encrypts it with Android Keystore and disables Android OS backup; desktop uses Secret Service on Linux, Keychain on macOS, and DPAPI-protected storage on Windows; iOS uses Keychain. Keys, provider payloads, complete financial images, and complete notification bodies are excluded from application logs. Keys and temporary images are outside the finance database and its Google Drive backup; bounded notification text is cleared/redacted after processing.

### `Perfil`

- **Guardrails**: no-new-installments switch
- **Idioma**: `system` / `PT` / `EN`
- **Tema**: `system` / `light` / `dark`
- **Automatic transaction detection (Android)**:
  - explicit feature opt-in and independent AI-analysis opt-in
  - notification-listener access status and shortcut to Android settings
  - app-notification permission status and shortcut to notification settings
  - per-application allow/block selection
  - pending suggestion review, deletion, approval, and rejection
- **Google**:
  - Sign in/out with Google
  - Drive sync action (uploads the SQLite backup over TLS to app-private Drive space; the file itself is not encrypted)
  - account/sync status and latest sync timestamp
- **Release info**:
  - app version (tag-propagated)
  - open-source libraries/licenses dialog

## Release CI/CD (Tag-based)

- Workflows:
  - `.github/workflows/ci-cd-quality-gate.yml` (`main` pushes)
  - `.github/workflows/ci-cd-release.yml` (manual unified release: Android + Windows + optional iOS)
- Trigger:
  - manual `workflow_dispatch` only
  - choose a **tag** in `Use workflow from` (no manual tag input)
  - inputs:
    - `build_android` (`true/false`)
    - `build_windows` (`true/false`)
    - `build_linux` (`true/false`)
    - `build_macos` (`true/false`)
    - `build_ios` (`true/false`)
  - quality gates are mandatory; Android releases include unit, lint, static-analysis, and emulator instrumentation checks
- Outputs published to GitHub Release:
  - Android (optional): release APK (`androidApp`)
  - Windows (optional): desktop artifacts (`.msi` + portable zip + optional `.exe`)
  - Linux (optional): desktop artifacts (`.deb` + portable `.tar.gz`)
  - macOS (optional): desktop artifacts (`.dmg` + portable `.zip`)
  - iOS (optional): signed `.ipa`

### Version propagation

- Version source priority:
  1. `-Papp.version=...`
  2. `APP_VERSION` env var
  3. GitHub tag (`GITHUB_REF_NAME`)
  4. fallback `1.5.0`
- Android:
  - `versionName` = resolved version
  - `versionCode` derived from SemVer (`1.5.0 -> 10500`) unless `-Papp.versionCode` is provided
- Desktop:
  - `nativeDistributions.packageVersion` = resolved version (without `v` prefix)
- Runtime/Profile:
  - `shared` generates `BuildInfo.VERSION_NAME` and `BuildInfo.VERSION_CODE` used in UI

### Required GitHub Secrets

Android:
- `GOOGLE_SERVICES_JSON_B64` (base64 of `google-services.json`)
- `GYST_KEYSTORE_B64` (base64 of `.jks`)
- `GYST_KEYSTORE_PASSWORD`
- `GYST_KEY_ALIAS`
- `GYST_KEY_PASSWORD`

Desktop (required when `build_windows=true` or `build_linux=true`):
- `GYST_DESKTOP_OAUTH_JSON_B64` (base64 of Desktop OAuth JSON)

iOS (required only when `build_ios=true`):
- `IOS_CERTIFICATE_P12_B64`
- `IOS_CERTIFICATE_PASSWORD`
- `IOS_PROVISIONING_PROFILE_B64`
- `IOS_KEYCHAIN_PASSWORD`
- `IOS_EXPORT_OPTIONS_PLIST_B64`
- `IOS_TEAM_ID`
- `IOS_BUNDLE_IDENTIFIER` (optional; defaults to `com.samluiz.gyst`)

Notes:
- `google-services.json` is injected at workflow runtime. Do not commit this file.
- iOS signing assets are injected and used only inside CI job runtime.

## Internationalization

- PT and EN strings via `rememberStrings(...)`.
- PT copy is currently being refined for punctuation/accents.

## Database

- Main schema:
  - `shared/src/commonMain/sqldelight/com/samluiz/gyst/db/finance.sq`
- Explicit migrations:
  - `shared/src/commonMain/sqldelight/com/samluiz/gyst/db/2.sqm` through `7.sqm`
  - migration `7.sqm` upgrades the previous production schema 7 to SQLDelight schema version 8; it adds provider profiles, conversations/messages, image-import sessions/sources, shared transaction candidates, notification ingestion, monitored applications, and expense provenance without replacing existing finance tables
- Android startup hardening:
  - validates/repairs legacy `expense` columns before driver init
  - avoids duplicate-column migration crashes from partial old states
  - migrated databases retain existing budgets, categories, expenses, subscriptions, installments, schedules, settings, and Drive-restored data
  - restore is rollback-capable and rejects backups from a newer schema; app downgrades are unsupported and fail closed without overwriting the newer database

## Run

## Desktop

```bash
./gradlew :desktopApp:run
```

### Google Login + Drive Sync Setup (Desktop)

1. In Google Cloud Console, create an **OAuth 2.0 Client ID** of type **Desktop app**.
2. Enable **Google Drive API**.
3. Save the downloaded JSON as:
   - `~/.gyst/google/desktop_oauth_client.json` (recommended), or
   - set env var `GYST_GOOGLE_DESKTOP_OAUTH_PATH` pointing to that file.
4. Open `Profile` in Desktop app, sign in with Google, then use **Sync data** / **Restore backup**.

Notes:
- Desktop sync uses the same private Drive scope (`drive.appdata`) as Android.
- OAuth token data is stored locally under `~/.gyst/google/tokens`.

## Android

From Android Studio:
1. Select `androidApp` run configuration
2. Choose emulator/device
3. Run

From terminal:

```bash
./gradlew :androidApp:installDebug
```

If needed:

```bash
./gradlew :androidApp:uninstallDebug :androidApp:installDebug
```

### Google Login + Drive Sync Setup (Android)

1. In Google Cloud Console, create an **OAuth 2.0 Client ID (Android)** for package `com.samluiz.gyst` and your signing SHA-1/SHA-256.
2. Enable **Google Drive API**.
3. No broad file scope is used; app requests only `drive.appdata` (app-private folder).
4. Run the app and authenticate in `Perfil` before tapping sync.

### Automatic transaction detection setup (Android)

Automatic detection is disabled by default. It uses Android notification-listener access to inspect notifications from applications the user explicitly allows; it does not use accessibility APIs. Android 13 and newer also use the separate application-notification permission to display the “transaction detected” review alert.

See [Android automatic transaction detection](docs/android-transaction-detection.md) for the permission differences, setup and revocation steps, local filtering, optional BYOK processing, and pending-data deletion.

## iOS (Future Release-Ready)

Local iOS app host exists in `iosApp/` and release automation is available in CI.

To enable iOS release in CI:
1. Prepare Apple signing assets (distribution `.p12`, provisioning profile, export options plist).
2. Add iOS secrets listed above.
3. Trigger `CI/CD Release` with `build_ios=true`.

Current iOS defaults:
- `iosApp/Configuration/Config.xcconfig`
  - `PRODUCT_NAME=Gyst`
  - `PRODUCT_BUNDLE_IDENTIFIER=com.samluiz.gyst`

## Tests

Run shared desktop-compatible tests:

```bash
./gradlew :shared:desktopTest
```

Run static quality checks:

```bash
./gradlew ktlintCheck detekt
```

Run Android unit and device/emulator tests:

```bash
./gradlew :androidApp:testDebugUnitTest :androidApp:connectedDebugAndroidTest
```

Note: `:shared:allTests` may require valid Android SDK configuration in your environment.

## Environment Notes

- Local-only storage (offline-first).
- Seed data is initialized on first launch (`SeedDataInitializer`).
- If Android build fails with SDK path issues, set `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.
- Secrets policy:
  - never commit `google-services.json` or `.jks`
  - keep `**/google-services.json` in `.gitignore`
  - use GitHub Actions Secrets for CI/CD injection
  - runtime SQLite files (`*.db`, `*.db-wal`, `*.db-shm`) are ignored

## Architecture Notes

- ADRs live in `docs/architecture/` and document key decisions for store orchestration and guardrail behavior.
- The v1.5.0 ingestion and persistence design is documented in `docs/architecture/ADR-0003-durable-ai-and-transaction-ingestion.md`.

## Legal

- Privacy Policy: `docs/legal/privacy-policy.md`
- Terms of Service: `docs/legal/terms-of-service.md`
- Publication notes for Google Console: `docs/legal/README.md`

## v1.5.0 transaction-review scope

Image extraction and notification detection preserve `expense`, `income`, `transfer`, `refund`, and `unknown` classifications in the review candidate. The current ledger's confirmed-record pipeline accepts BRL expenses only. A non-expense candidate remains editable and cannot be approved until the user changes it to an expense; it is never silently converted. This follows the existing finance model instead of introducing a second transaction ledger.

## Known Follow-ups

- Migrate deprecated datetime APIs (`monthNumber`, `dayOfMonth`, deprecated `Instant` alias usage).
- Continue UI/accessibility polish and chart/report depth.


## License

This project is licensed under the GNU General Public License v3.0 (`GPL-3.0-or-later`). See `LICENSE` for details.
