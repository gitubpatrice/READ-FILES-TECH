# Reprise du chantier v2.15 — état au 2026-08-09

> Document de reprise. Il existe pour qu'une session repartant sans le contexte de la précédente
> sache exactement où en est le travail, ce qui reste, et ce qui a déjà été tranché — sans avoir à
> le redécouvrir ni, pire, à le refaire autrement.
>
> Le détail des constats est dans `consolidation_v2_15_20260809.md`. Ici : l'état et la suite.

## 1. État de départ

| | |
|---|---|
| Branche | `main`, arbre **propre** |
| Dernier commit | `62dc0f3` |
| Commits de la session | 20, depuis `c89b820` (v2.14.0) |
| `flutter analyze` | **0 issue** |
| `flutter test` | **101 tests au vert** (13 fichiers) |
| Version `pubspec.yaml` | **2.14.0+21400** — non bumpée, volontairement |
| Tag | aucun créé |
| Appareil de test | Galaxy S9 (SM-G960F), Android 10, API 29 — APK release validé dessus |

**Ne pas refaire** : les 23 points du plan §8 de l'audit du 2026-08-02 sont tous tranchés
(21 confirmés, 1 réfuté, 1 partiellement). Le verdict est dans le rapport §2, avec `fichier:ligne`.

## 2. Ce qui reste — par ordre de priorité

### P1 — Bloquant pour publier

1. **`fastlane/` n'existe pas.** Aucun changelog. Il en faut un par `versionCode`, en FR **et** EN
   (`fastlane/metadata/android/{fr-FR,en-US}/changelogs/<versionCode>.txt`), **cap 500 caractères**
   pour F-Droid. La v2.15 apporte beaucoup : verrouillage du coffre, garde anti-zip-bomb,
   correctif Android 7→10, « Ignorer ». Le changelog doit tenir dans 500 signes.
2. **`SECURITY.md` s'arrête à v2.13.2.** Le README a été resynchronisé sur 2.14.0, pas lui.
3. **Bump de version** — `pubspec.yaml` uniquement, et **seulement au moment de la release**.
   Read Files Tech lit sa version par `PackageInfo` : **aucune constante statique à bumper**
   (contrairement à PDF Tech et Notes Tech).

### P2 — Décisions produit, à trancher avec Patrice (ne pas décider seul)

4. **`INTERNET`** : l'APK la porte, injectée par `transport-backend-cct` (télémétrie ML Kit), et
   elle n'est **pas** déclarée dans le manifeste source. La retirer par `tools:node="remove"`
   rendrait « 100 % local » vérifiable et couperait la télémétrie — au prix du check de mise à jour
   GitHub de `files_tech_core`. Mesuré à l'`aapt dump permissions`, pas déduit.
5. **`REQUEST_INSTALL_PACKAGES`** : techniquement **nécessaire** à l'installation en un tap
   (l'installeur système vérifie `canRequestPackageInstalls()` pour le paquet appelant). Retirable
   au prix d'un tap de plus via « Ouvrir avec ». Permission la plus lourde du portefeuille.
6. **Community License Syncfusion** : à enregistrer auprès de Syncfusion. Pas automatique.
   Documenté dans `THIRD_PARTY_NOTICES.md`, **non résolu**.
7. **F-Droid** : le manifeste annonce la distribution F-Droid, mais aucun `fastlane/`, aucun
   `productFlavors`, et ML Kit + Syncfusion + Play Services rendent un build FOSS impossible sans
   flavor dédié. Objectif réel, ou mention à retirer ?

### P3 — Dette technique, sans risque fonctionnel

8. **A-P0a — éclater `vault_service.dart` (1352 l.) et `vault_screen.dart` (1209 l.).**
   C'est **maintenant** le bon moment : les 25 tests du coffre existent et sont **validés sur
   appareil**. Découpage suggéré par l'audit : `VaultCrypto`, `VaultStorage`, `VaultBackupCodec`,
   `VaultLockout`. Règle : les tests ne doivent pas être modifiés pendant la refonte — s'ils
   doivent l'être, c'est que le comportement change.
9. **C-C5** — 19 `SnackBar(content: Text('Erreur…` inline contre 7 `showErrorSnack`.
10. **A-P1b** — `docx_viewer_screen.dart:81` (`_extractDocxStatic`) réimplémente
    `text_extraction_service.dart:82` (`extractDocxText`). Les deux sont aujourd'hui d'accord —
    vérifié — mais divergeront.
11. **C-C8 / C-C9 résiduels** : `duplicates_screen.dart:89,213` et `global_search_screen.dart:215`
    (style destructif) ; `zip_viewer_screen.dart:65` (formatage de tailles).
12. **Octet NUL brut** dans `zip_viewer_screen.dart:168`. Le code est correct, mais l'octet fait
    traiter le fichier comme **binaire** par git : les diffs y sont invisibles en revue.
    ⚠ **Quatre tentatives de réécriture ont été annulées par un processus externe** (éditeur gardant
    le fichier ouvert) — les écritures ne persistaient pas, même vérifiées dans le même processus.
    **À reprendre éditeur fermé.**
13. **A-P1a (DI)** et **C-C11 (i18n)** — hors périmètre d'une consolidation.

### P4 — Non vérifiable ici

14. Comportement du **scanner sans services Google Play** : l'appareil de test en dispose.
    `play-services-mlkit-document-scanner` est un client léger — le scanner ne marchera pas, mais
    la dégradation (`catch` générique, message opaque) n'a pas pu être observée.

## 3. Méthode — ce qui a coûté cher, à ne pas réapprendre

- **Vérifier sur l'artefact, pas sur la source.** `INTERNET` et `ACCESS_NETWORK_STATE` sont dans
  l'APK et absentes du manifeste source. Lire la source seule menait à une conclusion fausse.
- **Vérifier sur l'appareil, pas sur le code.** Le défaut le plus grave de la session — explorateur
  inutilisable sur Android 7→10 — n'a été trouvé ni par l'audit, ni par **trois** relectures
  externes, ni par lecture de code. Il a fallu brancher le téléphone.
- **Une falsification qui rougit ne prouve pas que le test vise juste.** Deux tests du coffre
  passaient au rouge pour la mauvaise raison. Toujours se demander *ce que le test resterait vert
  à cacher*.
- **Une falsification ratée ressemble à un test inutile.** Premier essai sur la garde v2-only :
  test vert. Diagnostic hâtif possible. En réalité le sabotage était incomplet — il restait un
  second garde en amont.
- **Vérifier qu'un patch a réellement été appliqué fait partie de l'appliquer.** Un remplacement
  de texte a échoué silencieusement (apostrophes échappées) et un commit a affirmé corriger un
  chemin qu'il n'avait pas touché.
- **Partir de la version résolue** (`pubspec.lock`), pas de la première trouvée dans le cache pub.
  `gpt_markdown` 1.1.6 et 1.1.7 cohabitent et n'ont pas la même signature.
- **Un correctif n'est fini que quand on a cherché ses frères.** Motif dominant de ce dépôt, et
  reproduit deux fois par mes propres correctifs.

## 4. Outils

**Relectures externes** — `python ~/.claude/tools/audit-ia.py`

- GPT : `--provider gpt --model gpt-5.2` (⚠ `gpt-5.1-codex-max`, le défaut, rend **HTTP 404**)
- Gemini : `--provider gemini --model gemini-3-pro-preview` (le défaut `gemini-3.1-pro-preview`
  a rendu **503 sur plus de 90 tentatives**) — relancer en boucle, ça finit par passer.
- Prompts réutilisables : `prompts/RELECTURE-CORRECTIFS-V2.15.md` et
  `prompts/RELECTURE-DIALOGUES-BRANCHEMENTS.md`. Rapports dans `audits_results/ia-externe/`.

**Appareil** — Galaxy S9, `adb`

- ⚠ Sous Git Bash, préfixer par `MSYS_NO_PATHCONV=1`, sinon `/sdcard/...` est converti en chemin
  Windows et `adb push` échoue.
- `aapt` : `/j/android-sdk/build-tools/35.0.0/aapt.exe`
- Jeu de fichiers pièges : `<scratchpad>/rft_test/` (HTML avec faux `<head>` + iframe + ancre,
  Markdown à image distante, EPUB bombe 80 Ko → 80 Mo à en-tête menteur). **Encore présent dans
  `Téléchargements/rft_test` sur le téléphone — à retirer.**

## 5. Interdits, rappelés

- Jamais de `git add -A` ; messages de commit par `git commit -F fichier`, jamais `-m`.
- Aucune release, aucun tag, aucun bump sans demande explicite.
- Ne jamais toucher au keystore ni à la signature.
- Ne pas régénérer de baseline pour faire passer un contrôle.
- Ne pas proposer d'ajouter une permission — la direction est le retrait.
