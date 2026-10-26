# Read Files Tech

**Lecteur, éditeur et explorateur de fichiers pour Android — vos fichiers ne quittent jamais le téléphone.**

Explorateur, visionneuse universelle, éditeurs, scanner de documents, OCR, coffre chiffré, corbeille,
conversions, effacement des données EXIF. Sans compte, sans cloud, sans permission Internet. En français,
anglais, allemand, italien et espagnol.

🇬🇧 English version: [README.md](./README.md)

La version 3.0 est une réécriture de l'application en Kotlin avec Jetpack Compose. Elle reprend en place les
données des versions 2.x — coffre, corbeille, réglages, fichiers récents — et s'installe par-dessus.

## La confidentialité en un paragraphe

Tout se passe sur le téléphone : lecture, édition, conversions, OCR, coffre. Rien n'est collecté, rien n'est
profilé, il n'y a pas de compte. **L'application n'a pas de permission Internet** : `INTERNET` et
`ACCESS_NETWORK_STATE` sont retirées du manifeste fusionné, et l'intégration continue échoue sur toute
permission hors d'une liste revue, lue dans le manifeste fusionné de la version publiée et dans l'APK lui-même.
**Rien des services de Google** non plus : l'OCR est Tesseract, sur le téléphone ; l'intégration continue
refuse ML Kit, les services Play, Firebase et leur transport de télémétrie dans les dépendances et dans le
manifeste ; la WebView du système est exclue de ses statistiques, et l'application ne demande pas la police
d'émojis à un fournisseur de polices. Un fichier ne quitte le téléphone que lorsque vous le confiez à une autre
application — partage, « Ouvrir avec », application cloud, PDF Tech.

Ce que l'application conserve, où, et ce qui est chiffré ou non : la [politique de confidentialité](app/src/main/res/raw-fr/privacy.md),
affichée dans l'application sous À propos.

## Fonctionnalités

- **Explorateur** — parcourir, chercher par nom, trier, sélection multiple, copier, déplacer, renommer (un
  fichier ou plusieurs : numérotation, préfixe et suffixe, expressions régulières), corbeille avec annulation,
  informations, installation d'APK par l'installateur d'Android.
- **Visionneuses** — texte et code (coloration), Markdown, JSON (arbre), HTML (isolé : sans JavaScript, sans
  connexion), CSV et TSV, tableurs (XLSX, XLS, ODS), documents (DOCX, ODT, ODP), EPUB et mode lecture, PDF
  (mot de passe, sommaire, aller à la page), ZIP (tout extraire), images (galerie, zoom).
- **Éditeurs** — code et texte avec historique automatique des versions, CSV en tableau modifiable.
- **Coffre chiffré** — AES-256-GCM, clé dérivée par Argon2id, verrouillage automatique, blocage contre la force
  brute, écrans protégés de la capture, sauvegardes `.rftvault` chiffrées, import de dossier. Un mode panique,
  dans les Réglages, efface d'un coup ce que l'application garde : coffre, corbeille, historique, récents, cache.
- **Scanner** — photos prises avec l'application d'appareil photo ou choisies, bords détectés, perspective
  redressée, PDF.
- **OCR** — Tesseract sur le téléphone, en anglais, français, allemand, italien et espagnol.
- **Conversions** — images → PDF, CSV → XLSX, texte → PDF, PDF et DOCX → texte, images en JPG ou PNG.
- **Outils** — compression, effacement EXIF, doublons et gros fichiers (SHA-256), recherche dans le contenu et
  recherche globale, comparaison, empreinte de fichier, encodage, formatage, pipette à couleurs, création de
  ZIP, outils texte et CSV.
- **Tuiles des réglages rapides** — scanner, OCR et coffre depuis le volet des notifications.
- **Envois** — à votre demande, vers kDrive, Google Drive ou Proton Drive s'ils sont installés, et « Éditer
  dans PDF Tech ».

## Permissions Android

| Permission | Pourquoi |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` (Android 11+) | Un explorateur de fichiers : parcourir, lire et modifier les fichiers que vous choisissez, partout sur le stockage partagé. |
| `READ_EXTERNAL_STORAGE` (≤ Android 12), `WRITE_EXTERNAL_STORAGE` (≤ Android 10) | La même chose sur les versions plus anciennes d'Android. |
| `REQUEST_INSTALL_PACKAGES` | Confier un APK touché dans l'explorateur à l'installateur d'Android, qui demande toujours sa propre confirmation. |
| `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Interne, ajoutée par AndroidX, au niveau `signature`. |

Pas de permission d'appareil photo (les photos sont prises par l'application d'appareil photo), ni médias, ni
localisation, ni contacts, ni micro, ni notifications, ni réseau.

## Installation

Depuis les [versions publiées sur GitHub](https://github.com/gitubpatrice/READ-FILES-TECH/releases/latest),
hors du Play Store. Chaque version porte trois APK par architecture et un APK universel, avec leurs empreintes
SHA-256 ; le certificat de signature est celui des versions 2.x : la 3.0 s'installe par-dessus et garde leurs
données. Pour vérifier un téléchargement : [SECURITY.fr.md](SECURITY.fr.md#vérifier-un-apk).

L'application ne cherche jamais de mise à jour d'elle-même : « Vérifier les mises à jour », dans À propos,
ouvre la page des versions dans votre navigateur. Site officiel :
[files-tech.com](https://www.files-tech.com/read-files-tech.php).

## Construire depuis les sources

```bash
git clone https://github.com/gitubpatrice/READ-FILES-TECH.git
cd READ-FILES-TECH
./gradlew :app:assembleDebug
```

Il faut le JDK 17 et le SDK Android (compileSdk 37) ; Gradle et toutes les dépendances sont épinglés dans le
dépôt. Par défaut, la construction s'installe **à côté** de l'application publiée (paquet suffixé `.next`,
dossiers à elle) : elle ne touche jamais à vos données. `-Prft.replaceFlutter=true` construit le vrai paquet,
dont la version publiable refuse de se construire sans la clé de signature.

## Traductions

L'anglais est la langue source ; le français, l'allemand, l'italien et l'espagnol sont complets. Ajouter une
langue : [TRANSLATING.md](TRANSLATING.md) (en anglais).

## Licence

Licence Apache 2.0 — voir [LICENSE](LICENSE). Les logiciels tiers que l'application inclut, avec leurs mentions
et leurs licences, sont listés dans [NOTICE](NOTICE), affiché dans l'application sous À propos › Licences des
composants tiers.

Conditions d'utilisation : [app/src/main/res/raw-fr/terms.md](app/src/main/res/raw-fr/terms.md). Signaler une
vulnérabilité : [SECURITY.fr.md](SECURITY.fr.md).

Copyright 2026 Patrice Haltaya — Files Tech.
