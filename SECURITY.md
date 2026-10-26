# Security policy — Read Files Tech

🇫🇷 Version française : [SECURITY.fr.md](./SECURITY.fr.md)

## Supported versions

Only the latest version published on GitHub Releases is maintained from a security standpoint.

| Version | Supported |
| ------- | --------- |
| 3.0.x   | ✅ |
| < 3.0   | ❌ — 3.0 installs over 2.x and keeps its data |

## Reporting a vulnerability

Please **do not open a public GitHub issue**. Two channels, either one:

- **GitHub private reporting**: the repository's *Security* tab, then *Report a vulnerability*
  (https://github.com/gitubpatrice/READ-FILES-TECH/security/advisories/new) — seen by the maintainer only;
- 📧 **email**: **contact@files-tech.com**, subject `[SECURITY] Read Files Tech — <short description>`.

Please include a description, the steps to reproduce it, its impact, the affected version (shown in the app
under About), and a suggested fix if you have one.

- Acknowledgement: within 7 days.
- Initial assessment: within 30 days.
- Fix: a critical issue gets a patch within 30 days; a major one, the next minor version; a minor one goes to
  the backlog.

Please do not disclose the vulnerability publicly until a fix has shipped and users have had a reasonable
window to update — typically 30 days after the fix is published.

## Verifying an APK

Every release carries **four** signed APKs — three ABI splits and one universal APK — with their SHA-256
digests in the release notes:

```
read-files-tech-arm64-v8a-<version>.apk
read-files-tech-armeabi-v7a-<version>.apk
read-files-tech-x86_64-<version>.apk
read-files-tech-universel-<version>.apk
```

Check the file you downloaded against the digest published for it; if it does not match, do not install it:

```bash
sha256sum read-files-tech-arm64-v8a-3.0.0.apk
```

Then check the signing certificate, which is what ties a download to this project — whatever its file name:

```bash
apksigner verify --print-certs read-files-tech-arm64-v8a-3.0.0.apk
```

Its SHA-256 has not changed since the 2.x versions: an APK that presents another one did not come from here.

## Scope

In scope: privilege escalation or permission bypass; path traversal, zip-slip, injection through the WebView;
reading or writing outside what the user chose; data of the vault readable without its password; a component
another app can call; an exploitable crash (persistent denial of service); leaking user data off the phone.

Out of scope: UX bugs with no security impact; vulnerabilities in third-party dependencies already reported
upstream; attacks that need a rooted or already compromised phone; physical attacks on an unlocked phone.

## How 3.0 protects what it holds

- **No network.** No `INTERNET` or `ACCESS_NETWORK_STATE` permission (`tools:node="remove"`). The CI reads the
  merged release manifest and the release APK, and fails on any permission outside a reviewed list.
- **Nothing of Google's services.** No ML Kit, Play services, Firebase or telemetry transport: refused by the
  CI in the release classpath and in the manifest. The system WebView is opted out of its metrics, and
  EmojiCompat's start-up initializer, which would ask a downloadable-fonts provider for its font, is removed.
- **What other apps can reach.** The launcher activity (empty task affinity, extras dropped but a whitelisted
  shortcut), the three Quick Settings tiles (system only) and AndroidX's profile installer receiver, which only
  the shell and the system may call (`DUMP` permission); nothing else is exported, which the CI checks exactly.
  The FileProvider is not exported: every file is checked by a path guard, then served read-only to the app the
  user chose — Android keeps that app's access to the file, at that path, while its task lives. Write access
  goes only to PDF Tech, after its signing certificate is checked, and to the camera app, on the empty file of
  the one photo asked for, until it returns.
- **The vault.** AES-256-GCM, the file name bound as associated data; key derived by Argon2id, calibrated on
  the phone, for a vault created since 2.7.1 (an older vault keeps its derivation: PBKDF2 with 600,000
  iterations, or Argon2id 16 MiB × 4); brute-force lockout on two clocks, so neither a reboot nor setting the
  clock back frees it (a reboot followed by setting the clock forward frees one attempt per reboot: nothing
  else keeps time across a reboot); auto-lock in the background, when idle, while the phone sleeps (an alarm)
  and as soon as a screen other than its own opens; each operation works on its own copy of the key and stops
  if the vault was locked meanwhile; screens protected from capture; decrypted copies, PdfBox's scratch
  included, purged at the lock and at every start. The one-time repair of the files 2.15.2 encrypted under a
  zeroed key is carried over.
- **Hostile files.** The HTML viewer loads no network resource and runs no JavaScript (WebViewAssetLoader,
  `blockNetworkLoads`, a restrictive CSP); ZIP extraction refuses names leading outside its folder and bounds
  what is produced, not what is declared; PDFs are parsed in a separate process (`:pdf`) that accepts only the
  app's own calls; the patterns the user types run on RE2/J, in linear time; every viewer has a size cap.
- **Backups.** Android backup and device transfer are turned off for the app's data.

Known limits, stated in the privacy policy as well: the names and sizes of the vault's files are not encrypted;
the editors' history keeps plain copies of edited files; the trash and the outputs are on shared storage;
deleting does not overwrite; panic mode leaves the clipboard and the `Files Tech` folder.

## History

The hardening history of the 2.x line, version by version, is kept with its last release:
[SECURITY.md of v2.15.3](https://github.com/gitubpatrice/READ-FILES-TECH/blob/v2.15.3/SECURITY.md) and its
[French version](https://github.com/gitubpatrice/READ-FILES-TECH/blob/v2.15.3/SECURITY.fr.md).
