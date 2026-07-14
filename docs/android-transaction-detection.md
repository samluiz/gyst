# Android automatic transaction detection

Automatic transaction detection is an optional Android-only feature. It observes eligible notifications from financial applications selected by the user, creates a local draft, and asks the user to review it. It never adds a final expense directly from a device notification.

## The two Android permissions are different

| Permission | What it allows | What happens when denied |
|---|---|---|
| Notification access (`NotificationListenerService`) | Lets Gyst receive eligible notifications posted by applications the user allows. | Gyst cannot detect new transactions. Existing suggestions remain available in the app. |
| Gyst notification permission (`POST_NOTIFICATIONS` on Android 13+) | Lets Gyst display its own “transaction detected” review alert. | Detection may continue and drafts remain in the pending list, but no system review alert is shown. |

Granting Gyst permission to display notifications does not grant access to other applications' notifications. Granting notification-listener access does not override the user's Gyst notification/channel settings.

## Enable detection

1. Open **Profile > Automatic transaction detection**.
2. Read the privacy explanation and enable detection.
3. Choose the banking, card, payment, or finance applications Gyst may monitor. Applications are not implicitly approved.
4. Tap **Open notification access settings**, choose Gyst, and confirm Android's warning.
5. Enable review alerts. On Android 13 or newer, grant the contextual notification permission when prompted.
6. Optional: enable **AI-assisted analysis** and explicitly select a configured BYOK provider/model. The screen identifies what text may leave the device.

The service reconnects through Android's supported listener lifecycle after process recreation, device restart, or app update. Some manufacturers may delay background delivery under aggressive battery restrictions; the settings screen continues to show whether Android currently grants listener access.

## What Gyst reads locally

Only after detection is enabled and the source application is allowed, Gyst reads the minimum fields required for filtering and deduplication:

- source package identifier;
- Android notification key/identifier and posted timestamp;
- title, main text, and expanded text when present;
- notification category and channel identifier needed for filtering.

Gyst ignores group summaries, ongoing events, messages, calls, media controls, and empty notifications before ingestion where Android exposes those signals. It filters Portuguese and English authentication-code, personal-message, promotional, and non-financial patterns locally. A notification containing an OTP/security-code pattern is rejected before durable storage. Long account/card-like digit sequences are redacted to the final four digits.

Relevant text is bounded and normalized. A notification fingerprint prevents duplicate callbacks, updates, and process recreation from producing multiple suggestions. Raw notification text is removed when it is no longer needed for processing; the durable suggestion keeps only the fields and provenance needed for review and duplicate protection.

## Optional BYOK AI analysis

AI-assisted analysis is a separate opt-in. Local filtering always runs first. When enabled, Gyst sends only the allowed source application's package identifier, bounded normalized relevant notification text, locale/context instructions, and the provider-independent extraction schema to the provider/model selected by the user. It does not send the Android notification key, notification identifier, other notifications, installed-app list, complete database, full account numbers, or authentication codes.

The API key authenticates directly to the configured provider endpoint and remains in platform-protected credential storage. The provider may process or retain submitted text according to its own privacy policy; review that policy before enabling AI analysis. Disabling AI analysis cancels queued provider work while retaining local rule-based detection.

## Review alerts and lock-screen privacy

When a suggestion reaches **Needs review**, Gyst posts to the stable **Transaction detections** channel. A private notification may show a redacted amount and short merchant/description; its public lock-screen version only says that a possible transaction is ready for review. Tapping **Review** opens that exact suggestion, including after process recreation. Updating a suggestion updates the existing alert; approval, rejection, or deletion removes it.

You can disable only this channel in **Android settings > Apps > Gyst > Notifications > Transaction detections**. Pending suggestions remain visible inside Gyst when alerts are denied or disabled.

## Approve, reject, or delete

- **Approve** runs the normal validation and expense insertion transaction. Repeated taps cannot insert the same suggestion twice.
- **Reject** keeps the audit state but prevents insertion and removes the system alert.
- **Delete** removes the selected unapproved suggestion and its alert.
- **Delete notification-derived data** clears ingestion records, pending/rejected drafts, delivery markers, and notification provenance. Expenses that the user already reviewed and approved remain ordinary ledger entries.

The current ledger confirms BRL expense candidates. Income, transfer, refund, unknown, unsupported-currency, incomplete, or ambiguous candidates remain editable and cannot be approved until they represent a valid expense. Gyst never silently changes their meaning.

## Disable and revoke access

To stop all future processing:

1. Turn off **Automatic transaction detection** in Gyst. This stops collection and cancels queued detection-analysis work.
2. Use **Delete notification-derived data** if you also want to remove pending/rejected drafts, retained ingestion metadata, and notification provenance. Already approved expenses remain in the ledger until you delete them normally.
3. Tap **Open notification access settings** and revoke Gyst's notification access.
4. Optional: open Gyst's Android notification settings to revoke `POST_NOTIFICATIONS` or disable the detection channel.

Uninstalling Gyst also removes its local suggestions, settings, work, and notification channel. Android may preserve its own permission history according to the operating-system version.

## Troubleshooting

- **Access says denied:** open notification access settings and verify Gyst is enabled. Turning the Android switch off and on can request a listener rebind.
- **Suggestions appear but there is no alert:** check Gyst's app-notification permission and the **Transaction detections** channel separately.
- **A financial app is ignored:** confirm the app is allowed and that its notification includes an actual completed financial action and amount, not only an offer or account summary.
- **AI analysis is waiting:** network-backed work requires connectivity and uses bounded exponential retry. Local pending data remains available if the provider is unavailable.
- **Provider authentication fails:** update that provider's BYOK key; Gyst does not fall back to another provider.
