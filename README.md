# Read Files Tech

[![CI](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml/badge.svg)](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/gitubpatrice/READ-FILES-TECH)](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest)

**Reader, editor and explorer of files for Android — your files never leave the phone.**

File explorer, universal viewer, editors, document scanner, OCR, encrypted vault, trash, conversions,
EXIF removal. No account, no cloud, no Internet permission. In English, French, German, Italian and Spanish.

🇫🇷 Version française : [README.fr.md](./README.fr.md)

Version 3.0 is a rewrite of the app in Kotlin with Jetpack Compose. It takes over the data of the 2.x
versions in place — vault, trash, settings, recent files — and installs over them.

## Privacy in one paragraph

Everything runs on the phone: reading, editing, conversions, OCR, the vault. Nothing is collected, nothing is
profiled, there is no account. **The app has no Internet permission**: `INTERNET` and `ACCESS_NETWORK_STATE`
are removed from the merged manifest, and the CI fails on any permission outside a reviewed list, read from
the merged release manifest and from the release APK itself. **Nothing of Google's services** either: the OCR
is Tesseract, on the phone; the CI refuses ML Kit, Play services, Firebase and their telemetry transport in
the release classpath and in the manifest; the system WebView is opted out of its metrics and the app does
not fetch the emoji font from a fonts provider. A file leaves the phone only when you hand it to another app
— share, "Open with", a cloud app, PDF Tech.

What the app keeps, where, and what is or is not encrypted: the [privacy policy](PRIVACY.md), shown in the
app under About.

## Features

- **Explorer** — browse, search by name, sort, multi-select, copy, move, rename (one or many: numbering,
  prefix and suffix, regular expressions), trash with undo, info, APK installation through Android's installer.
- **Viewers** — text and code (syntax colors), Markdown, JSON (tree), HTML (sandboxed: no JavaScript, no
  connection), CSV and TSV, spreadsheets (XLSX, XLS, ODS), documents (DOCX, ODT, ODP), EPUB and a reading
  mode, PDF (password, outline, go to page), ZIP (extract all), images (gallery, zoom, EXIF).
- **Editors** — code and text with an automatic history of versions, CSV as an editable table.
- **Encrypted vault** — AES-256-GCM, key derived by Argon2id, auto-lock, brute-force lockout, screens
  protected from capture, encrypted `.rftvault` backups, folder import, panic mode.
- **Scanner** — photos taken with the camera app or picked, edges detected, perspective straightened, PDF.
- **OCR** — Tesseract on the phone, in English, French, German, Italian and Spanish.
- **Conversions** — images → PDF, CSV → XLSX, text → PDF, PDF and DOCX → text, images to JPG or PNG.
- **Tools** — compression, EXIF removal, duplicates and largest files (SHA-256), search in contents and global
  search, compare, file hash, encoding, format, color picker, ZIP creation, text and CSV tools.
- **Quick Settings tiles** — scanner, OCR and vault from the notification shade.
- **Sending** — on your action, to kDrive, Google Drive or Proton Drive when installed, and "Edit in PDF Tech".

## Android permissions

| Permission | Why |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` (Android 11+) | A file explorer: browse, read and edit the files you pick, anywhere on shared storage. |
| `READ_EXTERNAL_STORAGE` (≤ Android 12), `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | The same on older Android versions. |
| `REQUEST_INSTALL_PACKAGES` | Hand an APK tapped in the explorer to Android's installer, which always asks for its own confirmation. |
| `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Internal, added by AndroidX at the `signature` level. |

No camera permission (photos are taken by the camera app), no media, location, contacts, microphone or
notification permission, and no network permission.

## Install

From [GitHub Releases](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest), outside the Play
Store. Each release carries three ABI splits and one universal APK, with their SHA-256 digests; the signing
certificate is the one of the 2.x versions, so 3.0 installs over them and keeps their data. How to check a
download: [SECURITY.md](SECURITY.md#verifying-an-apk).

The app never looks for updates by itself: "Check for updates", in About, opens the releases page in your
browser. Official site: [files-tech.com](https://www.files-tech.com/read-files-tech.php).

## Build from source

```bash
git clone https://github.com/gitubpatrice/READ-FILES-TECH.git
cd READ-FILES-TECH
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 37); Gradle and every dependency are pinned in the repository.
By default the build installs **next to** the published app (package suffix `.next`, its own folders), so it
never touches your data; `-Prft.replaceFlutter=true` builds the real package, whose release build refuses to
run without the release key.

The CI gate runs the build, the unit tests, lint, detekt and the release build (R8), then checks the
release APKs (package, versionCode, languages), the permissions and the shape of the manifest, the absence of
Google services and the translations — each guard with a negative control that proves it fails when it should.

## Translations

English is the source; French, German, Italian and Spanish are complete. Adding a language:
[TRANSLATING.md](TRANSLATING.md).

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). The third-party software the app includes, with its notices and
licences, is listed in [NOTICE](NOTICE), shown in the app under About › Third-party licences.

Terms of use: [TERMS.md](TERMS.md). Reporting a vulnerability: [SECURITY.md](SECURITY.md).

Copyright 2026 Patrice Haltaya — Files Tech.
