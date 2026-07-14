# Store publication and privacy notes

This file summarizes disclosures for release maintainers. It does not replace the public [Privacy Policy](privacy-policy.md).

## Version 1.5.0 review checklist

- Publish the updated privacy-policy HTML before or with the Android release.
- In Play Console, disclose local financial data, optional Google account/Drive use, optional user-selected image processing, and optional financial-notification processing.
- Declare `POST_NOTIFICATIONS` as optional app functionality.
- Explain notification-listener access in the in-app contextual consent screen and store review notes. It is not an accessibility permission.
- Do not describe BYOK as fully on-device: advisor, image, and optionally notification content is transmitted to the endpoint configured by the user.
- Link [Android automatic transaction detection](../android-transaction-detection.md) from help/privacy surfaces.

## Notification access justification

Gyst uses Android's supported `NotificationListenerService` only after explicit opt-in, to identify possible completed financial transactions from applications the user allows. Local rules discard obviously irrelevant, messaging, promotional, media, and authentication-code notifications. The result is a reviewable draft; Gyst does not silently create a final expense.

The separate Android application-notification permission is used to alert the user that a draft is ready. Denying it does not grant or revoke listener access, and pending suggestions remain visible in the app.

## External processing disclosure

The user configures a BYOK endpoint and credentials. The app can send:

- selected advisor messages plus relevant financial context;
- complete images explicitly selected for a vision import;
- only locally filtered, bounded notification text when the separate AI-analysis toggle is enabled.

Provider terms and retention apply. The app never silently chooses a different provider.

## Data deletion paths

Store reviewers can be directed to these controls:

- delete finance records, conversations, and suggestions inside the app;
- disable detection and AI analysis independently;
- delete notification-derived data from detection settings;
- revoke notification access in Android system settings;
- revoke Gyst notification permission or disable the detection channel;
- disconnect Google and remove the app-private Drive backup;
- clear app storage or uninstall to remove local data.
