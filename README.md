# Read Files Tech

[![CI](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml/badge.svg)](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/gitubpatrice/READ-FILES-TECH)](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest)
[![Flutter](https://img.shields.io/badge/Flutter-stable-02569B?logo=flutter)](https://flutter.dev)

**Le couteau suisse Android pour vos fichiers — version 2.12.3.**

Explorateur de fichiers, lecteur universel, scanner de documents, OCR, coffre-fort, conversion, anti-EXIF — 100 % local, sans cloud, sans tracker.

## Fonctionnalités

- **Explorateur de fichiers** : navigation, recherche, multi-sélection, copier/déplacer/renommer en masse, picker avec filtre intelligent.
- **Lecteur universel** : PDF, CSV, XLSX, DOCX, JSON, MD, TXT, HTML, ZIP, images, EPUB (et ODT, ODS, JS, CSS, PHP, XML).
- **Scanner de document** (caméra, détection des bords, perspective, export PDF).
- **OCR Latin** sur images, 100 % local (ML Kit on-device).
- **Coffre-fort `.rftvault v2 AAD`** : Argon2id + AES-256-GCM + AAD bindée au nom de fichier, FLAG_SECURE, rate-limit anti brute-force, chiffrement batch dossier.
- **Signature PDF** au doigt.
- **Conversion** : Images → PDF, CSV ↔ XLSX, JPG ↔ PNG, TXT/MD → PDF, etc.
- **Anti-EXIF** : suppression GPS, date, modèle d'appareil avant partage.
- **Recherche globale** par nom et contenu (Isolate Dart).
- **Anti-doublons SHA-256** : trois passes pour libérer du stockage.
- **Partage cloud** : envoi explicite vers les apps cloud installées (kDrive, Google Drive, Proton Drive — action utilisateur via le sélecteur de partage Android).
- **Quick Tiles** Android : scanner, OCR, coffre depuis le volet de notification.
- **Installation d'APK** depuis l'explorateur — tap sur un `.apk` → PackageInstaller système (icône Android dédiée + couleur teal dans la liste).

## Nouveautés v2.12.3

- **Installation d'APK restaurée** depuis l'explorateur (tap `.apk` → PackageInstaller système, avec dialog si l'autorisation "Apps installant des applis inconnues" n'est pas encore accordée).
- **Icône Android** dédiée + couleur teal pour les `.apk` dans la liste.
- Icône d'app alignée sur la suite Files Tech (`assets/icon/app_icon.png` régénéré).

> ℹ️ **Play Protect** : le binaire est soumis à Google + VirusTotal pour traitement du faux positif "dropper" déclenché par la combinaison `MANAGE_EXTERNAL_STORAGE` + `REQUEST_INSTALL_PACKAGES`. Read Files Tech n'installe jamais en silence : le PackageInstaller système exige toujours un consentement utilisateur explicite.

## Nouveautés v2.12.1

- Anti CSV-injection à l'export (préfixe `'` automatique sur cellules `= + - @ \t \r`).
- Cap source + cap cumulatif sur `Outils CSV` (anti-OOM).
- `SecureWindow` à refcount : plus de vignette Recents qui leak un coffre quand un écran sensible se chevauche.
- Vault : déchiffrement v2 zéro-copie (`sublistView`), check `_v2OnlyCache` lu avant la sentinelle.
- HTML viewer : navigation `file://` cantonnée au dossier source avec whitelist d'extensions document/média.
- Atomic write étendu aux éditeurs CSV et code source (plus de fichier tronqué si kill OS pendant le save).
- Restore `.rftvault` : wipe plaintext per-entry (au lieu d'un wipe global tardif).
- Restauration de l'installation d'APK + icône Android dédiée dans la liste.
- Icône d'app alignée sur la suite Files Tech.
- Nettoyage : `run_busy` mort retiré, règles ProGuard orphelines retirées, dédup `_autoLockDelay` via `AppConstants`.

## Sécurité

- Coffre-fort : **Argon2id + AES-256-GCM + AAD bindée au filename**, dérivation auto-tuned, métadonnées scellées.
- **`safeCanonical` + roots whitelist côté Kotlin** : path traversal et accès hors sandbox bloqués sur tous les MethodChannels natifs.
- **HTML viewer scoping `file://`** : isolation stricte du contexte WebView, JavaScript désactivé par défaut.
- **Network Security Config** strict : pas de cleartext HTTP, pas d'autorités utilisateur.
- **FileProvider** restrictif : exposition contrôlée des chemins partagés.
- Protection anti zip-slip sur les extractions d'archives.

Voir [SECURITY.md](SECURITY.md) pour la politique de signalement.

## Permissions Android

| Permission                  | Justification                                                                          |
| --------------------------- | -------------------------------------------------------------------------------------- |
| `MANAGE_EXTERNAL_STORAGE`   | Fonction explorateur universel : parcourir, lire, éditer tout fichier choisi.          |
| `REQUEST_INSTALL_PACKAGES`  | Installer un APK signé depuis l'explorateur (notamment sync avec PDF Tech).            |
| `CAMERA`                    | Scanner de documents et OCR (optionnel, accordée à la demande).                        |
| `INTERNET`                  | Vérification des mises à jour via API GitHub Releases (anonyme, sans cookie).          |

Détail complet et raison d'être : [PRIVACY.fr.md §9](PRIVACY.fr.md).

## Installation

[GitHub Releases — dernière version](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest) — APK signé, distribué hors Play Store.

Site officiel : [files-tech.com/read-files-tech](https://www.files-tech.com/read-files-tech.php)

## Build local

```bash
git clone https://github.com/gitubpatrice/READ-FILES-TECH.git read_files_tech
git clone https://github.com/gitubpatrice/files_tech_core.git
cd read_files_tech
flutter pub get
flutter build apk --release
```

Nécessite Flutter stable + Android SDK + JDK 17.

## Confidentialité

100 % local. Aucune télémétrie, aucune collecte de données, aucun partage. Voir [PRIVACY.fr.md](PRIVACY.fr.md) et [TERMS.fr.md](TERMS.fr.md).

## Licence

Apache License 2.0 — voir [LICENSE](LICENSE) et [NOTICE](NOTICE).
