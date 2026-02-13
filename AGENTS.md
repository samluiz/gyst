# Repository Guidelines

## Project Structure & Module Organization
- `shared/`: main Kotlin Multiplatform logic.
  - `src/commonMain/`: domain, data, presentation, and shared Compose UI.
  - `src/commonTest/`: domain/use-case tests.
  - `src/commonMain/sqldelight/`: SQLDelight schema and migrations (e.g., `finance.sq`, `2.sqm`).
- `androidApp/`: Android host app (`MainActivity`, platform DI/driver setup).
- `desktopApp/`: Desktop JVM host app entrypoint.
- `iosApp/`: iOS host scaffold (not primary target yet).
- Root Gradle files: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`.

## Build, Test, and Development Commands
- `./gradlew :shared:compileKotlinDesktop`  
  Compiles shared code quickly (best smoke check).
- `./gradlew :desktopApp:run`  
  Runs desktop app.
- `./gradlew :androidApp:installDebug`  
  Installs Android debug build on connected device/emulator.
- `./gradlew :shared:desktopTest`  
  Runs shared tests without requiring Android SDK test tasks.
- `./gradlew :androidApp:assembleRelease -Papp.version=v1.2.3`  
  Builds Android release artifact with tag-style version.

## Coding Style & Naming Conventions
- Language: Kotlin (KMP + Compose). Use 4-space indentation.
- Keep architecture boundaries clear: `domain` (pure), `data` (repos/drivers), `presentation` (store/state/UI events).
- Names:
  - Types/objects: `PascalCase`
  - functions/properties: `camelCase`
  - constants: `UPPER_SNAKE_CASE`
- Money values must remain `Long` cents (never floating-point).
- Prefer small, focused composables and immutable UI state.

## Testing Guidelines
- Framework: `kotlin.test` with coroutine test utilities.
- Put tests under `shared/src/commonTest/kotlin/...`.
- Test naming: behavior-focused (e.g., `forecastShowsFreedCashAfterCommitmentEnds`).
- Cover business rules (validation, rollover, forecast, guards) before UI details.
- CI workflows:
  - `CI/CD Quality Gate` on pushes to `main`
  - `CI/CD Release` on tags `v*`, and it waits for release quality checks before publishing artifacts

## Commit & Pull Request Guidelines
- No established commit history yet; use Conventional Commits:
  - `feat: ...`, `fix: ...`, `refactor: ...`, `test: ...`, `docs: ...`.
- PRs should include:
  - concise summary
  - affected modules/files
  - test/compile commands executed
  - screenshots or short video for UI changes.

## Security & Configuration Tips
- App is offline-first; avoid introducing external data sync by default.
- Do not commit secrets, keystores, or machine-specific SDK paths.
- For Android local setup, keep SDK path in `local.properties` only.
- Never commit `google-services.json` or release keystore files.
- GitHub Actions secrets expected for releases:
  - `GOOGLE_SERVICES_JSON_B64`
  - `GYST_KEYSTORE_B64`
  - `GYST_KEYSTORE_PASSWORD`
  - `GYST_KEY_ALIAS`
  - `GYST_KEY_PASSWORD`
- If a secret file is ever committed, rotate credentials and rewrite history before release.
