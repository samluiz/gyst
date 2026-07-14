# ADR-0001: Store Splitting and Refresh Strategy

## Status
Accepted

## Context

The app started with a single `MainStore` and a single large Compose root file. This helped speed up early feature delivery, but it increased coupling and made tests and refactors harder.

## Decision

1. Keep `MainStore` as application coordinator for now, but remove ad-hoc use-case construction and inject use cases through DI.
2. Parallelize read-heavy refresh segments using coroutines to reduce UI refresh latency.
3. Move non-UI formatting/date helpers out of `GystRoot.kt` into dedicated utility files.
4. Extract screen modules (`Resumo`, `Despesas`, `Planejamento`, `Perfil`) and isolate large dialog, category-management, and profile-identity subflows.
5. Keep SQLDelight repository implementations in separate files so persistence changes remain scoped to one domain capability.
6. Delegate category, expense, subscription, installment, sync, and update mutations from `MainStore` to focused action classes while preserving `MainStore` as the UI-facing coordinator.

## Consequences

- Better testability and consistency for domain orchestration.
- Lower refresh latency under heavier data volumes.
- Lower cognitive load in UI root file.
- `MainStore` retains loading, navigation, state publication, and action serialization; capability-specific mutations live in focused collaborators.
- UI and persistence files now align with feature boundaries without changing the public store API.
