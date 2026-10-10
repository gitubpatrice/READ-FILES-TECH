# Read Files Tech

[![CI](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml/badge.svg)](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/gitubpatrice/READ-FILES-TECH)](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest)
[![Flutter](https://img.shields.io/badge/Flutter-stable-02569B?logo=flutter)](https://flutter.dev)

**The Android swiss-army knife for your files — version 2.15.3.**

File explorer, universal reader, document scanner, OCR, encrypted vault, trash,
conversion, EXIF stripping — your files never leave the device. No cloud, no account.

🇫🇷 Version française : [README.fr.md](./README.fr.md)

## Privacy in one paragraph

Everything runs locally: reading, editing, conversion, OCR, the vault. Nothing is
collected, nothing is profiled, there is no account.

**No internet permission**, since v2.15.3 — like the other Files Tech apps. The
app connects to nothing. `INTERNET` and `ACCESS_NETWORK_STATE`, which Google ML
Kit adds when the manifests are merged, are removed with `tools:node="remove"`.
The CI verifies it: it reads the merged release manifest on every run and in the
release workflow, before anything is published, and fails if a
network permission — or any permission outside the reviewed list — appears
(`tools/check-manifest-permissions.py`, with a negative control).

Two consequences, stated here because hiding them would be dishonest:

- **The app no longer checks for updates on its own.** Up to v2.15.2 it queried
  the GitHub Releases API at launch; that request is gone. The "Vérifier les mises
  à jour" button on the About screen now opens the releases page in your browser
  — the browser connects, not the app. To learn about a security fix, check from
  there or on [files-tech.com](https://www.files-tech.com/read-files-tech.php).
- **The document scanner needs Google Play services.** It runs inside them — a
  separate process, with its own permissions, outside the app's control.
  Everything else, OCR included, works without them: the OCR model is bundled in
  the APK.

**The app itself sends no telemetry.** Google ML Kit, which provides offline OCR,
ships its own telemetry transport as a transitive dependency. Its three entry
points — `TransportBackendDiscovery`, `JobInfoSchedulerService` and
`AlarmManagerSchedulerBroadcastReceiver` — are stripped from the final manifest
with `tools:node="remove"` as of v2.15. Without a declared entry point, Android
can neither bind the service nor deliver the broadcast, so the transport never
starts; its code stays in the APK, and without a network permission it could send
nothing anyway. This does not extend to Google Play services, which run the
document scanner under their own rules.

This is measured on each release rather than assumed, because a dependency bump
can undo it silently. On the 2.15.3 build: **zero `datatransport` components** in
the merged manifest and in the APK, against a positive control of seven
`com.google.mlkit` entries — the check would be worthless if it passed on an APK
that had lost ML Kit too.

Full detail in [PRIVACY.md](PRIVACY.md) §6 bis and §9 bis, and [TERMS.md](TERMS.md).

## Features

- **File explorer** — browsing, search, multi-selection, bulk copy/move/rename, picker with smart filtering.
- **Universal reader** — PDF, CSV, XLSX, DOCX, JSON, MD, TXT, HTML, ZIP, images, EPUB (plus ODT, ODS, JS, CSS, PHP, XML).
- **Document scanner** — camera, edge detection, perspective correction, PDF export.
- **Latin OCR** on images, fully on-device (ML Kit).
- **Encrypted vault** (`.rftvault v2 AAD`) — Argon2id + AES-256-GCM, authenticated data bound to the file name, `FLAG_SECURE`, brute-force rate limiting, whole-folder encryption.
- **PDF signing** with your finger.
- **Conversion** — images → PDF, CSV ↔ XLSX, JPG ↔ PNG, TXT/MD → PDF, and more.
- **EXIF stripping** — removes GPS, timestamp and device model before sharing.
- **Global search** by name and by content, off the UI thread.
- **SHA-256 duplicate finder** — three passes, to reclaim storage.
- **Cloud sharing** — explicit hand-off to installed cloud apps (kDrive, Google Drive, Proton Drive) through the Android share sheet, on your action.
- **Quick Settings tiles** — scanner, OCR and vault from the notification shade.
- **APK installation** from the explorer — tapping a `.apk` hands it to the system PackageInstaller.

## What changed, and where to read it

Release notes are **not duplicated here**, deliberately: this file used to carry
its own "what's new" sections and they were still describing v2.12 while the app
shipped 2.15. One source, kept current:

- [Releases](https://github.com/gitubpatrice/READ-FILES-TECH/releases) — every version, with its four signed APKs and their SHA-256 digests
- [`fastlane/metadata/android/en-US/changelogs/`](fastlane/metadata/android/en-US/changelogs/) — the same notes, per versionCode, in English and French

## Security

- **Vault** — Argon2id + AES-256-GCM with authenticated data bound to the file name, auto-tuned derivation, sealed metadata.
- **`safeCanonical` + root whitelist on the Kotlin side** — path traversal and out-of-sandbox access are blocked on every native MethodChannel.
- **HTML viewer `file://` scoping** — strict WebView context isolation, JavaScript off by default.
- **Strict Network Security Config** — no cleartext HTTP, no user-installed certificate authorities.
- **Restrictive FileProvider** — shared paths are exposed deliberately, never wholesale.
- **Zip-slip protection** on archive extraction.

See [SECURITY.md](SECURITY.md) for the reporting policy and the per-version history.

## Android permissions

| Permission | Why it is needed |
| --- | --- |
The published APK carries exactly nine permissions, measured with `aapt2 dump
permissions` on the 2.15.3 release build:

| Permission | Why it is needed |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | The point of a universal explorer: browse, read and edit any file you pick. |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) / `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | The same, on Android versions that predate `MANAGE_EXTERNAL_STORAGE`. |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Display and preview the media files you pick (Android 13+). |
| `REQUEST_INSTALL_PACKAGES` | Install a signed APK from the explorer. Installation is never silent — the system PackageInstaller always asks. |
| `CAMERA` | Document scanner and OCR. Optional, requested when first used. |
| `com.readfilestech.read_files_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Internal, added by AndroidX: `signature` level, keeps the app's own broadcast receivers private. |

**Not in the APK:** `INTERNET` and `ACCESS_NETWORK_STATE`, removed since v2.15.3
(see above).

Full rationale: [PRIVACY.md](PRIVACY.md) §9.

## Install

[GitHub Releases — latest](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest) —
signed APK, distributed outside the Play Store.

Each release carries four assets: three ABI splits and one universal APK, named
`read-files-tech-<abi>-<version>.apk`, with their SHA-256 digests in the release
body. The signing certificate is stable across versions, so updates install over
an existing copy.

The app does not look for updates itself, since it has no network permission.
Watch the releases page, or use the "Vérifier les mises à jour" button on the
About screen, which opens that page in your browser.

Official site: [files-tech.com/read-files-tech](https://www.files-tech.com/read-files-tech.php)

## Build from source

```bash
git clone https://github.com/gitubpatrice/READ-FILES-TECH.git read_files_tech
cd read_files_tech
flutter pub get
flutter build apk --release
```

`files_tech_core` is pinned to a commit in `pubspec.yaml` and fetched by
`flutter pub get`; it does not need to be cloned alongside. Requires Flutter
stable, the Android SDK and JDK 17.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Copyright 2026 Patrice Haltaya.
