# Politique de sécurité — Read Files Tech

## Historique des durcissements

- **v2.12.2** (2026-05-13) — Hotfix Google Play Protect : retrait de
  `REQUEST_INSTALL_PACKAGES` de l'AndroidManifest. La combinaison de
  cette permission avec `MANAGE_EXTERNAL_STORAGE` était classée par
  Play Protect comme signature potentielle de "dropper" (faux positif
  "application dangereuse" sur installation sideload). Le tap sur un
  fichier `.apk` dans l'explorateur reste possible via le gestionnaire
  de Fichiers système Android, qui prend le relais avec sa propre
  permission. Branche Kotlin spéciale `package-archive` retirée dans
  `MainActivity.kt`. Aucun changement de format.
- **v2.12.1** (2026-05-12) — Audit expert zéro-vuln/zéro-faille G1-G16 +
  H1-H8 :
  - **Vault** : wipe per-entry pendant restore `.rftvault` (G2, anti
    fenêtre RAM plaintext étendue), `sublistView` zéro-copie sur
    `_decryptAuto` (G6), ordre `_v2OnlyCache` lu AVANT déchiffrement
    sentinelle (G7, défense en profondeur substitution v1).
  - **Anti-screenshot** : `SecureWindow` passe d'un bool à un refcount (G4)
    pour gérer les écrans sensibles imbriqués (vignette Recents fuite
    évitée).
  - **HTML viewer** : whitelist d'extensions sur navigation `file://`
    cantonnée au dossier d'origine (G8).
  - **CSV** : nouveau helper `CsvSafe` anti CSV-injection (`= + - @ \t \r`
    préfixés `'`, H1) adopté dans csv_editor + merge ; cap source + cap
    cumulatif merge (H2, anti OOM).
  - **Atomic write** : étendu aux éditeurs csv/code (G1, plus de
    troncature sur kill OS pendant save).
  - Dead code retiré : `vault.importFile` non-Safe (zéro caller),
    `lib/utils/run_busy.dart` (H4), règles ProGuard orphelines
    `flutter_secure_storage` + `local_auth`/`biometric` (H8),
    `_ColorInfo` dupliqué dans html_viewer (factorisé via `color_extract`).
  - Déduplication : `AppConstants.autoLockDelay` partagé main.dart /
    vault_screen.dart.
  - `dart analyze` 0 issue, 15/15 tests.
- **v2.12.0** (2026-05-09) — F1-F19 : caps viewers (txt/docx/xlsx/epub/
  csv), ImageBounds anti-bomb, purge cache au boot + paused, race guards
  Argon2id, atomic write 13 sites, vault v2-only flag, auto-lock GLOBAL
  Stopwatch monotonique, PanicService Settings, SecureWindow signatures,
  splits ABI + resourceConfigs FR/EN.

## Versions supportées

Seule la dernière version publiée sur GitHub Releases est activement maintenue côté sécurité.

| Version       | Supportée  |
| ------------- | ---------- |
| 2.12.x        | ✅          |
| < 2.12.0      | ❌          |

## Signaler une vulnérabilité

Si vous découvrez une vulnérabilité de sécurité dans Read Files Tech, **merci de ne PAS ouvrir d'issue publique sur GitHub**. À la place :

📧 **Envoyez un email à : contact@files-tech.com**

Indiquez dans le sujet : `[SECURITY] Read Files Tech — <description courte>`.

Merci d'inclure :

- Une description claire de la vulnérabilité
- Les étapes pour la reproduire
- L'impact potentiel
- La version affectée (visible dans l'écran « À propos » de l'app)
- Si possible, une suggestion de correctif

## Délai de réponse

- Accusé de réception : sous 7 jours
- Évaluation initiale : sous 30 jours
- Correctif : selon la criticité (critique → patch sous 30 jours, majeur → version mineure suivante, mineur → backlog)

## Divulgation responsable

Merci de ne pas divulguer publiquement la vulnérabilité avant qu'un correctif ne soit publié et qu'un délai raisonnable de mise à jour ait été laissé aux utilisateurs (typiquement 30 jours après la publication du correctif).

## Vérification de l'intégrité d'un APK

Chaque release publiée sur GitHub contient les hashs SHA-256 attendus pour les APK splits ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) dans les notes. Avant install, vous pouvez vérifier :

```bash
sha256sum app-arm64-v8a-release.apk
```

Le résultat doit correspondre exactement à la valeur publiée. Sinon, ne pas installer l'APK.

## Périmètre

Vulnérabilités acceptées :

- Élévation de privilèges, contournement d'autorisations
- Path traversal, zip-slip, injection via WebView ou MethodChannels
- Crash exploitable (DoS persistant)
- Lecture/écriture arbitraire hors du sandbox de l'app
- Fuite de données utilisateur

Hors périmètre :

- Bugs UX sans impact sécurité
- Vulnérabilités dans des dépendances tierces déjà reportées en amont
- Attaques nécessitant un appareil rooté/compromis
- Attaques physiques sur l'appareil déverrouillé
