# Reprise du chantier v2.15 — état au 2026-08-10, fin de journée

> Document de reprise. Il existe pour qu'une session repartant sans le contexte
> de la précédente sache où en est le travail, ce qui reste, et ce qui a déjà
> été tranché — sans le redécouvrir ni, pire, le refaire autrement.
>
> Détail des constats : `consolidation_v2_15_20260809.md` (les 23 points du plan
> d'audit) puis `consolidation_v2_15_suite_20260809.md` (décisions produit et
> dette). Ici : l'état et la suite.

## 1. État

*État au 2026-08-10 au soir, tout poussé. Coffre éclaté, ODS lisible, et un
audit externe des fichiers jamais relus — voir §8 et §9.*

| | |
|---|---|
| Branche | `main`, arbre **propre**, 0/0 de divergence |
| Commits depuis `c89b820` (v2.14.0) | **58** |
| `flutter analyze` | **0 issue** |
| `flutter test` | **204 tests verts** (18 fichiers) |
| Build release | **passe** — 35,6 / 40,7 / 42,9 Mo |
| Version `pubspec.yaml` | **2.14.0+21400** — non bumpée, volontairement |
| Tag | aucun |
| Appareils | Galaxy S9 (Android 10, **API 29**) et Galaxy S24 FE (Android 16, **API 36**), APK installé sur les deux |

## 2. ✅ CLOS le 2026-08-10 — l'extraction d'archive est vérifiée sur appareil

Ce paragraphe demandait de confirmer que l'extraction fonctionnait, après deux
pannes consécutives sur ce chemin le 2026-08-09 (un **ANR**, puis une fermeture
d'`Isolate.run` qui **embarquait l'arbre de widgets**), chacune avec
`flutter analyze` à 0 et tous les tests verts.

**Constaté sur les deux téléphones** : `01_bombe_300Mo.zip` s'ouvre, « Extraire
tout » produit le bandeau rouge de refus, sans gel, sans « Invalid argument »,
sans boîte « ne répond pas ». Journaux vides des deux côtés.

**Ce que la vérification a coûté en plus.** La migration vers `archive` 4 a
révélé que ce chemin n'était toujours pas testé pour ce qui compte — voir §4.

## 2 bis. ✅ Le doute du « second appui » est levé

Au premier essai, le bandeau de refus n'était apparu qu'au **second** appui sur
« Extraire tout ». Reconstaté après le correctif d'affichage : **il arrive dès
le premier appui**, sur les deux téléphones. L'hypothèse retenue — le premier
appui portait sur l'icône encore désactivée pendant le chargement, qui ne donne
aucun retour — n'a pas été prouvée, mais aucun défaut ne subsiste sur ce chemin.

## 3. Décisions prises, à ne pas rouvrir

| Point | Décision |
|---|---|
| **Télémétrie** | **Zéro.** Les trois composants `datatransport` de Google sont retirés du manifeste final. Vérifié à l'`aapt` sur l'APK : aucun composant de télémétrie. Vérifié au `dumpsys jobscheduler` après OCR sur les deux appareils : aucun job — alors que d'autres applications des mêmes téléphones en planifient, ce qui prouve que le contrôle sait détecter. **OCR et scanner fonctionnent toujours**, testés sur les deux. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **Gardées et déclarées.** L'application interroge l'API GitHub au lancement pour signaler les mises à jour. Les retirer casserait la vérification **en silence**. |
| Community License Syncfusion | **Reportée sciemment.** |
| F-Droid | **Pas un objectif.** |
| **Passer à Kotlin** | **Non.** Ne retirerait pas la télémétrie — ML Kit est un AAR natif, identique en Kotlin. Coût : réécriture de plusieurs mois qui reperdrait tous les défauts corrigés ces deux jours. |
| **Tableur : `excel` → `excel_community`** | **Fait le 2026-08-10.** `excel 4.0.6` est mort depuis août 2024 et ne lisait que le `.xlsx`. Comparé à `excel_plus` sur banc isolé ; retenu pour le diff le plus étroit. Voir §4. |
| **Couleurs d'alerte** | `kErrorRed` (#D32F2F) pour un **fond de bandeau** ou une **bordure**, qui tiennent sur n'importe quel fond ; `colorScheme.error` pour le **texte posé sur la page**, où un rouge fixe deviendrait illisible en thème sombre. Ne pas unifier les deux. |

## 4. Dépendances — la carte, vérifiée au solveur

**Fait** : 16 paquets dans les contraintes existantes, `permission_handler`
12→13, ML Kit OCR 0.15→0.16, scanner 0.4→0.5, et `compileSdk` 36→**37**
(`permission_handler` 13 est compilé contre l'API 37 ; compiler en dessous peut
produire un `NoSuchMethodError` **à l'exécution**).

**La clé de voûte `excel` — ✅ tranchée le 2026-08-10.**

`excel 4.0.6` était sa dernière version publiée (20 août 2024) et bloquait
`archive` 4, `image` 4.9 et syncfusion 34. En allant le vérifier on a trouvé
pire que de la dette : **il ne lit que le `.xlsx`**, alors que l'application lui
route aussi le `.xls` et le `.ods`. Les deux affichaient « Fichier illisible ».

Bascule vers **`excel_community`**, fork vivant à l'API identique. Comparé à
`excel_plus` sur banc isolé : les deux lisent le `.xlsx` et un vrai `.xls`
BIFF8, **aucun ne lit l'ODS**. `excel_community` retenu pour le diff le plus
étroit. Résultat : `.xls` réellement lisible, `.ods` refusé explicitement.

`archive` 3.6.1 → 4.0.9 suit, imposé par le fork. **`image` 4.9 et syncfusion
34 sont désormais débloqués** — à faire séparément, avec repassage appareil.

### ⚠️ Le piège d'archive 4, à ne jamais rouvrir

`ZipFile.decompress(output)` est le raccourci qui semble évident en 4.x. **Il
est à proscrire.** Sur `dart:io` — donc sur Android — il délègue à
`ZLibDecoder.decodeStream`, qui alimente un `ChunkedConversionSink.withCallback`
(`_zlib_decoder_io.dart:24`) : cette variante accumule tous les fragments et ne
rappelle le puits qu'à la fermeture. La bombe est intégralement décompressée
avant que la garde ne parle. Le refus reste correct, l'allocation ne l'est plus
— c'est mot pour mot l'ANR du S9. `Inflate.stream` reste donc en place.

### Le trou de tests que ça a révélé

En substituant `decompress(out)` à `Inflate.stream`, **les neuf tests de
`archive_safe` sont restés verts**. Ils vérifiaient que l'entrée est *refusée*,
jamais que la mémoire reste *bornée* — la distinction exacte qui séparait le
correctif du S9 de l'ANR qu'il devait guérir.

Nouveau test « la borne tient, pas seulement le refus », à seuil
**auto-étalonné** : il mesure d'abord le coût d'une décompression complète de
256 Mo sur la machine courante, puis exige que le refus en coûte moins du quart.
Falsifié dans les deux sens — rouge avec le sabotage (110 ms contre 293 ms), vert
sans.

Deux observables essayés et écartés, trace gardée dans le fichier : la taille de
notre propre tampon ne bouge pas (l'accumulation a lieu **dans** le sink de
zlib), et `ProcessInfo.currentRss` ne reflète pas le tas Dart sous Windows —
mesuré à −1 Mo pendant que 256 Mo étaient décompressés.

### Syncfusion 34 — refusé le 2026-08-10, et pourquoi

La bascule d'`excel` a bien levé le blocage `xml <7` qu'on lui attribuait. Un
**second blocage, absent de toutes les cartes**, est apparu à l'essai :

```
syncfusion_flutter_pdfviewer >= 33.2.13+1  →  device_info_plus ^13.1.0
                                           →  win32 ^6.0.1
file_picker 11.0.3 (dernier stable)        →  win32 ^5.9.0
```

Le franchir demanderait soit un `dependency_overrides` sur `win32`, soit
`file_picker` **12, encore en bêta** (12.0.0-beta.7). Une bêta sur le sélecteur
de fichiers d'une application qui manipule le coffre chiffré n'est pas un
compromis acceptable.

**Et le gain est nul.** Le changelog de la 34 ne porte, pour notre usage, que la
montée d'`xml` et un correctif d'horodatage sur la **signature PDF externe** —
que l'application n'utilise pas. Payer un risque réel pour zéro bénéfice n'a pas
de sens.

À rouvrir quand `file_picker` 12 sera stable : la montée sera alors gratuite.

**La leçon** : « ce paquet en bloque trois » était vrai, mais « donc le
remplacer débloque les trois » ne l'était pas. Un blocage levé peut en révéler
un autre qui était caché derrière.

`share_plus` 13 est bloqué par **`files_tech_core`**, qui épingle `^10.0.3`. Le
débloquer engage tout le portefeuille.

`package_info_plus` 10 est bloqué en amont par `file_picker` 11 (`win32 ^5.9`) —
c'est-à-dire par exactement le même nœud que syncfusion 34.

## 5. Ce qui reste — la liste de demain

> Liste unique et autoritaire. Tout ce qui est barré a été fait le 2026-08-10 ;
> gardé pour qu'on ne le rouvre pas.

### À faire, par ordre conseillé

1. **`reader_service` décompresse un EPUB sans la garde anti-bombe.**
   `decodeBytes` y est appelé sans passer par `safeEntryBytes` — c'est **le
   seul chemin de décompression de l'application** qui n'utilise pas la garde
   commune, alors qu'elle existe précisément pour ne plus dépendre du fait
   qu'on y pense. Un `.epub` piégé suffit. À traiter en premier : c'est court,
   c'est le motif « garde recopiée à la main qui se re-oublie », et le corpus
   de test sait déjà fabriquer une bombe.

2. **`trash_service.list()` lit les métadonnées sans borne.** Un JSON énorme
   déposé dans `.RFT_Corbeille/meta/` — dossier situé sur le stockage
   **partagé**, donc accessible à toute application ayant la permission — ferait
   geler l'ouverture de la corbeille. Et le mode panique l'appelle. Un plafond
   sur la taille lue suffit.

3. **E/S synchrones dans `list()` et `_existingTrashRoots()`** (`listSync`).
   Gel perceptible sur stockage lent, sur un chemin appelé aussi par le mode
   panique.

4. **`duplicate_finder_service` : appels concurrents à `find()` non gérés.**
   Deux lancements laissent des isolates orphelins qui continuent de consommer.

5. **`docxXmlToPlainText` écrit une ligne par paragraphe même vide.** Signalé
   par GPT le 2026-08-09. Comportement du service, aligné avec l'outil de
   conversion : le changer modifierait aussi la sortie de l'outil. À décider
   comme un choix produit, pas comme un correctif.

6. **Syncfusion 34** — à rouvrir **quand `file_picker` 12 sera stable**. La
   montée sera alors gratuite ; aujourd'hui elle coûte soit une bêta sur le
   sélecteur de fichiers, soit un `dependency_overrides`. Voir §4.

7. **Bump + tag.** Rien n'est bumpé, aucun tag posé. **Interdit sans demande
   explicite de Patrice.**

### Fait le 2026-08-10, ne pas rouvrir

- ~~`image` 4.9~~ — passé en **4.9.1** et `xml` en **7.0.1** sans qu'une ligne
  change : la contrainte `^4.1.3` les acceptait déjà, seul `archive` 3 les
  retenait.
- ~~L'ODS~~ — lisible, sans dépendance nouvelle. Voir §7.
- ~~A-P0a, éclater `vault_service.dart`~~ — trois modules purs, les 25 tests du
  coffre **non modifiés**. Voir §8.
- ~~Audit externe des fichiers jamais relus~~ — quatre pertes de données
  corrigées. Voir §9.

### Avant de conclure quoi que ce soit demain

Les deux téléphones sont le juge, pas la suite de tests. Le 2026-08-09 et le
2026-08-10, **huit défauts** ont été trouvés par l'appareil alors que
`flutter analyze` était à 0 et toute la suite au vert. Voir §6.

Et après tout correctif de sécurité : **remettre le défaut et vérifier que le
test rougit**. C'est ce geste qui a montré que les neuf tests de la garde
anti-bombe ne testaient pas ce qu'on croyait.

## 6. Méthode — ce que cette journée a coûté et enseigné

- **L'appareil dit ce que rien d'autre ne dit.** Trois défauts majeurs
  aujourd'hui (impasse Android 7→10, ANR, isolate non transmissible) : zéro
  trouvé par les tests, zéro par trois relectures externes, trois par le
  téléphone.
- **Deux appareils valent bien plus que deux fois un.** L'ANR ne se voyait que
  sur le S9 (4 Go) ; le S24 (8 Go) encaissait sans broncher.
- **Un fait exact n'est pas une conclusion.** « La permission est absente du
  manifeste source » était vrai ; « donc l'application ne s'en sert pas » était
  faux. C'est le manifeste **fusionné** qui compte.
- **Vérifier au solveur, pas au commentaire.** `pubspec.yaml` attribuait deux
  blocages à `win32` ; un seul l'était.
- **Écrire le test révèle ce que lire le code ne montre pas.** Trois fois :
  `safeJoin` refusait des chemins légitimes, l'ODT perdait ses titres, la garde
  STORE arrivait trop tard.
- **Une relecture externe se trompe aussi.** Sur quatre, une affirmation était
  fausse sur le fond (`raw.length` = taille totale — c'est le restant).
- **Un correctif générique juste devient faux là où les contraintes sont
  contraires.** « Ne jamais taire un échec » et « ne rien laisser échapper du
  coffre » se contredisent ; j'ai appliqué la première sans voir la seconde.
- **Vérifier qu'un patch est appliqué fait partie de l'appliquer.** L'octet NUL
  a résisté à cinq tentatives.
- **Un refactor par délégation invite à déplacer le code sans le relire.** La
  double allocation du mot de passe a été recopiée telle quelle depuis
  `vault_service.dart`, et c'est une relecture externe qui l'a vue.
- **Une relecture absente ressemble à une relecture qui n'a rien trouvé.**
  `gemini-3.1-pro-preview` a rendu un rapport vide sans message d'erreur.
  Vérifier que le fichier de sortie existe.
- **Un test vert ne dit pas lequel des deux faits il vérifie.** Ceux de
  `archive_safe` prouvaient le *refus* et personne n'avait remarqué qu'ils ne
  prouvaient pas la *borne*. Saboter le correctif et constater que rien ne
  rougit est le seul moyen de le découvrir — c'est devenu un geste systématique
  après tout correctif de sécurité.
- **Une mesure invalide est pire qu'aucune mesure.** La première mesure du temps
  de refus donnait 0 ms parce que la bombe était *honnête* : refusée au contrôle
  bon marché, elle n'atteignait jamais le chemin testé. Le chiffre était réel et
  ne mesurait rien.
- **Un seuil absolu dans un test de performance est une dette.** Celui de la
  borne s'étalonne sur la machine courante avant de juger.
- **Vérifier une affirmation chiffrée avant de l'écrire en commentaire.** Le
  contraste de `kErrorRed` a été annoncé à 5,7:1 puis calculé à 4,98:1.
- **Le thème dérivé peut trahir l'intention.** `errorContainer` en Material 3
  se dérive du `seedColor` : avec une graine bleue, un refus de sécurité
  s'affichait en saumon. Une couleur d'alerte ne se délègue pas.

## 7. Outils — pièges constatés

- `audit-ia.py --diff` attend une **plage git** (`A..B`), pas un chemin.
- GPT : `--model gpt-5.2`. ⚠ `gpt-5.1-codex-max`, le défaut, rend **404**.
- Gemini : **lister d'abord** (`--provider gemini --list`). Les modèles
  disparaissent en cours de journée : `gemini-3-pro-preview` répondait à 15 h 27
  et rendait 404 à 17 h 30. `gemini-3.5-flash` a répondu.
- `adb` sous Git Bash : préfixer par `MSYS_NO_PATHCONV=1`, sinon `/sdcard/...`
  est converti en chemin Windows.
- `aapt` : `/j/android-sdk/build-tools/35.0.0/aapt.exe`
- **Capture d'écran** : `adb -s <id> exec-out screencap -p > x.png`. C'est ce
  qui a donné le message d'erreur complet que logcat ne montrait pas — le code
  attrape l'exception et l'affiche, donc rien ne passe par le journal.
- Jeu de fichiers piégés : `scratchpad/make_corpus.py` et `make_bomb.py`,
  déployés dans `Téléchargements/rft_test_v215` sur les deux appareils.
  ⚠ La première bombe produisait 80 Mo, **sous** le plafond de 200 Mo : elle
  n'aurait rien prouvé. Calibrer au-dessus du plafond visé.

## 8. Interdits, rappelés

- Jamais de `git add -A` ; messages de commit par `git commit -F fichier`,
  jamais `-m` (les accents y effacent des fragments).
- Aucune release, aucun tag, aucun bump sans demande explicite.
- Ne jamais toucher au keystore ni à la signature.
- Ne pas régénérer de baseline pour faire passer un contrôle.
- Ne pas proposer d'ajouter une permission — la direction est le retrait.

## 7. Le 2026-08-10 en fin de journée — ODS, et la régression d'archive 4

### L'ODS est lisible

Ajouté sans une dépendance de plus : un `.ods` est un ZIP contenant du XML ODF,
comme le `.odt` que `text_extraction_service` parcourait déjà. Nouveau
`lib/services/ods_service.dart`, top-level et isolate-safe.

**Deux pièges du format, tous deux couverts par des tests :**

- **Les cellules vides sont auto-fermantes** (`<table:table-cell/>`). Le
  scanner du projet ne les voyait pas — son motif exige un `>` là où la balise
  présente un `/`. Les ignorer aurait décalé toutes les colonnes suivantes vers
  la gauche : le tableau se serait affiché **faux**, sans erreur ni message.
  `tagContents` est désormais exprimé sur `tagElements`, qui rend aussi les
  attributs et reconnaît l'auto-fermeture. Une seule implémentation, et les 24
  tests d'extraction de texte restent verts sans modification.
- **Les répétitions sont un vecteur de bombe.**
  `table:number-columns-repeated="1000000000"` tient dans quelques centaines
  d'octets. Trois gardes : plafond dur sur la valeur lue, bornes de sortie sur
  les boucles, et non-dépliage des répétitions **vides** — le cas courant,
  LibreOffice terminant ses feuilles par une ligne vide répétée 1 048 576 fois.

Vérifié sur artefact réel : un `.ods` de **825 octets** déclarant un million de
lignes et un milliard de colonnes est lu en **11 ms**, cellule vide à sa place
et accents décodés.

### ⚠️ La régression d'archive 4 — trois correctifs pour un seul défaut

**Le fait, vérifié :** jusqu'à la 3.x, `ZipDecoder().decodeBytes` levait sur des
octets qui n'étaient pas une archive. Les **quatre** sites qui décodent
s'appuyaient tous sur ce `throw`. **La 4.x ne lève plus** : elle rend une
archive vide.

Le correctif a demandé trois passes, chacune jugée suffisante avant de ne pas
l'être :

1. **`looksLikeZip`** — contrôle de signature. Nécessaire, **insuffisant** : il
   ne regarde que quatre octets.
2. **`zipDeclaresEntries` croisé avec le nombre d'entrées décodées.**
   `02_archive_corrompue.zip` du corpus vaut `PK\x03\x04` + 4 096 octets
   aléatoires : il franchit la signature et rend zéro entrée. Un fichier qui
   ouvre sur un en-tête d'entrée locale en **déclare** au moins une ; s'il ne
   s'en décode aucune, il est corrompu. Seul `PK\x05\x06` autorise une archive
   légitimement vide — les confondre ferait refuser un fichier correct.
3. **Le message d'extraction**, faux indépendamment des deux autres :
   « Extrait dans : … (0 fichier(s)) » sur fond neutre présentait un échec comme
   une réussite, et laissait un dossier vide dans les documents de l'app.

Les deux premières passes ont été trouvées **par les appareils**, pas par les
tests. Les tests reproduisent maintenant le fichier du corpus **octet pour
octet** plutôt qu'un cas plausible inventé.

### Ce que la journée entière enseigne

Le chemin des archives a demandé **cinq** correctifs en deux jours — ANR,
capture d'isolate, borne non testée, signature, croisement — et à chaque fois
`flutter analyze` était à zéro et toute la suite au vert avant que l'appareil ne
contredise.

La cause n'est pas la malchance : ce chemin a beaucoup d'états d'échec, et la
tentation est de déclarer une garde suffisante dès qu'elle attrape le cas qu'on
avait en tête. **Sur ce chemin, partir du fichier réel du corpus, et faire
reconstater sur appareil après chaque correctif même quand il paraît évident.**

## 8. A-P0a — le coffre éclaté, le 2026-08-10

`vault_service.dart` passe de **1 451 à 1 304 lignes**. Trois modules purs dans
`lib/services/vault/` : `vault_bytes.dart` (47 l.), `vault_blob.dart` (133 l.),
`vault_kdf.dart` (199 l.).

**Les 25 tests d'intégration du coffre n'ont pas été modifiés**, conformément à
la règle. La façade `VaultService` garde une API publique identique : le
refactor est une **délégation**, les méthodes privées conservent leur signature
et leur corps appelle les fonctions top-level extraites.

### Ce que l'extraction a rendu testable — la vraie raison de la faire

Deux mécanismes de sécurité étaient inatteignables par un test :

- **`calibrateArgon2Params`** décide des paramètres Argon2id du coffre **à
  vie** : écrits au setup, jamais recalculés. Une sous-évaluation affaiblit le
  coffre sans message, sans symptôme, et un attaquant qui en profite ne se
  manifeste pas. **Zéro test** avant, onze après — dont la **monotonie** (un
  appareil plus rapide ne doit jamais recevoir de paramètres plus faibles) et
  un balayage de −100 à 5 000 ms qui exclut tout trou d'arrondi.
- **Le refus du repli v1** sur un coffre v2-only protège contre la substitution
  d'un `.enc` v2 par un blob v1 forgé, lequel contournerait la liaison de l'AAD
  au nom de fichier. Il lisait un champ statique depuis une méthode privée :
  l'atteindre demandait de monter un coffre complet. `v2Only` est désormais un
  **paramètre**. Trois tests, dont celui qui prouve que le blob refusé est
  **par ailleurs parfaitement valide** — sans quoi le test passerait pour la
  mauvaise raison.

Falsifié : en neutralisant le refus, deux tests virent au rouge.

### Doublons supprimés

- `_readU32be` et `_readInt32be` : **deux corps distincts, mot pour mot
  identiques**. Rien ne le signalait, et rien n'empêchait qu'un correctif
  appliqué à l'un manque à l'autre.
- Le contrôle du magic `RFT2`, recopié **à la main quatre fois**, chacun avec
  ses index.
- La construction de l'AAD, **triplée**.

### ⚠️ Le défaut trouvé par la relecture externe

GPT (`gpt-5.2`) n'a rien trouvé et a confirmé l'équivalence sémantique point par
point, y compris que l'arrondi flottant du calibrage est identique à l'original
dans tous les cas.

**Gemini a trouvé un défaut réel et préexistant** :

```dart
Uint8List.fromList(utf8.encode(password))
```

`utf8.encode` rend **déjà** un `Uint8List`, neuf et mutable à chaque appel —
vérifié à l'exécution, l'analyseur le confirmant aussi statiquement
(« Unnecessary type check »). L'envelopper allouait donc une **seconde** copie
du mot de passe en clair, et le `finally` ne remettait à zéro que celle-là. La
première restait en mémoire jusqu'au passage du ramasse-miettes.

Corrigé aux deux sites de dérivation. Les autres usages du même motif
construisent l'**AAD** — donnée publique, aucun secret à effacer — et sont
laissés tels quels.

**La leçon** : ce défaut a été recopié tel quel depuis l'original sans être vu,
parce qu'un refactor par délégation invite à déplacer le code sans le relire.
La relecture externe sert précisément à ça.

### `gemini-3.1-pro-preview` a échoué en silence

Le script a rendu « prompt de 15 507 caractères » puis **aucun rapport**, sans
message d'erreur. `gemini-3.5-flash` a répondu. Vérifier que le fichier de
sortie existe avant de conclure qu'une relecture n'a rien trouvé — une relecture
absente et une relecture vide se ressemblent beaucoup.

## 9. Le 2026-08-10, fin de journée — audit externe des fichiers jamais relus

Huit fichiers n'avaient **jamais** été relus par un tiers. Passés à GPT
(`gpt-5.2`) avec une consigne ciblée sur la perte de données, les états d'échec
silencieux, les ressources et la concurrence. Chaque constat a été vérifié dans
le code avant d'agir.

### Le motif qui revient partout : tester puis agir

Quatre défauts distincts, une seule cause. Le code vérifiait qu'un chemin était
libre, **puis** agissait — en laissant entre les deux une fenêtre où tout peut
changer.

| Où | Ce qui pouvait arriver |
|---|---|
| `atomic_write` | Temporaire au nom **fixe** `'$path.tmp'`. Deux écritures concurrentes vers le même chemin le partageaient : fichier final mélangé ou tronqué. C'est le point d'écriture du coffre, de la corbeille, des conversions et des sauvegardes. |
| `trash_service._newId` | Identifiant choisi, dossier créé plus tard. Deux suppressions dans la même milliseconde obtenaient le **même** — et `deleteForever` fait un `delete(recursive: true)` dessus. Supprimer un élément en effaçait **deux**. |
| `trash_service.restore` | Chemin libre choisi, déplacement plus tard. Un fichier apparu entre-temps était **écrasé** par `rename`. Il n'était même pas dans la corbeille. |
| `output_storage_service` | `create()` sans exclusivité, et un timestamp à la **seconde** — alors qu'un commentaire affirmait qu'il incluait les millisecondes. Deux fichiers générés dans la même seconde s'écrasaient. |

Partout, le correctif est le même : **réserver** au lieu de tester —
`create(exclusive: true)`, ou création du dossier au moment où l'identifiant est
choisi.

### Le mode panique annonçait un succès non vérifié

Trois drapeaux. `prefs.remove()` rend un booléen et ne lève pas ; sa valeur
était jetée. Les échecs de suppression des temporaires étaient avalés.
`recentsCleared` était posé inconditionnellement alors qu'il **dépend** de
l'étape précédente.

Sur un effacement d'**urgence**, un rapport qui rassure à tort est le pire
résultat possible : l'utilisateur cesse de s'inquiéter. Vérification
indépendante ajoutée — ce qui compte n'est pas que chaque appel ait réussi,
c'est qu'il ne **reste** rien.

### Deviner la version d'Android menait à une impasse

`storage_access` posait `_sdk = 30` quand le canal échouait — alors que sa
propre documentation annonçait `-1`. Supposer est un piège **symétrique** :
supposer 30 sur Android ≤ 10 interroge une permission qui n'existe pas — c'est
l'impasse exacte du S9 le 2026-08-09 — et supposer ≤ 10 sur Android 13+ demande
une permission sans effet.

Aucune valeur par défaut n'est bonne. Version inconnue, on interroge **les
deux** modèles : celui qui existe répondra.

### ⚠️ Le défaut que le correctif a introduit

Le suffixe d'identifiant de corbeille était d'abord **hexadécimal**, alors que
`_idPattern` n'accepte que des chiffres — l'identifiant alimentant des chemins
de fichiers. `list()` rejetait donc **toutes** les entrées : la corbeille serait
apparue vide, fichiers invisibles.

Les tests l'ont dit immédiatement. Sans eux, le correctif d'une perte de
données en aurait causé une pire.

### Ce qui reste de cet audit, non traité

1. `trash_service.list()` lit les métadonnées **sans borne** : un JSON énorme
   déposé par un tiers dans `.RFT_Corbeille/meta/` ferait geler la liste — et le
   mode panique, qui l'appelle.
2. E/S **synchrones** dans `list()` et `_existingTrashRoots()` : gel perceptible
   sur stockage lent.
3. `duplicate_finder_service` : appels concurrents à `find()` non gérés →
   isolates orphelins.
4. `reader_service` : `decodeBytes` sur un EPUB sans passer par la garde
   anti-bombe de `archive_safe`.

Les quatre sont réels et moins graves que ce qui précède. Le 4 mérite d'être
regardé en premier : c'est le seul chemin de décompression qui n'utilise pas la
garde commune.
