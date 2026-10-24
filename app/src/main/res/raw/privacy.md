# Privacy policy — Read Files Tech

_Last updated: 24 October 2026 (version 3.0.0)_

## In one sentence

Read Files Tech collects, sends and keeps no data on any server: the app has no permission to reach the Internet, and your files leave your phone only when you hand them to another app yourself.

## Who is responsible

Read Files Tech is published by Patrice Haltaya (Files Tech), as an individual. Contact: **contact@files-tech.com**. The source code is public: https://github.com/gitubpatrice/READ-FILES-TECH.

The developer receives no data from the app, so there is nothing about you for him to keep, look at or erase. Only an e-mail you choose to write to him reaches him — "Report a bug" adds the app's and Android's versions to it. That e-mail is used only to answer you; it is kept for the time of the exchange, then erased. You can ask for access to it or its erasure at the same address, and complain to the CNIL, the French data protection authority (www.cnil.fr), if you think your rights are not respected.

## What the app does not do

- No advertising, no trackers, no audience measurement, no crash reports sent.
- No account, no sign-up.
- **No Internet permission**: `INTERNET` and `ACCESS_NETWORK_STATE` are removed from the app's manifest, and its build refuses any version that would carry one. The app cannot reach any server.
- **No Google services**: no Google Play services, no ML Kit, no Firebase. Text recognition (OCR) runs on the phone, with Tesseract. The WebView that shows HTML pages is set not to send usage statistics to its maker, and the app does not ask an outside service for its emoji font.
- The app does not list your apps. It only checks whether four apps are installed, to offer to send them a file: PDF Tech, kDrive, Proton Drive and Google Drive.

## What the app reads, and why

Read Files Tech is a file explorer and reader. With your consent — "All files access" from Android 11 on, the storage permission before — it can read and change every file of the phone's shared storage, SD card included. It does so only for what you ask: browsing a folder, opening a file, converting it, searching, finding duplicates… It never hands a file of other apps' `Android/data` or `Android/obb` folders to another app.

## What is kept on your phone

- **Recent files (path, name, size, date, favorite), files removed from that list, theme, output folder, automatic share** — the app's private preferences, in clear.
- **Vault settings (salt, key derivation parameters), failure counters** — the app's private preferences, in clear; no password.
- **Editors' history: the last 10 versions of each file changed in the code editor or the CSV editor, with its path** — the app's private storage, **in clear**.
- **Vault files** — the app's private storage, encrypted (see below).
- **OCR models** — the app's private storage, shipped with the app.
- **Temporary copies: a vault file opened or shared, an unlocked protected PDF, a shared archive entry, the scanner's photos** — the app's private cache, in clear; deleted once no longer needed or, at the latest, at the next start.
- **Files the tools create: scans, conversions, compressions, images without EXIF, OCR texts, vault backups, extractions** — the `Files Tech` folder of shared storage, or the one you choose in Settings, in clear, except the vault backups.
- **Trash: deleted files and their description (name, original path, size, date)** — the hidden `.RFT_Corbeille` folder at the root of each volume, in clear; **never emptied automatically**.

Files on shared storage — the `Files Tech` folder, the trash — can be read by any other app you gave access to your files.

If the folder of the files the tools create refuses writes ("All files access" refused, card removed), the app writes to its own folder on shared storage (`Android/data/…/files/Files Tech`), then to its private storage; Settings says so, and so does the dialog of a vault backup. Those folders are erased with the app, and panic mode does not erase what they hold.

## The vault

- Each file is encrypted with **AES-256-GCM**, with a key derived from your password by **Argon2id**. A vault created with an older version keeps the derivation it was created with (PBKDF2, or a lighter Argon2id). The password is never stored.
- **Not encrypted**: the **names** of the vault's files, their **sizes**, their number and dates, nor the key derivation settings.
- The vault locks 30 seconds after you leave the app — the phone in deep sleep, when it wakes, and always before the vault shows again —, after 3 minutes without touching the screen, and when you leave its screen. After 5 wrong passwords, each attempt waits longer, up to 30 minutes.
- The vault's screens, and the vault files opened in the app, cannot be captured and do not appear in the recent apps' preview.
- A `.rftvault` **backup** is encrypted whole, names included, under a separate backup password; it is written to the vault backups' folder, which the backup's dialog shows first: on shared storage or, when the output folder refuses writes, in a folder of the app, erased with it.
- **Share (decrypted)** makes a temporary plain copy, deleted at the lock and at the next start. **Export** writes a permanent plain copy to the folder you choose. What the app receiving a file does with it no longer depends on Read Files Tech.

## When something leaves your phone

Always on an action of yours, and to the app you choose:

- **Share**, **Open with**: the file is handed to the chosen app, read-only, for that time.
- **Send** to kDrive, Google Drive or Proton Drive, if installed.
- **Edit in PDF Tech**: write access is given only if the installed app carries PDF Tech's signature.
- **Share automatically after creation**, if you turn this setting on (off by default).
- **Taking a photo** for the scanner or the OCR: the phone's camera app writes the photo into a file Read Files Tech opens to it for that only.
- **Links** — the releases page, the website, e-mail —: opened in your browser or your e-mail app, which connect by themselves. "Report a bug" prepares an e-mail you read and send yourself.
- **A document's links**: a link you touch in an HTML page or a Markdown file opens in your browser, which connects to the site by itself.

The HTML viewer shows a page without JavaScript and without any connection: only the files of the page's folder are loaded.

## Panic mode

From Settings, after a confirmation, panic mode erases at once: the vault and its settings, every preference (recent files included), the whole cache, the editors' history, the old extractions and **the trash** on every volume. It does not erase: your files, the `Files Tech` folder (vault backups included), the files exported from the vault, the copies already handed to other apps, nor the clipboard. Erasing deletes the files without overwriting their content.

## Android backup

Android's backup — to the cloud or from one phone to another — is turned off for the app's data. Files on shared storage are not part of it: a tool that copies your storage can take them along.

## Android permissions

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (Android 11 and later) | Access to all files: the explorer |
| `READ_EXTERNAL_STORAGE` (up to Android 12), `WRITE_EXTERNAL_STORAGE` (up to Android 10) | The same on older versions |
| `REQUEST_INSTALL_PACKAGES` | Handing an APK tapped in the explorer to Android's installer, which asks its own confirmation |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by an Android library, internal to the app |

No camera, location, contacts, microphone, notifications or Internet.

## System log

When an operation fails, the app writes one line to the system log: the type of the error, with no file name and no content. The library that reads PDFs may add its own warnings, and Android records a crash of the app there; the app sends this log nowhere.

## Data of earlier versions

Version 3.0.0 takes over the data of the 2.x versions in place — preferences, vault, trash, history —: nothing is sent or copied elsewhere, except the old extractions, which the app offers once to move into the `Extractions` folder of the files you create, or to erase: nothing is moved or erased without your consent.

## Your rights

Your data are on your phone, under your control: you can erase them from the app (files, trash, vault, panic mode) or by uninstalling it. Uninstalling erases the app's private data, but not the `Files Tech` folder nor the trash, which are on shared storage. For any question: **contact@files-tech.com**.

## Children

The app is not aimed at children in particular; it holds no advertising and no profiling.

## Changes

This policy may change with the app; the date at the top gives its version.

## Language

This policy was written in French; the versions in other languages are translations. Should they differ, **the French version prevails**.

---

Read Files Tech is part of the **Files Tech** suite, published by **Patrice Haltaya**.
