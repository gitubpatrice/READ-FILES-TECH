# Security policy — Read Files Tech

🇫🇷 Version française, avec l'historique détaillé des durcissements :
[SECURITY.fr.md](./SECURITY.fr.md)

## Supported versions

Only the latest version published on GitHub Releases is actively maintained from
a security standpoint.

| Version  | Supported |
| -------- | --------- |
| 2.15.x   | ✅        |
| < 2.15.0 | ❌        |

## Reporting a vulnerability

If you find a security vulnerability in Read Files Tech, **please do not open a
public GitHub issue**. Instead:

📧 **Email contact@files-tech.com**

Subject line: `[SECURITY] Read Files Tech — <short description>`.

Please include:

- A clear description of the vulnerability
- Steps to reproduce it
- The potential impact
- The affected version (shown on the app's "About" screen)
- A suggested fix, if you have one

## Response times

- Acknowledgement: within 7 days
- Initial assessment: within 30 days
- Fix: depending on severity — critical means a patch within 30 days, major means
  the next minor version, minor goes to the backlog

## Responsible disclosure

Please do not disclose the vulnerability publicly until a fix has shipped and
users have had a reasonable window to update — typically 30 days after the fix
is published.

## Verifying an APK

Every GitHub release carries **four** signed assets: three ABI splits and one
universal APK, with their SHA-256 digests in the release body.

```
read-files-tech-arm64-v8a-<version>.apk
read-files-tech-armeabi-v7a-<version>.apk
read-files-tech-x86_64-<version>.apk
read-files-tech-universel-<version>.apk
```

Check the file you downloaded against the digest published for it:

```bash
sha256sum read-files-tech-arm64-v8a-2.15.3.apk
```

The value must match exactly. If it does not, do not install the APK.

You can also check that the signing certificate has not changed, which is what
actually ties a download to this project:

```bash
apksigner verify --print-certs read-files-tech-arm64-v8a-2.15.3.apk
```

The certificate SHA-256 has been stable across versions, so a build that presents
a different one did not come from here — regardless of what its file name says.

> Note: releases up to and including v2.15.1 used the earlier asset naming,
> `read_files_tech-v<version>-<abi>.apk`. The names above apply from v2.15.2 on.

## Scope

In scope:

- Privilege escalation, permission bypass
- Path traversal, zip-slip, injection through the WebView or a MethodChannel
- Exploitable crash (persistent denial of service)
- Arbitrary read or write outside the app sandbox
- Leakage of user data

Out of scope:

- UX bugs with no security impact
- Vulnerabilities in third-party dependencies already reported upstream
- Attacks that require a rooted or already compromised device
- Physical attacks on an unlocked device

## Hardening history

Summarised per version. The full account, with the reasoning behind each fix, is
in [SECURITY.fr.md](./SECURITY.fr.md).

- **v2.15.3** (2026-10-10) — Two vault fixes, and no network permission any more.
  (D1) A vault lock that landed *during* an import or a restore (leaving the vault
  screen, 30 s in the background, 3 min of inactivity) let the operation finish
  without any error using a key of 32 zero bytes: the file could not be opened
  with the real password, could be decrypted by anyone, and a folder import could
  then delete the original. Each operation now works on its own copy of the key
  and stops before writing if the vault was locked in the meantime: an
  interrupted import writes nothing (so a folder import does not delete the
  original), an interrupted restore stops, and nothing is encrypted under a
  zeroed key. A write already under way when the lock lands completes, with a
  valid key, so its file stays readable; a decryption to the cache, which writes
  plaintext, checks again and erases it. Resetting the vault now locks it before
  anything else, so an import in flight cannot land in the new vault.
  (D2) After the phone rebooted, the brute-force lockout (5 failures) could last
  about as long as the previous uptime — days — correct password included,
  because the "monotonic" deadline came from the previous boot. A deadline that
  cannot belong to the current boot is now ignored, for unlocking and for backup
  restore alike; what is no longer covered is rebooting *and* moving the wall
  clock forward, at the cost of one reboot per attempt. Both reproduced on
  2026-10-10 on an emulator with 2.15.2, both covered by tests; no known
  exploitation. Also: the automatic update check
  (GitHub Releases API, at launch and from the About screen) is removed — the
  About screen opens the releases page in the browser — and `INTERNET` and
  `ACCESS_NETWORK_STATE` are removed from the merged manifest. The CI checks the
  merged release manifest against a reviewed list of nine permissions, with a
  negative control.
- **v2.15.2** (2026-09-20) — Dependency chain unlocked at its source. `share_plus`
  13, `package_info_plus` 10, `file_picker` 13, Syncfusion 34.2.8, ML Kit 0.17.1.
  Two version ceilings documented against an AGP 8 packaging defect. PDF signing
  no longer saves from an already-released document. Verified on the build: zero
  `datatransport` components, seven `com.google.mlkit` manifest entries.
- **v2.15.1** (2026-08-11) — Update check: the timeout did not cover reading the
  response, so a server that answered and then went silent could hang the app.
  Oversized responses are refused before being loaded into memory; empty
  redirects are no longer followed.
- **v2.15.0** (2026-08-09) — Consolidation across the 23 points of the 2026-08-02
  audit plan. The zip-bomb guard relied on the size *declared in the entry
  header*, so an 80 KB archive claiming 1 KB and inflating to 80 MB passed it, on
  all four sites using it, since v2.12.0; decompression now runs through a capped
  stream. Vault: `reset()` left lockout counters behind, `decryptToTemp` wrote to
  a shared folder, `exportFile` overwrote silently. HTML viewer: the injected CSP
  could be bypassed two different ways.
- **v2.14.0** (2026-07-19) — Trash, and snackbar reliability.
- **v2.13.2** (2026-05-20) — Expert audit after v2.13.1, three axes in parallel.
- **v2.13.1** (2026-05-19) — CI hotfix: removed the `splits.abi {}` block.
- **v2.13.0** (2026-05-13) — Expert audit after v2.12.3: 24 fixes.
- **v2.12.2** (2026-05-13) — Google Play Protect hotfix.
- **v2.12.1** (2026-05-12) — Zero-vulnerability audit, G1-G16.
- **v2.12.0** (2026-05-09) — F1-F19: viewer caps (txt, docx, xlsx, epub).
