# ADR-0003: Durable AI and Transaction Ingestion

## Status

Accepted for version 1.5.0.

## Context

Advisor history previously lived only in process memory. Image extraction and Android notification detection both need to turn untrusted external input into finance records, but neither source may bypass validation or insert a transaction before review. Provider retries, duplicate Android callbacks, process recreation, and database upgrades also make in-memory idempotency insufficient.

The existing application already has a shared Kotlin domain/data layer, SQLDelight, repository boundaries, BYOK credentials, and an expense insertion path. Version 1.5.0 extends those boundaries rather than introducing a second ledger or Android-only transaction model.

## Decision

### Persist conversations and message lifecycle

Conversations and messages are first-class SQLDelight entities. A conversation stores its stable identifier, title source, provider/model metadata, prompt snapshot, preview, timestamps, and the next deterministic sequence. Each message stores its role and one of `PENDING`, `STREAMING`, `COMPLETED`, `FAILED`, or `CANCELLED`, plus exchange/retry identifiers, provider metadata, optional usage, and typed failure data.

The selected conversation is the only source of provider context. A user/assistant exchange is reserved atomically. The unique `(conversation_id, sequence_number)` and `(conversation_id, exchange_id, role)` constraints prevent ordering races and duplicate retry rows. Partial assistant content remains durable when a stream fails or is cancelled.

### Share one candidate pipeline

Image and Android notification sources produce the same provider-independent candidate model:

```text
Selected image(s)                 Allowed Android notification
        |                                      |
        +------------- source adapter --------+
                              |
                              v
                provider-independent extraction
                              |
                              v
                 normalization (money/date/type)
                              |
                              v
                    validation + warnings
                              |
                              v
            fingerprint and duplicate comparison
                              |
                              v
                  durable editable candidate
                              |
                 explicit user confirmation
                              |
                              v
                normal expense insertion command
                              |
                              v
                 expense + provenance in one DB tx
```

Provider responses deserialize into extraction DTOs, never SQLDelight or finance entities. Normalization handles locale-specific money and date forms. Validation retains ambiguity as review warnings instead of inventing values. A fingerprint combines normalized date, amount, currency, description, payment/account hint, and candidate type.

The existing ledger currently confirms BRL expenses only. Income, transfer, refund, and unknown classifications are preserved on the candidate and shown for correction; they cannot be silently converted or approved into a different ledger meaning.

### Make imports explicit and atomic

An import session has a stable idempotency key and explicit lifecycle. Each selected image has a hash and deterministic source order. Extracted rows remain review candidates until confirmation. By default, confirmation inserts all selected valid expense rows and their provenance in one transaction, or inserts none. Source/session and candidate idempotency keys make a repeated analysis or confirmation safe.

Image custody becomes durable before analysis: accepting a picker or camera result atomically creates a `CREATED` session and all of its source rows. Provider/model/locale configuration is attached to that same session only when the user explicitly starts analysis. Adding or removing sources replaces the prior draft in one database transaction, so process death cannot expose a half-replaced source set.

On Android, every successfully copied `ActivityResult` is first recorded in a private recovery queue, including results delivered to an active caller. The image-import service persists the database draft before acknowledging that queue. Bootstrap initialization and `Activity.onResume` recovery share one initialization lock, making callback delivery, process recreation, database failure, and acknowledgement replay idempotent without deleting a source still referenced by a draft.

Original image bytes are kept only in app-controlled cache storage. Accepted types are JPEG, PNG, WebP, and GIF; the per-image limit is 12 MiB and the batch limit is 40 MiB. Files are deleted on completion or cancellation and are also eligible for cleanup after 24 hours. Unconfirmed database drafts expire on the same lifecycle; completed imports retain only the hash and necessary provenance, not the image.

### Keep Android ingestion local-first and durable

Android uses `NotificationListenerService`, never accessibility services or polling. Collection is gated before extras are read by the feature opt-in and source-application policy. Ongoing/group-summary, message, call, media, and authentication-code notifications are filtered locally. Account-like long numbers are redacted before durable ingestion.

```text
NotificationListenerService
        |
        | feature + source gate, minimal extraction, local filter
        v
notification_ingestion (unique notification fingerprint)
        |
        +-- deterministic candidate is sufficient --> NEEDS_REVIEW
        |
        +-- user enabled BYOK AI --> unique WorkManager job
                                            |
                  network constraint + bounded exponential backoff
                                            |
                                            v
                                   shared candidate pipeline
                                            |
                                            v
                                     NEEDS_REVIEW
                                            |
                     stable Android notification + exact deep link
```

Provider analysis uses WorkManager unique work with `KEEP`, a network constraint, exponential backoff beginning at 30 seconds, and a five-attempt budget. Only a stable local suggestion identifier enters WorkManager input; notification text remains in the database boundary. Disabling detection or assisted analysis cancels tagged work.

The stable `transaction_detections` channel uses private lock-screen visibility. A suggestion-derived notification identifier updates the same alert rather than notifying twice. Approval/rejection cancels the alert. The immutable pending intent carries the stable suggestion identifier so a closed, backgrounded, or recreated process opens the exact candidate after normal app initialization.

### Separate permission responsibilities

Notification-listener access authorizes reading eligible external notifications. Android 13+ `POST_NOTIFICATIONS` authorizes Gyst to display its own review alerts. Detection can retain an in-app pending suggestion when the second permission is denied; it never treats that denial as listener authorization. Both states are visible and revocable independently.

### Use additive migration `7.sqm` and schema version 8

Migration `7.sqm` creates:

- `advisor_provider_profile`
- `advisor_conversation` and `advisor_message`
- `transaction_import_session` and `transaction_import_source`
- shared `transaction_candidate`
- `notification_ingestion` and `monitored_application`
- `expense_origin`

The migration upgrades the previous production schema version 7 to SQLDelight schema version 8. It does not drop, rename, or recreate an existing finance table. Foreign keys define cascade/set-null behavior, while unique constraints and indexes protect identity and query order. A database restored from an older Drive backup is opened through the same migration sequence before repositories use it.

Restores stage and validate the downloaded SQLite file, close the active driver under the shared database gate, open/migrate and query the replacement, then commit by deleting the rollback copy. Any failure restores the previous file. Backups from a newer schema and application downgrades are intentionally rejected; the app never attempts a destructive downgrade.

## Security and observability consequences

- API keys stay in the existing platform secure credential store, never the SQL database.
- No hidden provider call is allowed; images and notification text require explicit provider/feature choices.
- Logs may include a local identifier, typed status, and error class, but never a key, provider payload, image, complete notification body, OTP, or full account number.
- Notification text is bounded and redacted, and raw content is cleared after processing when it is no longer needed.
- Provider behavior and retention are governed by the provider selected by the user; the app links that provider's key/terms page during setup.

## Consequences

- Restart and upgrade recovery no longer depend on the process surviving.
- Both ingestion sources share validation, duplicate detection, editing, and insertion semantics.
- Database constraints, rather than UI flags, provide the last line of duplicate protection.
- Additional entities and state transitions add implementation complexity, but make cancellation, retry, and recovery observable and testable.
- A future generalized ledger can add confirmed income/refund/transfer commands without changing extraction sources or creating a parallel candidate architecture.
