# Reprise du chantier v2.15 — état au 2026-08-09, fin de deuxième session

> Document de reprise. Il existe pour qu'une session repartant sans le contexte
> de la précédente sache où en est le travail, ce qui reste, et ce qui a déjà été
> tranché — sans le redécouvrir ni, pire, le refaire autrement.
>
> Le détail est dans `consolidation_v2_15_20260809.md` (23 points du plan
> d'audit) puis `consolidation_v2_15_suite_20260809.md` (décisions produit et
> dette). Ici : l'état et la suite.

## 1. État de départ

| | |
|---|---|
| Branche | `main`, arbre **propre** |
| Commits depuis `c89b820` (v2.14.0) | **31** |
| `flutter analyze` | **0 issue** |
| `flutter test` | **121 tests verts** (14 fichiers) |
| `flutter build apk --release --split-per-abi` | **passe** — 35,6 / 40,8 / 43,0 Mo |
| Version `pubspec.yaml` | **2.14.0+21400** — non bumpée, volontairement |
| Tag | aucun créé |
| Appareil de test | Galaxy S9 (SM-G960F), Android 10, API 29 |

**Ne pas refaire** : les 23 points du plan §8 de l'audit du 2026-08-02 sont tous
tranchés (21 confirmés, 1 réfuté, 1 partiellement). Les décisions produit sont
tranchées aussi — voir §2.

## 2. Décisions prises, à ne pas rouvrir

| Point | Décision |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | **Gardées, et déclarées explicitement au manifeste.** L'application s'en sert elle-même : `AppUpdate.checkForUpdate()` interroge l'API GitHub **au lancement**. Les retirer par `tools:node="remove"` casserait la vérification **en silence** (`UpdateService` avale la `SocketException`). |
| Community License Syncfusion | **Reportée sciemment.** L'APK embarque du code propriétaire compilé dont la licence n'est pas formellement acquise. Dit sans adoucissement dans `THIRD_PARTY_NOTICES.md`. |
| F-Droid | **Pas un objectif à ce jour.** Mentions retirées du manifeste et de `SECURITY.md`. Le dossier `fastlane/` est conservé — il alimente les notes de release GitHub. |
| `REQUEST_INSTALL_PACKAGES` | Conservée. |

## 3. Ce qui reste

### P1 — Avant de publier

1. **Bump de version** — `pubspec.yaml` **uniquement**, et **seulement au moment
   de la release**. Read Files Tech lit sa version par `PackageInfo` : aucune
   constante statique à bumper, contrairement à PDF Tech et Notes Tech.
2. **Renommer les changelogs fastlane** si le numéro retenu n'est pas 2.15.0 —
   ils sont écrits pour le `versionCode` **21500**. Les **deux** (fr-FR et en-US),
   pas seulement l'un.
3. **Retirer les fichiers de test du téléphone** : `Téléchargements/rft_test`
   contient encore `secret.txt` et une bombe zip.

### P2 — Dette technique

4. **A-P0a — éclater `vault_service.dart` (1451 l.) et `vault_screen.dart`.**
   **Reporté délibérément**, et la raison compte plus que le report :

   - c'est le seul point restant qui n'apporte **rien à l'utilisateur** ;
   - il touche `unlockWithPassword` et `restoreFromBackup`, les deux chemins
     résistants au brute-force, dont la logique de verrouillage est entrelacée
     avec `SharedPreferences` et l'horloge monotone sur **six clés** ;
   - une régression a été introduite dans du code d'**affichage** au cours de
     cette session, et seule une relecture externe l'a vue.

   Le bon moment est une session qui **commence** par lui, avec revalidation sur
   appareil à la fin. **Règle** : les 25 tests du coffre ne doivent pas être
   modifiés pendant l'opération. S'ils doivent l'être, c'est que le comportement
   change — signal d'arrêt.

5. **`docxXmlToPlainText` écrit une ligne par paragraphe, même vide.** Signalé
   par GPT. C'est le comportement du service, déjà utilisé par l'outil de
   conversion ; la visionneuse s'y aligne désormais, ce qui était le but. Filtrer
   les paragraphes vides changerait aussi la sortie de l'outil — à décider à
   part.
6. **A-P1a (injection de dépendances)** et **C-C11 (i18n)** — hors périmètre
   d'une consolidation.

### P3 — Non vérifiable ici

7. Comportement du **scanner sans services Google Play** : l'appareil de test en
   dispose.

## 4. Méthode — ce qui a coûté cher, à ne pas réapprendre

- **Vérifier sur l'artefact, pas sur la source.** `aapt dump permissions` a
  tranché une question que la lecture du manifeste source avait fait trancher
  **à l'envers**.
- **Un fait exact n'est pas une conclusion.** « La permission est absente du
  manifeste source » était vrai ; « donc l'application n'en fait pas usage » était
  faux. Android accorde ce que porte le manifeste **fusionné**.
- **Vérifier sur l'appareil, pas sur le code.** Le défaut le plus grave du
  chantier — explorateur inutilisable sur Android 7→10 — n'a été trouvé ni par
  l'audit, ni par **trois** relectures externes. Il a fallu brancher le téléphone.
- **Un correctif n'est fini que quand on a cherché ses frères — et le motif de
  recherche compte.** Chercher « aperçu de contenu » a manqué deux sites
  d'extraction portant exactement le même défaut de zip-bomb.
- **« Les deux implémentations sont d'accord » est à vérifier, pas à consigner.**
  Cette phrase figurait dans le rapport précédent ; trois lignes l'ont démentie.
- **Une falsification qui rougit ne prouve pas que le test vise juste.** Toujours
  se demander *ce que le test resterait vert à cacher*.
- **Faire relire ses propres correctifs vaut son coût.** Une régression réelle,
  qu'aucun test ne couvrait, a été trouvée ainsi — ainsi qu'un crash.
- **Une relecture externe se trompe aussi.** Sur trois, une affirmation était
  fausse sur le fond. Vérifier dans la source de la dépendance **résolue**, pas
  croire sur parole.
- **Vérifier qu'un patch a réellement été appliqué fait partie de l'appliquer.**
  L'octet NUL a résisté à cinq tentatives ; seule une relecture des octets
  **depuis le disque**, après écriture, l'a confirmé.

## 5. Outils — pièges constatés

**Relectures externes** — `python ~/.claude/tools/audit-ia.py`

- `--diff` attend une **plage git** (`A..B`), pas un chemin de fichier.
- GPT : `--provider gpt --model gpt-5.2`. ⚠ `gpt-5.1-codex-max`, le défaut, rend
  **404**.
- Gemini : **lister d'abord** (`--provider gemini --list`). Le modèle change sous
  les pieds : `gemini-3-pro-preview` répondait à 15 h 27 le 2026-08-09 et rendait
  **404** à 17 h 30. `gemini-3.1-pro-preview` sature en 503.
  `gemini-3.5-flash` a répondu.
- Prompts réutilisables dans `prompts/`. Rapports dans `audits_results/ia-externe/`.

**Appareil** — Galaxy S9, `adb`

- ⚠ Sous Git Bash, préfixer par `MSYS_NO_PATHCONV=1`, sinon `/sdcard/...` est
  converti en chemin Windows et `adb push` échoue.
- `aapt` : `/j/android-sdk/build-tools/35.0.0/aapt.exe`

## 6. Interdits, rappelés

- Jamais de `git add -A` ; messages de commit par `git commit -F fichier`, jamais
  `-m` (les accents y effacent des fragments).
- Aucune release, aucun tag, aucun bump sans demande explicite.
- Ne jamais toucher au keystore ni à la signature.
- Ne pas régénérer de baseline pour faire passer un contrôle.
- Ne pas proposer d'ajouter une permission — la direction est le retrait.
