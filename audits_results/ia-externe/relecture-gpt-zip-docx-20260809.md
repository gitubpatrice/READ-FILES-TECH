## 1) Garde anti‑zip‑bomb sur le chemin d’extraction (ZIP)

### Confirmé — `_load()` peut encore faire `setState()` après démontage (crash) *avant même l’extraction*
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:41-64`
- **Constat :** `_load()` fait plusieurs `await` puis appelle `setState(...)` **sans** `if (!mounted) return;` ni garde `if (mounted)`.
- **Impact :** sortie rapide de l’écran pendant le chargement ⇒ `setState() called after dispose()`.

### Confirmé — action “Extraire tout” possible quand `_archive` n’a jamais été initialisé (LateInitializationError)
- **Localisation :**  
  - init/erreur : `lib/screens/viewers/zip_viewer_screen.dart:41-64` (catch ne renseigne pas `_archive`)  
  - bouton : `lib/screens/viewers/zip_viewer_screen.dart:269-276` (onPressed dépend seulement de `_isLoading`)
- **Chemin :** si `_load()` échoue ⇒ `_isLoading=false`, `_error!=null`, mais le bouton “Extraire tout” reste cliquable ⇒ `_extractAll()` accède à `_archive.files` alors que `late Archive _archive;` n’a jamais été assigné.
- **Impact :** crash immédiat sur un ZIP illisible/corrompu (ou simplement sur une lecture qui a échoué).

### Confirmé — `_CappedOutput.writeInputStream()` ne contrôle le plafond qu’**après** la copie complète (borne “trop tard” pour STORE / non‑DEFLATE)
- **Localisation :** `lib/utils/archive_safe.dart:92-96` et `lib/utils/archive_safe.dart:166-173`
- **Pourquoi ça casse :**
  - Pour les entrées non‑DEFLATE, `safeEntryBytes()` fait `out.writeInputStream(raw)` (`archive_safe.dart:169-172`).
  - Or `_CappedOutput.writeInputStream` délègue à `_inner.writeInputStream(stream)` **puis** appelle `_check()` (`archive_safe.dart:92-96`).
- **Impact :** si `OutputStream.writeInputStream` avale de gros volumes avant de rendre la main, la limite n’interrompt pas “au fil de l’eau” (contrairement au chemin DEFLATE qui passe par `writeBytes` et check à chaque write).  
  → Même si, dans la visionneuse ZIP, la taille du ZIP est capée (`zip_viewer_screen.dart:43-49`), ce chemin laisse une fenêtre de surconsommation mémoire *inutilement large* sur des entrées STORE / compression non reconnue.

### Probable — zip‑slip via symlink “déjà présent” dans le dossier cible (pas de `resolveSymbolicLinks`)
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:157-178` (`_safeJoin`)
- **Constat :** `_safeJoin` fait une vérif de préfixe sur `File(...).absolute.path` mais **ne** résout **pas** les liens symboliques (malgré le commentaire “resolveSymbolicLinks” à `:169`).
- **Impact :** si `outDir` (ou un de ses sous‑dossiers) contient déjà un symlink pointant dehors, `File(target).writeAsBytes(...)` (`zip_viewer_screen.dart:237-241`) peut écrire hors de `outDir` malgré un chemin “textuellement” dedans.
- **Note :** sur Android c’est moins courant, mais c’est précisément le genre de trou “zip-slip” qui ressort quand on parle d’extraction.

### À vérifier — `ZipDecoder().decodeBytes(bytes)` décompresse‑t‑il déjà quelque chose en mémoire avant vos gardes ?
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:49-54`, `lib/services/text_extraction_service.dart:100-106` et `:164-171`
- **Risque :** si `decodeBytes` inflate certains contenus (ou matérialise `content` au lieu de `rawContent`) *pendant* le décodage, alors les gardes de `safeEntryBytes()` arrivent trop tard.  
  Je ne peux pas le confirmer sans la version exacte de `package:archive`, mais c’est un point à re‑valider car tout le modèle de menace dépend de “decode ≠ decompress”.

### Rien trouvé — dépassement d’entier sur les cumuls
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:207-242`
- **Pourquoi :** `int` Dart est arbitraire (pas d’overflow 32 bits/64 bits en VM), et le cumul est basé sur `bytes.length` (réel), borné par `cap`.

---

## 2) Déduplication extraction DOCX / ODT

### Confirmé — régression de rendu “paras vides” / espaces en tête (docx) : vous n’ignorez plus les paragraphes vides
- **Localisation :** `lib/services/text_extraction_service.dart:44-54`
- **Constat :**
  - `docxXmlToPlainText` fait systématiquement `out..write(pieces)..writeln();` pour **chaque** bloc entre sentinels, même si `pieces` est vide.
  - L’ancien code écran (supprimé) n’écrivait un paragraphe que si `text.trim().isNotEmpty`.
- **Impact :** les `.docx` contenant beaucoup de paragraphes vides / de structure peuvent produire **plus de lignes vides** qu’avant, y compris potentiellement des retours à la ligne au début (vous ne faites que `trimRight()`, `:53`, donc un début vide reste).

### Confirmé — changement de contrat côté ODT : un document “sans texte détecté” devient une **erreur** au lieu d’un affichage vide
- **Localisation :** `lib/services/text_extraction_service.dart:185-191`
- **Avant :** l’ancienne `_extractOdtStatic` retournait le `trim()` de `_xmlToText(...)` (donc potentiellement `''`) sans transformer ça en erreur.
- **Après :** `extractOdtText` renvoie `error: 'Le document semble vide...'` si `text.trim().isEmpty`.
- **Impact :** régression UX possible : un fichier ODT “vide” (ou texte stocké hors `text:p`, ou dans d’autres balises) ne montrera plus “rien” mais une erreur bloquante.

### Probable — perte de sauts de ligne ODT non encodés en entités (ex: `<text:line-break/>`) inchangée, mais le passage “service” fige ce comportement
- **Localisation :** `lib/services/text_extraction_service.dart:150-155`
- **Constat :** vous supprimez toutes les balises internes via `inner.replaceAll(RegExp(r'<[^>]+>'), '')`.  
  Toute balise de type “line-break” devient donc **rien** (pas de `\n`), sauf si elle était encodée via entité `&#xD;` que vous normalisez.
- **Pourquoi je le mets quand même :** c’est un cas où l’ancien `_xmlToText` avait le *même* défaut, mais la déduplication est l’occasion où des utilisateurs vont comparer “avant/après” sur des fichiers concrets ODT.

### À vérifier — affichage dans `DocxViewerScreen` si `result.error != null` (risque d’écran vide)
- **Localisation :** `lib/screens/viewers/docx_viewer_screen.dart:63-74` (dans le diff : `setState(() { _error = result.error; _text = result.text ?? ''; ... })`)
- **Risque :** si le `build` de l’écran privilégie `_text` et n’affiche pas `_error` (ou l’affiche ailleurs), vous pouvez obtenir un écran qui semble “vide” au lieu d’un message, alors que l’ancien code retournait parfois une chaîne explicite (ex. “Impossible de lire le document.”).

### Rien trouvé — bug `&amp;lt;` → `<` (double décodage) dans le nouveau cœur
- **Localisation :** `lib/services/text_extraction_service.dart:56-79` (ordre `&amp;` en dernier) + tests ajoutés `test/text_extraction_service_test.dart:...` (diff)
- **Le nouveau code semble bien traiter `&amp;` en dernier**, ce qui évite précisément le défaut décrit.

---

## 3) Permissions / documents (INTERNET, ACCESS_NETWORK_STATE)

### Rien trouvé sur cet axe
- **Raison :** le diff fourni ne contient ni `AndroidManifest.xml`, ni les changements README/PRIVACY/SECURITY, donc je ne peux pas pointer une contradiction **avec `fichier:ligne`** comme demandé.

---

## 4) Cycle de vie (`setState` / `context` après démontage)

### Confirmé — `ZipViewerScreen._load()` reste un appelant à risque (`setState` post‑await sans `mounted`)
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:41-64`
- **Impact :** c’est exactement la classe de bug que vous dites avoir corrigée ailleurs (`_refresh`, `_load`, `_scan`), mais ici elle subsiste.

### Probable — feuille de partage “hors écran” possible sur ZIP (même symptôme que l’OCR corrigé)
- **Localisation :** `lib/screens/viewers/zip_viewer_screen.dart:180-195`
- **Scénario :** l’utilisateur tape “share”, puis revient en arrière pendant la décompression/écriture temp (`await getTemporaryDirectory`, `await File.writeAsBytes`) ⇒ `Share.shareXFiles` se déclenche quand même.
- **Pourquoi c’est comparable :** vous avez explicitement jugé ce comportement indésirable pour l’OCR (guard `mounted` ajouté). Ici ce n’est pas protégé.

### Rien trouvé — utilisation de `context` après démontage dans les *nouveaux* gardes montrés (FileExplorer/Duplicates/Trash/OCR)
- **Localisation :** `lib/screens/explorer/file_explorer_screen.dart:289-314`, `lib/screens/tools/duplicates_screen.dart:43-120`, `lib/screens/tools/trash_screen.dart:27-40`, `lib/screens/tools/ocr_screen.dart:88-100`
- Sur ces extraits, les `showDialog` sont précédés de `if (!mounted) return;` et les `setState` à risque ont un garde en tête ou juste avant.