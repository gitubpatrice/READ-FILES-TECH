# Read Files Tech

[![CI](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml/badge.svg)](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/gitubpatrice/READ-FILES-TECH)](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest)
[![Flutter](https://img.shields.io/badge/Flutter-stable-02569B?logo=flutter)](https://flutter.dev)

**The Android swiss-army knife for your files — version 2.15.2.**

File explorer, universal reader, document scanner, OCR, encrypted vault, trash,
conversion, EXIF stripping — your files never leave the device. No cloud, no account.

🇫🇷 Version française : [README.fr.md](./README.fr.md)

## Privacy in one paragraph

Everything runs locally: reading, editing, conversion, OCR, the vault. Nothing is
collected, nothing is profiled, there is no account. Two qualifications, stated
here because they are verifiable on the published APK and hiding them would be
dishonest:

- **The app does reach the network, once.** It queries the public GitHub Releases
  API at launch to tell you a new version exists — the only channel that can warn
  a sideloaded install about a security fix. No identifier is sent.
- **The document scanner needs Google Play Services.** Everything else, OCR
  included, works without them.

**The app carries no telemetry.** Google ML Kit, which provides offline OCR, ships
its own telemetry transport as a transitive dependency. Its three entry points —
`TransportBackendDiscovery`, `JobInfoSchedulerService` and
`AlarmManagerSchedulerBroadcastReceiver` — are stripped from the final manifest
with `tools:node="remove"` as of v2.15. Without a declared entry point, Android
can neither bind the service nor deliver the broadcast, so the transport never
starts.

This is measured on each release rather than assumed, because a dependency bump
can undo it silently. On the 2.15.2 build: **zero `datatransport` components** in
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
| `MANAGE_EXTERNAL_STORAGE` | The point of a universal explorer: browse, read and edit any file you pick. |
| `REQUEST_INSTALL_PACKAGES` | Install a signed APK from the explorer. Installation is never silent — the system PackageInstaller always asks. |
| `CAMERA` | Document scanner and OCR. Optional, requested when first used. |
| `INTERNET` | Update check against the GitHub Releases API. Anonymous, no cookie. |
| `ACCESS_NETWORK_STATE` | Companion to the update check. |

Full rationale: [PRIVACY.md](PRIVACY.md) §9.

## Install

[GitHub Releases — latest](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest) —
signed APK, distributed outside the Play Store.

Each release carries four assets: three ABI splits and one universal APK, named
`read-files-tech-<abi>-<version>.apk`, with their SHA-256 digests in the release
body. The signing certificate is stable across versions, so updates install over
an existing copy.

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
