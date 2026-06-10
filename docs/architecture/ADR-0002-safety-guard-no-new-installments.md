# ADR-0002: Safety Guard `no_new_installments` as Domain Rule

## Status
Accepted

## Context

The product claims a default guardrail: "No new installments". The persistence schema already had a `no_new_installments` field, but runtime mapping and store behavior did not enforce it end-to-end.

## Decision

1. Model `noNewInstallments` explicitly in `SafetyGuard`.
2. Persist and read it correctly via SQLDelight repositories.
3. Seed it as enabled (`true`) for first-run data.
4. Expose it in profile settings and enforce it in installment creation/update/duplication flows.

## Consequences

- Product behavior now matches documented rules.
- Installment-related actions are consistently blocked when guardrail is enabled.
- Future policy changes can be handled as a domain toggle instead of scattered UI checks.

