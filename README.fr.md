# Read Files Tech

[![CI](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml/badge.svg)](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/gitubpatrice/READ-FILES-TECH)](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest)
[![Flutter](https://img.shields.io/badge/Flutter-stable-02569B?logo=flutter)](https://flutter.dev)

**Le couteau suisse Android pour vos fichiers — version 2.15.2.**

Explorateur de fichiers, lecteur universel, scanner de documents, OCR, coffre-fort, corbeille,
conversion, anti-EXIF — vos fichiers ne quittent jamais l'appareil, sans cloud, sans compte.

🇬🇧 English version, and the reference one : [README.md](./README.md)

> Deux points, détaillés dans [PRIVACY.fr.md](./PRIVACY.fr.md) §9 bis. Google ML Kit embarque
> un transport de télémétrie : ses trois points d'entrée sont retirés du manifeste final depuis
> la v2.15, donc il ne démarre pas — vérifié sur l'APK publié, où `aapt2` ne trouve aucun
> composant `datatransport`. Les permissions `INTERNET` et `ACCESS_NETWORK_STATE` sont bien
> déclarées par l'application, et lui servent à une seule chose : vérifier sur GitHub qu'une
> mise à jour existe. Enfin, le scanner de documents dépend des services Google Play ; tout le
> reste, OCR compris, fonctionne sans eux.

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

## Ce qui a changé, et où le lire

Les notes de version ne sont **pas dupliquées ici**, volontairement : ce fichier
portait ses propres sections « Nouveautés » et elles décrivaient encore la v2.12
alors que l'application était en 2.15. Une seule source, tenue à jour :

- [Releases](https://github.com/gitubpatrice/READ-FILES-TECH/releases) — chaque
  version, ses quatre APK signés et leurs empreintes SHA-256
- [`fastlane/metadata/android/fr-FR/changelogs/`](fastlane/metadata/android/fr-FR/changelogs/)
  — les mêmes notes, par versionCode, en français et en anglais

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
| `ACCESS_NETWORK_STATE`      | Accompagne la vérification de mise à jour.                                             |

Détail complet et raison d'être : [PRIVACY.fr.md §9](PRIVACY.fr.md).

## Installation

[GitHub Releases — dernière version](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest) — APK signé, distribué hors Play Store.

Site officiel : [files-tech.com/read-files-tech](https://www.files-tech.com/read-files-tech.php)

## Build local

```bash
git clone https://github.com/gitubpatrice/READ-FILES-TECH.git read_files_tech
cd read_files_tech
flutter pub get
flutter build apk --release
```

`files_tech_core` est épinglé par commit dans `pubspec.yaml` et récupéré par
`flutter pub get` : il n'a pas à être cloné à côté. Nécessite Flutter stable,
le SDK Android et JDK 17.

## Confidentialité

**Vos fichiers ne quittent jamais l'appareil.** Lecture, édition, conversion,
OCR, coffre : tout s'exécute localement. Aucune collecte, aucun profilage, aucun
compte.

Deux nuances, parce qu'elles sont vérifiables sur l'APK et qu'il serait malhonnête
de les taire :

- L'application interroge l'API publique GitHub Releases au lancement, pour
  signaler les mises à jour — c'est le seul canal qui prévienne d'un correctif de
  sécurité en distribution par sideload. Aucun identifiant n'est transmis.
- Le scanner de documents dépend des services Google Play. Tout le reste, OCR
  compris, fonctionne sans eux.

**L'application ne porte aucune télémétrie.** Google ML Kit, qui fournit l'OCR
hors ligne, embarque son propre transport de télémétrie en dépendance
transitive. Ses trois points d'entrée — `TransportBackendDiscovery`,
`JobInfoSchedulerService` et `AlarmManagerSchedulerBroadcastReceiver` — sont
retirés du manifeste final par `tools:node="remove"` depuis la v2.15. Sans point
d'entrée déclaré, Android ne peut ni lier le service ni délivrer la diffusion :
le transport ne démarre pas.

C'est mesuré à chaque release plutôt que supposé, parce qu'une montée de
dépendance peut le défaire en silence. Sur le build 2.15.2 : **zéro composant
`datatransport`** dans le manifeste fusionné et dans l'APK, contre un contrôle
positif de sept entrées `com.google.mlkit` — le contrôle ne vaudrait rien s'il
passait sur un APK qui aurait aussi perdu ML Kit.

> ⚠️ Cette page a longtemps affirmé l'inverse ici même, en contradiction avec
> son propre bandeau d'en-tête. La description du store avait été corrigée le
> 2026-08-19, pas ce fichier.

Détail complet dans [PRIVACY.fr.md](PRIVACY.fr.md) §6 bis et §9 bis, et
[TERMS.fr.md](TERMS.fr.md).

## Licence

Apache License 2.0 — voir [LICENSE](LICENSE) et [NOTICE](NOTICE).
