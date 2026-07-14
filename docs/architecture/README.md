# Architecture Notes

This directory holds lightweight ADRs (Architecture Decision Records) for decisions that shape shared domain behavior and maintainability.

Current ADRs:

- `ADR-0001-store-splitting-and-state-loading.md`
- `ADR-0002-safety-guard-no-new-installments.md`
- `ADR-0003-durable-ai-and-transaction-ingestion.md`

Current shared-code boundaries:

- `presentation/MainStore.kt`: lifecycle, navigation, serialized action execution, and state publication.
- `presentation/Store*Actions.kt`: capability-specific mutations and external-service actions.
- `app/ui/screens/`: screen entry points plus focused dialog and identity/category subflows.
- `data/repository/Sql*Repository.kt`: one SQLDelight repository implementation per file.
- `domain/model/TransactionCandidate.kt`: provider-independent normalization, validation, and transaction fingerprints shared by image and notification sources.
- `domain/service/AiProviderClient.kt`: provider capability and text/vision/structured request boundary.
- `androidApp/.../detection/`: Android framework listener, durable work scheduling, detection notifications, and suggestion deep links.
