# Read Files Tech

[![CI](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml/badge.svg)](https://github.com/gitubpatrice/READ-FILES-TECH/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/gitubpatrice/READ-FILES-TECH)](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest)
[![Flutter](https://img.shields.io/badge/Flutter-stable-02569B?logo=flutter)](https://flutter.dev)

**Le couteau suisse Android pour vos fichiers.**

Explorateur, lecteur universel, scanner de documents, OCR, signature PDF, coffre fort AES-256, recherche globale, anti-doublons — tout en local, sans cloud, sans tracker.

## Fonctionnalités

- **Lecteur universel** : PDF, DOCX, XLSX, ODT, ODS, EPUB, HTML, CSV, TXT, MD, JSON, JS, CSS, PHP, XML, images
- **Explorateur complet** : navigation, recherche, multi-sélection, copier/déplacer/renommer en masse
- **Coffre fort AES-256-GCM** : Argon2id auto-tuned, FLAG_SECURE, rate-limit anti brute-force, export/restore `.rftvault`, chiffrement batch dossier
- **Scanner de document** + OCR (ML Kit local)
- **Signature PDF** au doigt
- **Conversion** : Images → PDF, CSV → XLSX, JPG ↔ PNG, etc.
- **Compression d'image**, anti-EXIF
- **Recherche globale** (Isolate Dart) par nom et contenu
- **Doublons & gros fichiers** : SHA-256 trois passes
- **Cloud direct** kDrive / Google Drive / Proton Drive
- **Quick Tiles** Android (scanner, OCR, coffre)

## Téléchargement

[GitHub Releases](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest) — APK signé, distribué hors Play Store.

Site officiel : [files-tech.com/read-files-tech](https://www.files-tech.com/read-files-tech.php)

## Confidentialité

100 % local. Aucune télémétrie, aucune collecte de données, aucun partage. Code source ouvert sous licence Apache 2.0.

Voir [PRIVACY.fr.md](PRIVACY.fr.md) et [TERMS.fr.md](TERMS.fr.md).

## Build local

```bash
git clone https://github.com/gitubpatrice/READ-FILES-TECH.git read_files_tech
git clone https://github.com/gitubpatrice/files_tech_core.git
cd read_files_tech
flutter pub get
flutter build apk --release
```

Nécessite Flutter stable + Android SDK + JDK 17.

## Licence

Apache License 2.0 — voir [LICENSE](LICENSE) et [NOTICE](NOTICE).
