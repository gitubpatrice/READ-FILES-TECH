# Reprise du chantier v2.15 — état au 2026-08-09, fin de journée

> Document de reprise. Il existe pour qu'une session repartant sans le contexte
> de la précédente sache où en est le travail, ce qui reste, et ce qui a déjà
> été tranché — sans le redécouvrir ni, pire, le refaire autrement.
>
> Détail des constats : `consolidation_v2_15_20260809.md` (les 23 points du plan
> d'audit) puis `consolidation_v2_15_suite_20260809.md` (décisions produit et
> dette). Ici : l'état et la suite.

## 1. État

| | |
|---|---|
| Branche | `main`, arbre **propre** |
| Commits depuis `c89b820` (v2.14.0) | **40** |
| `flutter analyze` | **0 issue** |
| `flutter test` | **140 tests verts** (16 fichiers) |
| Build release | **passe** — 35,3 / 40,5 / 42,7 Mo |
| Version `pubspec.yaml` | **2.14.0+21400** — non bumpée, volontairement |
| Tag | aucun |
| Appareils | Galaxy S9 (Android 10, **API 29**) et Galaxy S24 FE (Android 16, **API 36**), APK installé sur les deux |

## 2. ⚠️ LE POINT À FAIRE EN PREMIER DEMAIN

**Vérifier que l'extraction d'archive fonctionne sur le S9.**

Fichier : `Téléchargements/rft_test_v215/01_bombe_300Mo.zip` — 306 Ko sur le
disque, annonce 1 Ko, produit 300 Mo. L'ouvrir, puis toucher l'icône
« Extraire tout » en haut à droite.

**Attendu** : bandeau rouge « Extrait dans : … — 1 entrée(s) refusée(s) »,
sans gel, sans « Invalid argument », sans boîte « ne répond pas ».

**Pourquoi c'est le premier point.** Ce chemin a cassé **deux fois de suite**
aujourd'hui, chaque fois avec `flutter analyze` à 0 et tous les tests verts :

1. d'abord un **ANR** — la garde inflatait 200 Mo sur le thread de l'interface ;
2. puis, après correction, une fermeture d'`Isolate.run` qui **embarquait
   l'arbre de widgets** et rendait `Invalid argument(s): object is unsendable`.
   L'extraction était entièrement cassée.

Les deux ont été trouvés par l'appareil. Aucun test, aucune relecture externe —
il y en a eu trois — ne les a vus. Le correctif de la seconde est en place mais
**n'a pas encore été constaté sur appareil**.

## 3. Décisions prises, à ne pas rouvrir

| Point | Décision |
|---|---|
| **Télémétrie** | **Zéro.** Les trois composants `datatransport` de Google sont retirés du manifeste final. Vérifié à l'`aapt` sur l'APK : aucun composant de télémétrie. Vérifié au `dumpsys jobscheduler` après OCR sur les deux appareils : aucun job — alors que d'autres applications des mêmes téléphones en planifient, ce qui prouve que le contrôle sait détecter. **OCR et scanner fonctionnent toujours**, testés sur les deux. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **Gardées et déclarées.** L'application interroge l'API GitHub au lancement pour signaler les mises à jour. Les retirer casserait la vérification **en silence**. |
| Community License Syncfusion | **Reportée sciemment.** |
| F-Droid | **Pas un objectif.** |
| **Passer à Kotlin** | **Non.** Ne retirerait pas la télémétrie — ML Kit est un AAR natif, identique en Kotlin. Coût : réécriture de plusieurs mois qui reperdrait tous les défauts corrigés ces deux jours. |

## 4. Dépendances — la carte, vérifiée au solveur

**Fait** : 16 paquets dans les contraintes existantes, `permission_handler`
12→13, ML Kit OCR 0.15→0.16, scanner 0.4→0.5, et `compileSdk` 36→**37**
(`permission_handler` 13 est compilé contre l'API 37 ; compiler en dessous peut
produire un `NoSuchMethodError` **à l'exécution**).

**Bloqué, et une seule clé de voûte :**

```
excel 4.0.6  →  exige xml <7        →  bloque syncfusion 34
             →  exige archive ^3.6  →  bloque archive 4
                                     →  bloque image 4.9
```

`excel 4.0.6` **est sa dernière version publiée**. Il n'est utilisé qu'à deux
endroits : `xlsx_viewer_screen.dart:33` (lecture) et `convert_screen.dart:143`
(écriture). **Le remplacer débloquerait trois montées d'un coup** — mais c'est
un changement de fonctionnalité qui exige de revalider de vrais `.xlsx` et
`.ods` sur appareil. À décider en début de session, pas en fin.

`share_plus` 13 est bloqué par **`files_tech_core`**, qui épingle `^10.0.3`. Le
débloquer engage tout le portefeuille.

`package_info_plus` 10 est bloqué en amont par `file_picker` 11 (`win32 ^5.9`).

## 5. Ce qui reste

1. **Le test du S9** (§2).
2. **Décider du sort d'`excel`** (§4).
3. **A-P0a — éclater `vault_service.dart` (1451 l.)**, dans une session qui
   *commence* par lui. Règle : les 25 tests du coffre ne doivent pas être
   modifiés. S'ils doivent l'être, le comportement change — signal d'arrêt.
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
