# Politique de sécurité — Read Files Tech

## Historique des durcissements

- **v2.13.2** (2026-05-20) — Audit expert post-v2.13.1 (3 axes en parallèle
  + audit cohérence transversal) : 17 corrections (4 Haute + 9 Moyenne +
  2 Basse + 2 Info).
  - **Build** : retrait du flag `splits.abi {}` redondant (déjà fait en
    v2.13.1 hotfix CI). Documentation : retour intentionnel de
    `REQUEST_INSTALL_PACKAGES` en v2.13.0 (tap APK direct depuis
    l'explorateur, trade-off Play Protect explicite — cf. commentaire
    AndroidManifest.xml).
  - **Sécurité** : (S1) garde explicite `uri.scheme != 'https'` côté UI
    avant `launchUrl` de l'apkUrl (defense in depth doublant la validation
    de `UpdateService`). (S3) `confirmDelete()` aligné sur le pattern
    destructif Files Tech : autofocus Cancel + `FilledButton` avec
    `cs.errorContainer`. (S4/S5) Anti-ReDoS : cap 200 chars sur les regex
    utilisateur avant compilation (content search + bulk rename mode
    regex), refus silencieux des patterns trop longs.
  - **UI / a11y** : (U1/P1) `snackBarTheme: SnackBarBehavior.floating`
    déclaré dans les 2 ThemeData globaux. (U2) `Semantics(liveRegion)` +
    label dynamique sur `LinearProgressIndicator` OCR (TalkBack annonce
    le démarrage/fin). (U3) tokens M3 sur `Colors.orange/blue` editors +
    settings. (U4) `HapticFeedback.selectionClick` sur les 8 sites
    `Clipboard.setData` (copy OCR/hash/color/encode/format/path).
    (Q2) `Colors.red` → `cs.error` dans `reader_viewer_screen`.
  - **Cohérence** : 30+ snackbars inline migrés vers
    `showFloatingSnack`/`showErrorSnack` helpers canoniques (12 fichiers).
    20+ `Colors.red` hardcodés sémantiques → `cs.error`/`cs.errorContainer`.
    Caps `_maxZipBytes`/`_maxHtmlBytes` migrés dans `FileCaps`.
  - **Tests** : nouveau `test/audit_v2_13_2_test.dart` (garde-régression
    sur cap regex utilisateur, dual-clock lockout, FileCaps zipViewer/
    htmlViewer).

- **v2.13.1** (2026-05-19) — Hotfix CI : retrait du bloc `splits.abi {}`
  redondant dans `android/app/build.gradle.kts` (conflit avec
  `ndk.abiFilters` posé par Flutter 3.41+ : `Conflicting configuration
  ... in ndk abiFilters cannot be present when splits abi filters are
  set`). Aucun impact sécurité — purement build CI.

- **v2.13.0** (2026-05-13) — Audit expert post-v2.12.3 : 24 corrections
  (F1-F9 sécu + F12 + F15 + U3-U8 + P1.3-P2.4 perf/UX + tests garde).
  **Note REQUEST_INSTALL_PACKAGES** : permission ré-introduite après
  retrait v2.12.2 pour restaurer la fonctionnalité "tap APK direct
  depuis l'explorateur Read Files Tech" (UX file manager complet).
  Trade-off Play Protect documenté dans `AndroidManifest.xml` ligne
  27-38. Public cible Files Tech = sideload + F-Droid, risque accepté.

  **Sécurité** :
  - **F1** — Les sauvegardes `.rftvault` exportées étaient écrites à la
    racine de `cache/` ; si l'utilisateur annulait la share-sheet, le
    fichier (coffre entier re-chiffré sous `exportPassword`) restait
    indéfiniment. Désormais écrit dans `cache/exports/` dédié, purgé par
    `VaultService.purgeTempDecrypted()` (boot + paused) et par le mode
    panique.
  - **F2** — `restoreFromBackup` n'avait aucun lockout brute-force ; un
    `.rftvault` volé pouvait être attaqué sans friction côté app (Argon2id
    ~2.5 s/essai dans l'app, mais offline 100-1000× plus rapide).
    Désormais : compteur `vault_backup_fails` + backoff exponentiel
    symétrique au unlock principal (1, 2, 4, 8, 16, 30 min après 5 échecs).
  - **F3** — Le lockout brute-force utilisait `DateTime.now()` wall-clock,
    contournable par Réglages → Date/heure (ou `adb shell date -s`).
    Passage à **dual-clock** : `max(wall, elapsedRealtime)` via channel
    Kotlin `SystemClock.elapsedRealtime`. La deadline monotone est
    incassable sans reboot complet ; appliqué à l'unlock principal **et**
    au restore backup.
  - **F4** — `importFileSafe` chargeait la source via `readAsBytes()` sans
    cap → un utilisateur tentant d'importer une vidéo 4 Go crashait l'app
    (OOM, Redmi 9C 3 Go). Cap `FileCaps.vaultBackup` (100 Mo) ajouté.
  - **F5** — `MainActivity.safeCanonical` autorisait `cacheDir.canonicalFile`
    sans distinction. Or `cache/vault_decrypt/` et `cache/share/`
    contiennent du plaintext déchiffré du coffre. Un code Dart compromis
    aurait pu demander à Kotlin d'envoyer ces fichiers via FileProvider
    à n'importe quelle app tierce (`sendToPackage` / `openFile` chooser).
    **Blocklist explicite** ajoutée côté Kotlin.
  - **F6** — `PanicService` ne purgeait pas les artefacts dérivés laissés
    à la racine de `cache/` (signatures `_signe.pdf`, EXIF `_no_exif.jpg`,
    extractions OCR, `.rftvault` orphelins) ni le dossier
    `<docs>/history/` (auto-sauvegardes de l'éditeur de code, potentielles
    PII). Étape 5 ajoutée avec patterns connus de l'app + suppression
    `history/`. Le `PanicReport.tempPurged` rend la couverture explicite.
  - **F7** — L'écran de création du coffre affichait *« PBKDF2 600 000
    itérations »* alors qu'Argon2id auto-calibré est utilisé depuis
    v2.7.1. Texte corrigé pour cohérence avec SECURITY.md et le code.
  - **F8** — `ExifService.inspect()` (preview "métadonnées avant") faisait
    `decodeImage` sans cap, alors que `stripExif` était protégé depuis
    v2.12.0. Vecteur image-bomb identique sur le chemin preview.
    `FileCaps.imageFile` + `ImageBounds.assertSafeBounds` ajoutés.
  - **F9** — `OutputStorageService.setBasePath` acceptait n'importe quel
    `/Android/data/<autre-pkg>/` via le test `path.contains('/Android/data/')`.
    Désormais : refus explicite des paths `/Android/data/` ne ciblant pas
    notre package, et whitelist serrée des dossiers app privés.
  - **F12** — `PdfSignatureService` ne documentait pas que la signature
    est purement graphique. Docstring légal eIDAS ajouté + bandeau info
    visible dans `signature_capture_screen.dart`.
  - **F15** — Les `TextField` password (setup et dialog passphrase)
    permettaient la sélection / copie en clair quand l'utilisateur
    activait l'œil "Afficher". Désormais `enableInteractiveSelection`
    suit `obscureText` : pas de copie tant que masqué.

  **UX / a11y** :
  - **U3** — `rft_picker_screen` utilisait `ScaffoldMessenger.showSnackBar`
    direct (legacy non floating) → migration vers `showFloatingSnack`.
  - **U4** — `showErrorSnack` accepte un paramètre `action`
    (`SnackBarAction`) pour standardiser les patterns "Réessayer" /
    "Annuler" sur erreurs récupérables. Couleur de texte explicite
    `onErrorContainer` (contraste WCAG AA même en thème clair).
  - **U7** — `LinearProgressIndicator` (vault export/import folder) sans
    `Semantics(value: …)` → TalkBack annonçait juste "en cours" sans
    pourcentage. Wrap ajouté.
  - **U8** — Le dialog "Mode panique" et la ListTile utilisaient
    `Colors.red.shade700/900` codés en dur → cassait le thème dark
    et privait le daltonien d'alternative. Passage en `cs.error` /
    `cs.errorContainer` / `cs.onErrorContainer` (forme distincte
    préservée via `Icons.warning_amber` / `Icons.local_fire_department`).

  **Performance** :
  - **P1.3** — `global_search_screen` faisait un `setState` par
    `SearchHit` — sur un scan SD (50 k fichiers) cela pouvait dépasser
    1 000 rebuilds/s, geler le scroll et exploser la frame budget.
    Buffer + flush 100 ms ajouté → ListView fluide même sur résultats
    massifs (S9 / Redmi 9C 3 Go).
  - **P1.4** — Règles ProGuard `com.syncfusion.**` et `com.google.mlkit.**`
    étaient trop larges (couvraient 300+ classes inutilisées :
    barcode/face/pose/digital-ink pour ML Kit, tout le SDK Excel/Word
    pour Syncfusion). Narrow vers les sous-packages réellement utilisés
    (PDF + vision.text). **Gain APK estimé ~3-5 Mo** après R8.
  - **P1.5** — `isUniversalApk = true` produisait un APK universel de
    ~100 Mo gaspillé dans `build/outputs/apk/release/` (pas de Play
    Store côté Files Tech, distribution directe via splits ABI). Passé
    à `false` → build ~25 % plus rapide.
  - **P2.3** — `DateFormat('dd/MM/yyyy')` recréé à chaque appel
    `_formatDate` dans `home_screen` → hissé en `static final _dfDMY`.
  - **P2.4** — `MediaQuery.of(context)` restant sur 3 sites
    (`image_viewer_screen`, `signature_capture_screen`) → migration vers
    `MediaQuery.sizeOf` / `devicePixelRatioOf` (pas de rebuild sur
    changement d'inset clavier).

  **Tests garde** : `test/csv_safe_test.dart` (6 tests CSV-injection),
  `test/image_bounds_test.dart` (7 tests anti image-bomb PNG/GIF/JPEG),
  `test/file_caps_test.dart` (3 tests caps + helper). +16 tests, total 31.

  `dart analyze` 0 issue, 31/31 tests verts. Aucun changement de format
  vault `.enc` ni `.rftvault` (v1 et v2 toujours acceptés en lecture).

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
| 2.13.x        | ✅          |
| < 2.13.0      | ❌          |

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
