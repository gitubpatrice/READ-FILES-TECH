# Privacy Policy — Read Files Tech

**Document version** : 9 August 2026 (v2.15)
**App** : Read Files Tech
**Website** : https://www.files-tech.com
**Contact** : contact@files-tech.com
**Source code** : https://github.com/gitubpatrice/READ-FILES-TECH
**Code license** : Apache License 2.0

---

## 1. Purpose

This Privacy Policy explains how the **Read Files Tech** application handles user data, files and permissions.

## 2. User-friendly summary

- ✅ **No advertising** in the application.
- ✅ **No tracker**, audience measurement, behavioural analytics or profiling.
- ✅ **No account** specific to the application.
- ✅ Files opened, read or processed **stay on the user's device**.
- ✅ Files or contents are transmitted **only after an explicit user action** (sharing, export) or when the user voluntarily uses a third-party service.

**Core principle** : Read Files Tech is a local file reader/explorer for TXT, MD, JSON, HTML, CSS, JS, PHP, XML, CSV, DOCX, XLSX, PDF, ZIP and images. All files are processed locally under the user's control.

## 3. Data controller / developer

- **Developer** : Files Tech / Patrice
- **Website** : https://www.files-tech.com
- **Privacy contact** : contact@files-tech.com
- **Source repository** : https://github.com/gitubpatrice/READ-FILES-TECH
- **Source code license** : Apache License 2.0

## 4. Data accessed or processed

| Data type                       | Use                                                                                              | Processing location                |
| ------------------------------- | ------------------------------------------------------------------------------------------------ | ---------------------------------- |
| Files chosen by the user        | Reading, display, edition, conversion, sharing on user request.                                   | Mainly local on the device.        |
| Network technical data           | Functions triggered by the user (sharing, email, update check via GitHub Releases).              | Relevant third-party service.     |
| Local preferences               | Recent files, display settings, sort order.                                                       | Local storage on the device.       |

## 5. No advertising, no trackers, no analytics

The developer declares that the application contains no advertising, tracker, audience measurement, behavioural analytics or profiling system. The application does not sell user data.

## 6. Sharing and data transmission

Files or contents are transmitted to a third party only on explicit user action (Share button, export), via voluntary use of a third-party service, or to comply with applicable legal obligations.

### App-specific notes

- HTML/WebView rendering displays user-chosen content; the user must remain cautious with files from untrusted sources. JavaScript is **disabled by default** for HTML files (opt-in via toolbar).
- The update check queries the public GitHub Releases API (HTTPS, no authentication, no cookie). No user identifier is transmitted.

## 7. Retention and deletion

Files remain under the user's control. No app-specific account is created.

## 8. Security

The application aims to limit processing to what is necessary and to favour local processing. It implements:

- Network Security Config rejecting cleartext HTTP and user-installed CAs;
- App Sandbox + path validation on native MethodChannels (Kotlin);
- Zip-slip protection on archive extraction;
- File size limits to prevent local DoS.

See [SECURITY.md](./SECURITY.md) for the vulnerability disclosure policy.

## 9. Android permissions

This list is derived from `aapt dump permissions` run on the **published release
APK**, not from the source manifest: two permissions are added at manifest-merge
time by a dependency and appear nowhere in the application's own code.

| Permission / access                 | Reason                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `MANAGE_EXTERNAL_STORAGE`           | File explorer feature: browse, read, edit any file on shared storage selected by the user.              |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) / `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | Same feature, on Android versions predating `MANAGE_EXTERNAL_STORAGE`. |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Display and preview media files chosen by the user (Android 13+).                                  |
| `CAMERA`                            | Document scanner and OCR (optional, runtime-granted on demand).                                          |
| `REQUEST_INSTALL_PACKAGES`          | Install an APK tapped in the explorer. The installation itself is performed by the system package installer, which asks for its own confirmation. |
| `INTERNET`, `ACCESS_NETWORK_STATE`  | **Not requested by the application.** Added to the APK by `com.google.android.datatransport:transport-backend-cct`, the Google telemetry transport bundled with Google ML Kit (OCR and scanner). See §9 bis. |

### 9 bis. What "100% local" covers exactly

User files never leave the device: no feature of the application transmits,
uploads or remotely backs them up. Text recognition (OCR) runs entirely offline —
the model is bundled inside the APK (`assets/mlkit-google-ocr-models`,
`libmlkit_google_ocr_pipeline.so`).

Two caveats, stated here because they are verifiable on the APK:

1. **Google ML Kit bundles a telemetry transport.** The
   `TransportBackendDiscovery`, `JobInfoSchedulerService` and
   `AlarmManagerSchedulerBroadcastReceiver` components are present in the APK,
   and they are what bring in `INTERNET` and `ACCESS_NETWORK_STATE`. They may
   report library usage metrics to Google. They do not transmit document
   contents.
2. **The document scanner relies on Google Play services.**
   `play-services-mlkit-document-scanner` is a thin client: the scanning UI and
   its model live in Play services and are downloaded on demand. On a device
   without Google Play services the scanner does not work; the rest of the
   application, OCR included, works normally.

> **Correction, v2.15.** This document previously stated that
> `REQUEST_INSTALL_PACKAGES` had been removed in v2.12.2. That was accurate at
> the time, but the permission was **reinstated in v2.13.0** to restore direct
> APK installation from the explorer — as `SECURITY.md` correctly recorded. The
> table above is derived from the published APK.
>
> The `INTERNET` row also attributed that permission to the GitHub update check.
> That is not its origin: the application does not declare `INTERNET` in its
> manifest; it is added by a dependency.

## 10. Children

The application is not specifically targeted at children and contains no behavioural advertising or profiling.

## 11. Changes

This policy may be updated as the application evolves. The date at the top indicates the current version.

## 12. Contact

📧 **contact@files-tech.com**
