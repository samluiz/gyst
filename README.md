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

### `Perfil`
- **Guardrails**: no-new-installments switch
- **Idioma**: `system` / `PT` / `EN`
- **Tema**: `system` / `light` / `dark`
- **Google**:
  - Sign in/out with Google
  - Drive sync action (uploads encrypted-transport backup file to app-private Drive space)
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
  - inputs:
    - `tag` (required, example: `v1.4.0`)
    - `skip_quality_gate` (`true/false`)
    - `build_ios` (`true/false`)
- Outputs published to GitHub Release:
  - Android: release APK (`androidApp`)
  - Windows: desktop artifacts (`.msi` + portable zip + optional `.exe`)
  - iOS (optional): signed `.ipa`

### Version propagation

- Version source priority:
  1. `-Papp.version=...`
  2. `APP_VERSION` env var
  3. GitHub tag (`GITHUB_REF_NAME`)
  4. fallback `1.0.0`
- Android:
  - `versionName` = resolved version
  - `versionCode` derived from SemVer (`1.2.3 -> 10203`) unless `-Papp.versionCode` is provided
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

Desktop:
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
- Migration:
  - `shared/src/commonMain/sqldelight/com/samluiz/gyst/db/2.sqm`
- Android startup hardening:
  - validates/repairs legacy `expense` columns before driver init
  - avoids duplicate-column migration crashes from partial old states

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

Note: `:shared:allTests` may require valid Android SDK configuration in your environment.

## Environment Notes

- Local-only storage (offline-first).
- Seed data is initialized on first launch (`SeedDataInitializer`).
- If Android build fails with SDK path issues, set `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.
- Secrets policy:
  - never commit `google-services.json` or `.jks`
  - keep `**/google-services.json` in `.gitignore`
  - use GitHub Actions Secrets for CI/CD injection

## Legal

- Privacy Policy: `docs/legal/privacy-policy.md`
- Terms of Service: `docs/legal/terms-of-service.md`
- Publication notes for Google Console: `docs/legal/README.md`

## Known Follow-ups

- Migrate deprecated datetime APIs (`monthNumber`, `dayOfMonth`, deprecated `Instant` alias usage).
- Continue UI/accessibility polish and chart/report depth.


## License

This project is licensed under the GNU General Public License v3.0 (`GPL-3.0-or-later`). See `LICENSE` for details.
