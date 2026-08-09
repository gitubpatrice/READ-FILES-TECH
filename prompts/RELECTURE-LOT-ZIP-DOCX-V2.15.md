# Relecture adversariale — extraction ZIP, DOCX/ODT, permissions (Read Files Tech v2.15)

Tu relis des correctifs qui viennent d'être écrits. **Ne les valide pas.** Une
relecture qui conclut « tout va bien » n'apporte rien. Cherche ce qui casse.

## Contexte

Application Flutter/Dart (Android). Explorateur de fichiers, visionneuses,
coffre chiffré. Distribution par sideload (GitHub Releases), pas de magasin.

## Ce qui a été corrigé, et ce que je te demande de mettre en défaut

### 1. Garde anti-zip-bomb sur le chemin d'extraction

`ArchiveFile.size` vient de l'en-tête du ZIP : c'est l'attaquant qui l'écrit.
Deux sites de `zip_viewer_screen.dart` s'y fiaient encore, puis appelaient
`file.content`, qui décompresse **sans borne**.

Correctif : `safeEntryBytes(entry, label, cap)` (lib/utils/archive_safe.dart),
qui inflate vers un puits plafonné et lève `ArchiveTooLargeException`.

Dans `_extractAll`, en plus :
- le cumul s'incrémente désormais de `bytes.length` et non de `file.size` ;
- le cap de l'entrée courante vaut `min(capEntrée, budgetRestant)` ;
- la validation anti-zip-slip (`_safeJoin`) passe **avant** la décompression ;
- une entrée trop grosse est ignorée au lieu de faire échouer l'extraction.

**Cherche** : un chemin où la borne cumulée peut encore être dépassée ; un
dépassement d'entier ; un cas où `remaining <= 0` n'est jamais atteint ; une
entrée de type répertoire ou lien symbolique traitée comme un fichier ; le fait
qu'`_archive` ait déjà été entièrement décompressé en mémoire **avant** ces
gardes, par le `ZipDecoder` initial — auquel cas les gardes arrivent trop tard.

### 2. Déduplication de l'extraction DOCX / ODT

`docx_viewer_screen.dart` portait sa propre extraction, divergente de
`text_extraction_service.dart` : son décodage d'entités traitait `&amp;` en
premier, donc `&amp;lt;` ressortait `<` au lieu de `&lt;`.

Les deux extracteurs vivent maintenant dans le service. Pour l'ODT, le décodage
générique rend `\r` pour `&#xD;`, normalisé en `\n` après coup.

**Cherche** : une régression de comportement introduite par le passage au
service (messages d'erreur devenus différents, `.doc` binaire, cas où l'ancien
code affichait quelque chose et le nouveau plus rien) ; un problème dans la
normalisation `\r` ; un cas où `odtXmlToPlainText` perd du texte que l'ancien
`_xmlToText` gardait.

### 3. Permissions et documents

`INTERNET` / `ACCESS_NETWORK_STATE` étaient dans l'APK sans être dans le
manifeste source — injectées par la télémétrie ML Kit — alors que
l'application s'en sert elle-même pour vérifier les mises à jour au lancement.
Elles sont désormais déclarées explicitement.

**Cherche** : une contradiction restante entre ce que le code fait et ce que
README / PRIVACY.fr.md / PRIVACY.md / SECURITY.md affirment. Une affirmation
non vérifiable. Un endroit où le document promet plus que le code ne tient.

### 4. Cycle de vie

Plusieurs `setState` étaient appelés sans garde `mounted` en tête de méthode
(`_refresh`, `_load`, `_scan`). Gardes ajoutés dans la méthode.

**Cherche** : un `setState`, un `Navigator`, un `showDialog` ou un accès à
`context` encore atteignable après démontage, sur les chemins touchés.

## Règles

- Cite `fichier:ligne`. Sans localisation, le constat sera écarté.
- Distingue **confirmé** / **probable** / **à vérifier**.
- Aucune préférence de style, aucun renommage, aucune suggestion
  d'architecture générale.
- Si tu ne trouves rien sur un axe, écris « rien trouvé sur X ». N'invente pas
  pour remplir.
