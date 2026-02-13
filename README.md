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
- **Impacto do cenário**:
  - average monthly delta and 12-month delta
- **Pressão mensal**:
  - future months with safe allowance and risk label (`low/medium/high`)
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

## Known Follow-ups

- Migrate deprecated datetime APIs (`monthNumber`, `dayOfMonth`, deprecated `Instant` alias usage).
- Continue UI/accessibility polish and chart/report depth.
