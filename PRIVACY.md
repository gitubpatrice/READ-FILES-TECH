# Privacy Policy — Read Files Tech

**Document version** : 10 October 2026 (v2.15.3)
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
- ✅ **No network permission** since v2.15.3: the application opens no connection of its own (see §6 bis and §9).

**Core principle** : Read Files Tech is a local file reader/explorer for TXT, MD, JSON, HTML, CSS, JS, PHP, XML, CSV, DOCX, XLSX, PDF, ZIP and images. All files are processed locally under the user's control.

## 3. Data controller / developer

- **Developer** : Patrice Haltaya (Files Tech)
- **Website** : https://www.files-tech.com
- **Privacy contact** : contact@files-tech.com
- **Source repository** : https://github.com/gitubpatrice/READ-FILES-TECH
- **Source code license** : Apache License 2.0

## 4. Data accessed or processed

| Data type                       | Use                                                                                              | Processing location                |
| ------------------------------- | ------------------------------------------------------------------------------------------------ | ---------------------------------- |
| Files chosen by the user        | Reading, display, edition, conversion, sharing on user request.                                   | Mainly local on the device.        |
| Network technical data           | **None** from the app, which holds no network permission since v2.15.3 (see §6 bis). Sharing and email hand the content to the app the user picks, on user action. | App or service chosen by the user. |
| Local preferences               | Recent files, display settings, sort order.                                                       | Local storage on the device.       |

## 5. No advertising, no trackers, no analytics

The developer declares that the application contains no advertising, tracker, audience measurement, behavioural analytics or profiling system. The application does not sell user data.

## 6. Sharing and data transmission

Files or contents are transmitted to a third party only on explicit user action (Share button, export), via voluntary use of a third-party service, or to comply with applicable legal obligations.

### App-specific notes

- HTML/WebView rendering displays user-chosen content; the user must remain cautious with files from untrusted sources. JavaScript is **disabled by default** for HTML files (opt-in via toolbar).
- The application no longer checks for updates on its own (removed in v2.15.3). The "Vérifier les mises à jour" button on the "About" screen opens the releases page in the user's browser; the browser makes that connection, not the application (see §6 bis).

## 6 bis. No connection opened by the app

**Up to v2.15.2**, the application opened one connection on its own: at launch
(at most once every 12 hours, result cached) and on demand from the "About"
screen, a `GET` request to
`https://api.github.com/repos/gitubpatrice/read-files-tech/releases/latest`, to
learn whether a new version existed. It carried no device identifier, no account,
no cookie, no usage data and no file name; GitHub saw the device's public IP
address, as any server one contacts does. A previous version of this document
implied that all network activity was user-triggered; this paragraph was written
to correct that.

**Since v2.15.3, that request no longer exists, and the application holds no
network permission**: `INTERNET` and `ACCESS_NETWORK_STATE` are absent from the
APK (§9). The application connects to nothing.

- **What replaces it**: the "Vérifier les mises à jour" button on the "About"
  screen opens `https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest`
  in the browser. The browser makes that connection, under its own permissions
  and settings — not the application.
- **What this costs**: the application no longer tells the user on its own that a
  new version exists. It is distributed by sideload, outside any app store, so no
  system mechanism does either. To learn about a fix, check from the "About"
  screen or on https://www.files-tech.com.
- **Links opened from the application** (official website, releases page, email)
  are handed to the browser or email app the user picks, which make their own
  connections.

The application itself sends no file, no document content and no vault data
anywhere. Sharing hands a file to the app the user picks, on the user's action
(§6).

## 7. Retention and deletion

Files remain under the user's control. No app-specific account is created.

## 8. Security

The application aims to limit processing to what is necessary and to favour local processing. It implements:

- no network permission: `INTERNET` and `ACCESS_NETWORK_STATE` are removed from the merged manifest, and the CI fails if a network permission, or any permission outside the reviewed list, reappears (§9);
- Network Security Config rejecting cleartext HTTP and user-installed CAs;
- App Sandbox + path validation on native MethodChannels (Kotlin);
- Zip-slip protection on archive extraction;
- File size limits to prevent local DoS.

See [SECURITY.md](./SECURITY.md) for the vulnerability disclosure policy.

## 9. Android permissions

This list is derived from `aapt2 dump permissions` run on the **v2.15.3 release
APK**, not from the source manifest: dependencies can add permissions when the
manifests are merged, and one of the nine below appears nowhere in the
application's own manifest. The APK carries **exactly nine** permissions.

| Permission / access                 | Reason                                                                                                  |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `MANAGE_EXTERNAL_STORAGE`           | File explorer feature: browse, read, edit any file on shared storage selected by the user.              |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) / `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | Same feature, on Android versions predating `MANAGE_EXTERNAL_STORAGE`. |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Display and preview media files chosen by the user (Android 13+).                                  |
| `CAMERA`                            | Document scanner and OCR (optional, runtime-granted on demand).                                          |
| `REQUEST_INSTALL_PACKAGES`          | Install an APK tapped in the explorer. The installation itself is performed by the system package installer, which asks for its own confirmation. |
| `com.readfilestech.read_files_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Internal to the application, added at merge time by the AndroidX library. Declared with the `signature` protection level, it keeps the application's own broadcast receivers private; it gives access to nothing outside the application. |
| `INTERNET`, `ACCESS_NETWORK_STATE`  | **Absent since v2.15.3.** Google ML Kit adds them at merge time, through `com.google.android.datatransport:transport-backend-cct`; the application removes them with `tools:node="remove"`. The CI checks the merged release manifest on every run and in the release workflow before anything is published (`tools/check-manifest-permissions.py`, with a negative control): a network permission, or any permission outside the nine above, fails the build. |

### 9 bis. What "100% local" covers exactly

User files never leave the device: no feature of the application transmits,
uploads or remotely backs them up. Text recognition (OCR) runs entirely offline —
the model is bundled inside the APK (`assets/mlkit-google-ocr-models`,
`libmlkit_google_ocr_pipeline.so`).

Two caveats, stated here because they are verifiable on the APK:

1. **Google ML Kit bundles a telemetry transport, and it is neutralised.** The
   `TransportBackendDiscovery`, `JobInfoSchedulerService` and
   `AlarmManagerSchedulerBroadcastReceiver` components could report library usage
   metrics to Google — never document contents. Since **v2.15** their three
   declarations are stripped from the final manifest with `tools:node="remove"`:
   with no declared entry point, Android can neither bind the service nor deliver
   the broadcast, so the transport never starts. **Measured on the published
   v2.15.1 APK**, not only on the source file: `aapt2 dump xmltree` finds **no**
   `datatransport` component in it (positive control: seven `com.google.mlkit`
   entries are found). What this does not do: the transport's **code** remains in
   the APK — the string appears twice in `classes.dex`. Since **v2.15.3** the
   application also holds no network permission (§9), so even if it started, the
   transport could send nothing.
2. **The document scanner relies on Google Play services.**
   `play-services-mlkit-document-scanner` is a thin client: the scanning UI and
   its model live in Play services, which download them on demand. The scanner
   therefore runs in **another process**, Google Play services, with its own
   permissions, outside the application's control: the absence of a network
   permission described here covers Read Files Tech, not Google Play services. On a
   device without Google Play services the scanner does not work; the rest of the
   application, OCR included, works normally.

> **Correction, v2.15.** This document previously stated that
> `REQUEST_INSTALL_PACKAGES` had been removed in v2.12.2. That was accurate at
> the time, but the permission was **reinstated in v2.13.0** to restore direct
> APK installation from the explorer — as `SECURITY.md` correctly recorded. The
> table above is derived from the published APK.
>
> **Correction to the correction, same version.** An earlier draft of this
> paragraph removed the update check from the `INTERNET` row, on the grounds that
> "the application does not declare `INTERNET` in its manifest". The fact was
> right, the conclusion wrong: Android grants the permissions of the **merged**
> manifest, not of the source manifest. The request to `api.github.com` was
> therefore very much going out, silently relying on a permission contributed by
> a telemetry dependency. "Not requested by the application" was the opposite of
> the truth.
>
> Since v2.15, `INTERNET` and `ACCESS_NETWORK_STATE` are **declared explicitly**
> in the source manifest, with their rationale. A permission the app uses must be
> visible in the file people read.
>
> **Change, v2.15.3.** The "correction to the correction" and the paragraph after
> it describe v2.15 to v2.15.2. In
> v2.15.3 the update check was removed first, then both permissions were removed
> from the merged manifest with `tools:node="remove"` — still visible in the source
> manifest, now as a removal. Removing the lines alone would not have been enough:
> ML Kit would have added the permissions back at merge time. The table above is
> derived from the v2.15.3 release APK.

## 10. Children

The application is not specifically targeted at children and contains no behavioural advertising or profiling.

## 11. Changes

This policy may be updated as the application evolves. The date at the top indicates the current version.

## 12. Contact

📧 **contact@files-tech.com**
