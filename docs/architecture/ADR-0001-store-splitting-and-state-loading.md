# ADR-0001: Store Splitting and Refresh Strategy

## Status
Accepted

## Context

The app started with a single `MainStore` and a single large Compose root file. This helped speed up early feature delivery, but it increased coupling and made tests and refactors harder.

## Decision

1. Keep `MainStore` as application coordinator for now, but remove ad-hoc use-case construction and inject use cases through DI.
2. Parallelize read-heavy refresh segments using coroutines to reduce UI refresh latency.
3. Move non-UI formatting/date helpers out of `GystRoot.kt` into dedicated utility files.
4. Continue incrementally extracting screen modules (`Resumo`, `Despesas`, `Planejamento`, `Perfil`) into separate files in follow-up slices.

## Consequences

- Better testability and consistency for domain orchestration.
- Lower refresh latency under heavier data volumes.
- Lower cognitive load in UI root file.
- Full feature-module extraction remains an incremental follow-up, not a single risky rewrite.

