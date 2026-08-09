Voici le rapport de relecture adversariale concernant les correctifs de la version 2.15.

---

### 1. Garde anti-zip-bomb sur le chemin d'extraction (ZIP)

#### À vérifier — Confusion entre `raw.length` et `raw.remaining` dans la lecture par tranches (STORE)
- **Localisation :** `lib/utils/archive_safe.dart:181-187`
- **Pourquoi ça peut poser problème :** 
  Dans la boucle de copie par tranches des entrées non compressées (`STORE`) :
  ```dart
  final remaining = raw.length;
  out.writeInputStream(
    raw.readBytes(remaining < chunk ? remaining : chunk),
  );
  ```
  Dans `package:archive`, `InputStreamBase.length` renvoie la taille **totale** du flux (qui reste constante tout au long de la lecture), et non le nombre d'octets restants. Le nombre d'octets restants est exposé par `raw.remaining` (ou `raw.length - raw.position`).
- **Impact :** Heureusement, `raw.readBytes(count)` est robuste : si on lui demande de lire plus d'octets qu'il n'en reste, il renvoie simplement ce qui reste et positionne le curseur à la fin (ce qui déclenche `raw.isEOS` et arrête la boucle). Le code fonctionne donc sans boucler à l'infini ni planter. Cependant, l'expression `remaining < chunk` évalue presque toujours à `false` (sauf si le fichier entier fait moins de 64 Ko), ce qui rend la logique de calcul de la dernière tranche incorrecte.

#### Probable — Contournement théorique du Zip-Slip via un lien symbolique préexistant pour le fichier cible
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:233-241`
- **Pourquoi ça peut poser problème :**
  La validation dans `_extractAll` résout les liens symboliques du dossier parent via `outFile.parent.resolveSymbolicLinks()`. Cependant, si le fichier cible `outFile` lui-même est déjà présent sur le disque sous forme de lien symbolique pointant en dehors de `resolvedBase`, `outFile.parent` (le dossier contenant le lien) sera toujours résolu à l'intérieur de `resolvedBase`. L'appel `outFile.writeAsBytes(bytes)` suivra alors le lien symbolique et écrira en dehors du répertoire d'extraction.
- **Impact :** Sur Android, ce risque est fortement mitigé car `getApplicationDocumentsDirectory()` (`zip_viewer_screen.dart:199`) est un répertoire privé de l'application auquel aucun attaquant ou autre application ne peut accéder pour y pré-créer des liens symboliques. Cela reste néanmoins une faiblesse logique de la validation de chemin.

---

### 2. Déduplication de l'extraction DOCX / ODT

#### Confirmé — Changement de comportement (UX) : Un document ODT/DOCX valide mais vide produit désormais une erreur bloquante
- **Localisation :** `lib/services/text_extraction_service.dart:116-120` (DOCX) et `lib/services/text_extraction_service.dart:187-191` (ODT)
- **Pourquoi ça casse :**
  Auparavant, un fichier ODT vide ou ne contenant aucun paragraphe retournait une chaîne vide (`""`) et s'affichait normalement (écran vide). Désormais, le service d'extraction renvoie explicitement une erreur `DocxExtractResult(error: 'Le document semble vide...')`.
- **Impact :** L'utilisateur verra un message d'erreur bloquant ("Le document semble vide") pour un fichier tout à fait valide mais vierge, ce qui constitue une régression d'expérience utilisateur par rapport à l'ancien comportement.

---

### 3. Permissions et documents

#### Rien trouvé sur cet axe
- **Raison :** Le diff fourni ne contient pas les fichiers de configuration (`AndroidManifest.xml`) ni les fichiers de documentation (`README.md`, `PRIVACY.md`, etc.), empêchant toute confrontation directe avec le code.

---

### 4. Cycle de vie

#### Rien trouvé sur cet axe
- **Raison :** Les corrections apportées sur les gardes `mounted` dans `_refresh`, `_load`, `_scan` et `_extractAll` sont complètes et robustes. L'utilisation de `SnackTarget` résout élégamment le problème des SnackBar post-async en vérifiant la présence du `ScaffoldMessengerState` via `_messenger.mounted` (`lib/utils/snack_utils.dart:95`), ce qui est validé par le nouveau test d'intégration `test/snack_target_test.dart:168-211`.