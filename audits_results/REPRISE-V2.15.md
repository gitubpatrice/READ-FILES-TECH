# Reprise du chantier v2.15 — état au 2026-08-09, fin de journée

> Document de reprise. Il existe pour qu'une session repartant sans le contexte
> de la précédente sache où en est le travail, ce qui reste, et ce qui a déjà
> été tranché — sans le redécouvrir ni, pire, le refaire autrement.
>
> Détail des constats : `consolidation_v2_15_20260809.md` (les 23 points du plan
> d'audit) puis `consolidation_v2_15_suite_20260809.md` (décisions produit et
> dette). Ici : l'état et la suite.

## 1. État

*État au 2026-08-10, après la bascule `excel` et les correctifs d'affichage.*

| | |
|---|---|
| Branche | `main`, arbre **propre** |
| Commits depuis `c89b820` (v2.14.0) | **43** |
| `flutter analyze` | **0 issue** |
| `flutter test` | **141 tests verts** (16 fichiers) |
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

## 2 bis. Le point à faire en premier la prochaine fois

Un doute non levé : le bandeau de refus n'est apparu **qu'au second appui** sur
« Extraire tout ». L'explication probable est que le premier appui a eu lieu
pendant le chargement, quand l'icône est désactivée et ne donne aucun retour —
ni visuel, ni sonore. **Non confirmé.** Si le bandeau manque alors que la liste
des entrées est déjà affichée, ce n'est plus une question d'ergonomie mais un
défaut réel.

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

`share_plus` 13 est bloqué par **`files_tech_core`**, qui épingle `^10.0.3`. Le
débloquer engage tout le portefeuille.

`package_info_plus` 10 est bloqué en amont par `file_picker` 11 (`win32 ^5.9`).

## 5. Ce qui reste

1. **`image` 4.9 et syncfusion 34**, débloqués par la bascule d'`excel`. À faire
   dans un commit séparé, avec repassage sur les deux appareils.
2. **A-P0a — éclater `vault_service.dart` (1451 l.)**, dans une session qui
   *commence* par lui. Règle : les 25 tests du coffre ne doivent pas être
   modifiés. S'ils doivent l'être, le comportement change — signal d'arrêt.
3. **L'ODS n'est toujours pas lisible**, il est seulement refusé franchement.
   La machinerie existe pourtant déjà : `text_extraction_service.dart` sait
   parser du XML OpenDocument (`odtXmlToPlainText`) avec `archive` + `xml`, et
   un `content.xml` d'ODS n'est qu'une suite de `table:table-row` /
   `table:table-cell` / `text:p`. Aucune dépendance nouvelle ne serait requise.
   À décider comme une fonctionnalité, pas comme un correctif.
4. **Le doute du second appui** (§2 bis).
4. `docxXmlToPlainText` écrit une ligne par paragraphe même vide (signalé par
   GPT). Comportement du service, aligné avec l'outil de conversion. Le changer
   modifierait aussi la sortie de l'outil — à décider à part.
5. Bump + tag, quand vous le déciderez.

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
