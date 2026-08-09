# Consolidation v2.15 — Read Files Tech

**Date** : 2026-08-09 · **Base** : `c89b820` (v2.14.0) · **Périmètre** : `lib/` (79 fichiers,
21 267 lignes), `android/`, workflows, documents légaux.

Ce rapport répond au prompt `prompts/PROMPT-CONSOLIDATION-V2.15.md`. Il commence par le verdict
sur les 23 points du plan §8 de l'audit du 2026-08-02, puis expose mes propres constats, les
corrections, et ce que j'ai délibérément laissé de côté.

**Chiffres de départ et d'arrivée**

| | Avant | Après |
|---|---|---|
| Tests | 7 fichiers, 592 lignes, **0 sur le coffre** | 10 fichiers, **89 tests**, dont **25 sur le coffre** |
| `flutter analyze` | 0 issue | 0 issue |
| Tests exécutés en CI | jamais bloquants (`\|\| echo`) | bloquants |
| APK publié | 1 universel étiqueté « arm64-v8a » | 3 par ABI + 1 universel, chacun avec son SHA-256 |

---

## 1. Le constat qui change le modèle de menace

L'audit du 2026-08-02 atténuait deux fuites réseau (S-M2, V-M4) par le même raisonnement :
« l'app n'a pas la permission `INTERNET` en release ». **C'est faux, et je l'ai mesuré sur
l'artefact.**

`aapt dump permissions` sur l'APK release **publié** de la v2.14.0, et sur un APK reconstruit
depuis `HEAD` :

```
android.permission.ACCESS_NETWORK_STATE
android.permission.INTERNET
android.permission.MANAGE_EXTERNAL_STORAGE
android.permission.READ_EXTERNAL_STORAGE  maxSdkVersion=32
android.permission.WRITE_EXTERNAL_STORAGE maxSdkVersion=29
android.permission.READ_MEDIA_{IMAGES,VIDEO,AUDIO}
android.permission.CAMERA
android.permission.REQUEST_INSTALL_PACKAGES
```

Ni `INTERNET` ni `ACCESS_NETWORK_STATE` ne figurent dans `AndroidManifest.xml`. Le rapport de
fusion des manifestes les impute à `com.google.android.datatransport:transport-backend-cct:2.3.3`,
le transport de télémétrie de Google tiré par ML Kit. L'APK embarque aussi les composants qui
l'activent : `TransportBackendDiscovery`, `JobInfoSchedulerService`,
`AlarmManagerSchedulerBroadcastReceiver`.

**Conséquence directe** : les deux fuites étaient exploitables sur l'app distribuée, pas
théoriques. Elles sont corrigées (§3).

C'est exactement le piège annoncé au §2.2 du prompt — *« vérifie aussi le manifeste fusionné, pas
seulement la source »*. La leçon vaut au-delà : **lire le manifeste source seul aurait produit une
conclusion fausse, et rassurante.**

> Même cause que pour Pass Tech, où `transport-backend-cct` produit le même effet. C'est un motif
> de portefeuille, pas un accident local.

---

## 2. Verdict sur les 23 points du plan §8

Légende : **C** confirmé · **R** réfuté · **O** obsolète. « Corrigé » = corrigé dans cette session.

### Priorité release

| # | Point | Verdict | Preuve | État |
|---|---|---|---|---|
| 1 | V-H1 — coffre déverrouillé indéfiniment | **C** | `vault_screen.dart:60-69` : `dispose()` ne faisait que `SecureWindow.disable()` | **Corrigé** |
| 2 | V-M1/M2 — écrasements silencieux | **C** | `batch_ops_service.dart:47-48`, `file_explorer_screen.dart:588,603`, `vault_service.dart:446-449` | **Corrigé** |
| 3 | V-M3 — panic wipe oublie la corbeille | **C** | `panic_service.dart:38-122` n'importait même pas `trash_service` | **Corrigé** |
| 4 | V-M4 — Markdown charge des images distantes | **C** | `md_viewer_screen.dart:118` sans `imageBuilder` → `NetworkImage` (`markdown_component.dart:994`) | **Corrigé** |
| 5 | S-M1 — trancher `INTERNET` | **R (a) / C (b)** | (a) « UpdateService cassé en release » est faux : `INTERNET` **est** dans l'APK. (b) une lib tierce la réintroduit : confirmé, c'est `transport-backend-cct` | Documenté ; arbitrage produit ouvert (§5) |
| 6 | W-H1 — APK universel étiqueté arm64-v8a | **C** | `release.yml:64` sans `--split-per-abi`, `:85` annonce « APK arm64-v8a » | **Corrigé** |
| 7a | W-M3 — la CI masque les échecs de tests | **C** | `ci.yml:46` : `flutter test \|\| echo "No tests yet"` | **Corrigé** |
| 7b | W-M4 — release sans garde-fous | **C** | `release.yml` : ni analyze, ni test, ni contrôle tag↔pubspec | **Corrigé** |
| 7c | W-M5 — heredoc non quoté | **RÉFUTÉ** | voir ci-dessous | Non corrigé, **volontairement** |

**W-M5 mérite un développement, parce que son correctif aurait cassé la signature.** L'audit
affirme qu'un `$` ou un backtick dans `KEYSTORE_PASSWORD` serait interprété par le shell, et
recommande `<< 'EOF'`. Le shell **ne ré-analyse pas le résultat d'une expansion de paramètre** :
`$STORE_PASSWORD` est substitué une fois, et sa valeur n'est jamais re-scannée, quels que soient
les caractères qu'elle contient. Le défaut décrit n'existe pas. En revanche, quoter le heredoc
écrirait littéralement `storePassword=$STORE_PASSWORD` dans `key.properties` et **casserait la
signature de toutes les releases**. Le heredoc reste tel quel.

### Quick wins

| # | Point | Verdict | Preuve | État |
|---|---|---|---|---|
| 8 | V-L5 — `reset()` laisse un lockout | **C, et plus large** | `vault_service.dart:961-978` oubliait `_kLockoutUntilElapsed` **et les 3 clés du lockout de restauration** (F2 v2.13.0), que l'audit ne citait pas | **Corrigé** |
| 9 | Q-M6 — bug du label minify | **C** | `format_screen.dart:228` teste `_mode` ∈ {json,css,js}, qui ne contient jamais `minify` | **Corrigé** |
| 10 | C-C8 — style destructif | **C** | 3 conventions concurrentes | **Partiel** : `vault_screen` ×2, `vault_import_folder` ×1. Restent `duplicates_screen:89,213`, `global_search_screen:215` |
| 11 | C-C9 — 3 formatages de taille | **C** | copies inline identiques `global_search_screen:321`, `vault_screen:1055` | **Partiel** : 2 sur 3. `zip_viewer_screen:65` reste (§6) |
| 12 | C-C1/C7 + octet NUL | **C** | NUL brut vérifié à l'`od -c`, `zip_viewer_screen.dart:168` | **Non corrigé** (§6) |
| 13 | W-M6 — dérive de version | **C** | README 2.12.3, SECURITY.md s'arrête à v2.13.2, pubspec 2.14.0 | **Partiel** : README resynchronisé ; SECURITY.md non |

### Refactos moyens

| # | Point | Verdict | Preuve | État |
|---|---|---|---|---|
| 14 | V-H2→H4 / V-M10 — caps, streaming, `mounted` | **C** | `content_search_screen.dart:103`, `file_preview_sheet.dart:17`, `zip_creator_screen.dart:50-55` | **Corrigé** pour H2/H3/H4 ; V-M10 partiel |
| 15 | C-C12 — caps à 3 sources | **C** | `FileCaps` vs `PerfThresholds` vs inline | **Partiel** : `zipCreateTotal` ajouté à `FileCaps` ; inline résiduels non traités |
| 16 | C-C5 — `showErrorSnack` non adopté | **C** | 7 usages réels contre **19** `SnackBar(content: Text('Erreur…` inline | **Non traité** |
| 17 | A-P1b — `docx_viewer` réimplémente l'extraction | **C** | `docx_viewer_screen.dart:81` `_extractDocxStatic` vs `text_extraction_service.dart:82` `extractDocxText` | **Non traité** |
| 18 | Q-H8 — tempête de rebuilds | **C** | `content_search_screen.dart:125` : un `setState` par fichier | **Corrigé** |

### Lourd

| # | Point | Verdict | État |
|---|---|---|---|
| 19 | Q-H1 — tests du vault **avant** refonte | **C** | **Fait** : 25 tests, chacun falsifié |
| 20 | A-P0a — éclater `vault_service` (1352 l.) / `vault_screen` (1209 l.) | **C** | **Non fait, délibérément** (§6) |
| 21 | A-P1a — injection par constructeur | **C** | **Non fait** |
| 22 | W-H2 — flavor FOSS pour F-Droid | **C, aggravé** | **Non fait** : aucun dossier `fastlane/`, `productFlavors` absent de `build.gradle.kts`, et ML Kit + Syncfusion restent non-libres |
| 23 | C-C11 — i18n | **C** (info) | **Non fait** |

**Bilan : 23 points examinés, 21 confirmés, 1 réfuté (W-M5), 1 partiellement réfuté (S-M1a).
Aucun obsolète.** L'audit du 2026-08-02 était donc de très bonne tenue — mais son unique
réfutation portait sur un correctif qui aurait cassé la chaîne de signature.

---

## 3. Mes propres constats

Format : `fichier:ligne` · scénario · sévérité · CONFIRMÉ/PROBABLE.

### 3.1 Le plaintext du coffre survivait à tout — CONFIRMÉ, ÉLEVÉ

`vault_service.dart:429-439` (avant correction) purgeait `vault_decrypt`, **`share`** et
`exports`. Or `share` **n'a jamais existé** : c'était le nom supposé du staging de `share_plus`,
qui nomme ce dossier **`share_plus`** (`Share.kt:29` et `:250`, share_plus 10.1.4).

Le plugin ne partage jamais le fichier qu'on lui passe : il le **recopie** dans son dossier, publie
l'URI de la copie, et n'efface cette copie qu'au **début du partage suivant**
(`clearShareCacheFolder`, appelée en tête de `shareFiles`) — donc jamais, si l'utilisateur ne
repartage rien.

**Scénario** : l'utilisateur partage un document du coffre vers une messagerie. Une copie en clair
reste dans `cache/share_plus/`. Elle survit au verrouillage, au passage en arrière-plan, et au
**panic wipe**. Toute app disposant d'un accès au cache — ou un dump ADB, ou une sauvegarde — la
récupère.

Le commentaire qui décrivait `cache/share/` comme « le staging utilisé par share_plus » désignait
un dossier vide depuis toujours. **Même défaut, second site** : la blocklist Kotlin de
`safeCanonical` (`MainActivity.kt:63-68`) bloquait `vault_decrypt` et `share` — donc pas davantage
le vrai dossier.

### 3.2 Le partage depuis le coffre était cassé — CONFIRMÉ, MOYEN

`main.dart:250` purgeait `vault_decrypt/` **à chaque passage en arrière-plan**. Ouvrir une
share-sheet *est* un passage en arrière-plan. Le fichier déchiffré était donc supprimé pendant que
l'app destinataire s'apprêtait à le lire. (C'est V-L4, que l'audit classait en LOW ; l'effet réel
est une fonctionnalité qui échoue.)

Ce constat et le précédent sont liés : **corriger l'un sans l'autre casse le partage pour de bon.**

### 3.3 `FLAG_SECURE` restait posé sur toute l'app — CONFIRMÉ, MOYEN

`SecureWindow` est un compteur de références. `VaultScreen` appelait `enable()` à l'entrée **et** au
déverrouillage, mais `disable()` une seule fois au `dispose()`. Entrer dans le coffre, déverrouiller,
puis ressortir laissait le compteur à 1.

**Scénario** : après une seule visite au coffre, l'utilisateur ne peut plus faire **aucune capture
d'écran** dans l'application, jusqu'à la mort du process — sans message, sans explication. Le
refcount n'était pas en cause : son appariement l'était.

### 3.4 Les documents légaux décrivaient un APK qui n'existe pas — CONFIRMÉ, MOYEN

`PRIVACY.md`, `PRIVACY.fr.md` et la copie **embarquée dans l'APK** `assets/legal/PRIVACY.fr.md`
annonçaient que `REQUEST_INSTALL_PACKAGES` avait été **retirée** en v2.12.2. Elle est revenue en
**v2.13.0**. `SECURITY.md:10` le consignait correctement.

Le même mensonge dormait dans `MainActivity.kt:255-259`. **Quatre surfaces, une seule correcte** —
correctif asymétrique caractérisé.

La ligne `INTERNET` attribuait par ailleurs la permission à la vérification de mise à jour GitHub,
alors que l'application ne la déclare pas (§1). `ACCESS_NETWORK_STATE`, `READ_EXTERNAL_STORAGE` et
`WRITE_EXTERNAL_STORAGE` n'étaient citées nulle part.

Enfin, les deux copies de `PRIVACY.fr.md` avaient **divergé** sur la formulation d'une note.

### 3.5 Le lecteur EPUB : garde présente une fois sur trois — CONFIRMÉ, MOYEN

`reader_service.dart` accédait à `.content` sur **trois** entrées d'archive et n'en vérifiait la
taille que sur **une** : les chapitres (`:124`). `META-INF/container.xml` (`:67`) et le fichier OPF
(`:82`) étaient lus sans aucune borne. Or `.content` déclenche la décompression.

**Scénario** : un EPUB de quelques centaines de kilo-octets dont le `container.xml` se décompresse
en plusieurs gigaoctets (du zéro compressé atteint ~1000:1) fait tomber l'app par OOM à
l'ouverture, très en dessous du cap de 100 Mo posé sur le fichier.

**Réponse à la question du prompt « la garde couvre-t-elle les cinq sites `ZipDecoder` ? »** —
Non, elle en couvrait quatre sur cinq. Vérifié un par un, côte à côte :

| Site | Cap fichier | Cap entrée décompressée | Écrit sur disque ? |
|---|---|---|---|
| `docx_viewer_screen.dart:82` (docx) | ✅ `FileCaps.docZipped` | ✅ `:86` | non |
| `docx_viewer_screen.dart:115` (odt) | ✅ | ✅ `:118` | non |
| `text_extraction_service.dart:98` | ✅ appelant | ✅ `:113` (valeur inline) | non |
| `zip_viewer_screen.dart:47` | ✅ `FileCaps.zipViewer` | ✅ entrée + cumul | **oui** → `_safeJoin` en place |
| `reader_service.dart:60` | ✅ `FileCaps.epubFile` | ❌ **2 des 3 accès** | non |

**Zip-slip** : un seul des cinq sites écrit sur disque (`zip_viewer_screen`), et sa garde
`_safeJoin` couvre bien traversée, chemin absolu, lettre de lecteur Windows et octet NUL. **Rien à
signaler sur les quatre autres** — la question du zip-slip ne s'y pose pas.

### 3.6 La whitelist d'extensions de la WebView ne protège pas de ce qu'elle annonce — CONFIRMÉ, FAIBLE

Le commentaire G8 v2.12.1 (`html_viewer_screen.dart:50-52`) affirme que la whitelist empêche un
HTML piégé de faire `<iframe src="file:///.../db.sqlite">`. C'est faux :
`shouldOverrideUrlLoading` **renvoie `false` pour les sous-frames**
(`WebViewClientProxyApi.java:78`, `request.isForMainFrame()`). `onNavigationRequest` n'a jamais rien
pu bloquer là. La whitelist ne couvre que la navigation principale.

Impact réel limité (avec JS désactivé, une iframe affiche mais ne lit pas ; `allowFileAccessFromFileURLs`
vaut `false` par défaut), mais **le commentaire promet une propriété que le code ne garantit pas** —
deuxième famille de défauts la plus productive ici, comme annoncé.

### 3.7 Le scanner de documents n'est pas « 100 % local » — CONFIRMÉ, INFO

Vérifié en dépaquetant l'APK :

- **OCR** : modèle **embarqué** (`assets/mlkit-google-ocr-models`, 1,5 Mo,
  `libmlkit_google_ocr_pipeline.so`). La promesse tient. ✅
- **Scanner** : `play-services-mlkit-document-scanner.properties` — client **léger** des services
  Google Play, dont l'interface et le modèle sont téléchargés à la demande.

Sur un appareil sans services Play, le scanner ne fonctionne pas. La dégradation n'est pas un
plantage : `scanner_screen.dart:71` attrape et affiche `'Erreur scanner : $e'` — message opaque,
qui ne dit pas que Google Play est requis.

### 3.8 `REQUEST_INSTALL_PACKAGES` — réponse à la question du §2.1

**La permission est nécessaire à la fonctionnalité telle qu'elle est conçue.** Sur Android 8+,
l'installeur système vérifie `canRequestPackageInstalls()` **pour le paquet appelant** : sans la
permission déclarée, l'utilisateur ne peut jamais accorder le toggle « Autoriser depuis cette
source », et l'installation est refusée. Ce n'est pas contournable côté app.

Le chemin complet, tracé : `file_explorer_screen.dart:960` (tap sur `.apk`) → `_installApk:300` →
`canInstallApks` → dialogue si refusée → `installApk` → `MainActivity.kt:441` → `safeCanonical`,
contrôle du suffixe `.apk`, re-vérification de la permission, puis `ACTION_VIEW` via FileProvider.

**Une confirmation explicite existe** : l'installeur du système affiche le nom de l'application et
ses permissions, et l'installation ne se fait pas sans un second tap. Il n'y a pas de confirmation
*in-app* avant de lancer l'installeur — un tap accidentel ouvre l'écran système, mais n'installe
rien.

**Retrait possible sans perdre la fonction ?** Oui, au prix d'un tap : « Ouvrir avec » → gestionnaire
de fichiers système, qui détient la permission. C'est d'ailleurs ce que le commentaire de
`MainActivity.kt` décrivait — pour un comportement qui n'était plus le sien. **C'est un arbitrage
produit, pas un défaut** ; je ne l'ai pas tranché.

### 3.9 Syncfusion — signalé, non tranché

`THIRD_PARTY_NOTICES.md` annonçait « the following **open-source** dependencies » en incluant
`syncfusion_flutter_pdf` et `syncfusion_flutter_pdfviewer`. Syncfusion est **propriétaire** :
Community License **ou** licence commerciale, rien d'autre. Aucune source Syncfusion n'est dans le
dépôt, mais l'APK distribué embarque du Syncfusion compilé et **n'est donc pas, dans son ensemble,
un artefact Apache 2.0**. Corrigé dans la documentation, avec la formulation de PDF Tech (`9dcf032`).

**Reste à trancher, hors de mon ressort** : Read Files Tech doit disposer d'une Community License
Syncfusion enregistrée. Elle n'est pas automatique.

---

## 4. Ce que la relecture externe a trouvé — et que je n'avais pas vu

Lancée sur **mes correctifs**, conformément au §5 du prompt. GPT-5.2 a rendu 8 constats ; **quatre
étaient réels, dont deux régressions que je venais d'introduire**. Rapport intégral :
`audits_results/ia-externe/rapport-gpt-correctifs-coffre-2026-08-09.md`.

| Constat | Verdict après vérification | Suite |
|---|---|---|
| CSP contournable par un faux `<head>` en commentaire | **CONFIRMÉ, ÉLEVÉ** | Corrigé + 6 tests |
| Purge de `share_plus` casse les partages hors coffre | **CONFIRMÉ, ÉLEVÉ** | Mécanisme retiré |
| `VaultScreen` survit à son propre verrouillage | **CONFIRMÉ, MOYEN** | Corrigé |
| Test « AAD = en-tête » prouve autre chose | **CONFIRMÉ, MOYEN** | Test refait |
| Test « anti-traversée » ne restaure rien | **CONFIRMÉ, FAIBLE** | Test refait |
| `Uint8List` sans `dart:typed_data` → compilation KO | **RÉFUTÉ** | `flutter/services.dart` le réexporte ; l'ajouter fait échouer l'analyse |
| 2 autres | Observations justes, sans action | — |

**Gemini n'a pas abouti.** Environ 50 tentatives, toutes en `HTTP 503`
(« Deadline expired » / `UNAVAILABLE`). Le prompt annonce ce comportement et conseille de relancer
en boucle ; une boucle longue tourne encore. **La relecture croisée annoncée n'a donc été faite
qu'à moitié** — je le signale plutôt que de laisser croire à deux avis indépendants. Sur Agenda
Tech, GPT et Gemini avaient trouvé le défaut le plus grave *indépendamment* : l'absence du second
avis est une lacune réelle de cette session, pas un détail de procédure.

**Les deux constats les plus instructifs.**

**Ma CSP était perçable.** `injectCsp` cherchait `<head` dans le texte brut. Un document commençant
par `<!-- <head> -->` faisait insérer la balise **dans le commentaire** : CSP inerte, image distante
chargée — exactement la fuite qu'elle devait fermer. Chercher un tag par sous-chaîne, c'est parser
du HTML à la main. La balise est désormais placée en tête du document, sans chercher aucun tag.

**Deux de mes tests prouvaient autre chose que ce qu'ils annonçaient.** Le test « l'en-tête d'un
`.rftvault` est authentifié » flippait l'octet 12, `argon2_mem` — un champ qui **sert à dériver la
clé**. Le modifier casse le déchiffrement *même sans* AAD liée à l'en-tête : le test serait resté
vert après suppression de la propriété qu'il prétendait vérifier. Il vise maintenant l'octet 9
(`reserved`), qui n'est lu nulle part ailleurs.

C'est le scénario exact dont le prompt avertit — *« un de mes tests épinglait un défaut au lieu de
le détecter »*. **Ma propre falsification ne l'avait pas vu, parce qu'elle produisait bien du rouge,
pour la mauvaise raison.** Une falsification qui rougit ne prouve pas que le test vise juste.

---

## 5. Méthode — ce qui a été mesuré plutôt que raisonné

**Falsification systématique.** Chaque propriété créditée par un test a été cassée volontairement
pour vérifier que le test vire au rouge — et uniquement lui. Huit sabotages : garde v2-only (les
deux sites), AAD liée au nom de fichier, AAD = en-tête complet, repli v1, deadline monotone de
`reset()`, garde d'écrasement d'`exportFile`, garde anti-traversée, recherche de `<head>`.

**Une falsification ratée ressemble à un test inutile.** Premier essai sur la garde v2-only : le
test restait vert. Diagnostic hâtif possible — « test inutile ». En réalité, mon sabotage était
incomplet : il restait un **second** garde en amont (`_decryptMaybeIsolate` vs `_decryptAuto`). En
désactivant les deux, le test rougit. À noter pour la suite.

**Vérifié sur l'artefact** : permissions (`aapt dump permissions` sur l'APK publié **et**
reconstruit), rapport de fusion des manifestes, contenu de l'APK dépaqueté (modèle OCR, `.so`,
`.properties` Play Services), APK release installé et lancé sur le **Galaxy S9 (SM-G960F,
Android 10, API 29)** — démarrage propre, aucune exception Dart en logcat.

**Vérifié dans le code des dépendances**, jamais supposé : `share_plus` 10.1.4 (`Share.kt`),
`webview_flutter_android` 4.13.0 (`WebViewClientProxyApi.java`, `android_webview_controller.dart`),
`gpt_markdown` (`markdown_component.dart`).

**Un piège de mesure rencontré, du type annoncé au §1 du prompt.** Le cache pub contient
`gpt_markdown` **1.1.6 et 1.1.7**. J'ai lu la 1.1.6 en premier et écrit un `imageBuilder` à deux
paramètres — qui ne compile pas : `pubspec.lock` résout **1.1.7**, dont la signature en a quatre.
La compilation l'a rattrapé, mais un raisonnement sur la mauvaise version aurait pu passer.
**Toujours partir de la version résolue, pas de la première trouvée sur le disque.**

---

## 6. Ce que j'ai délibérément laissé de côté

Une liste honnête de ce qui reste, plutôt qu'un rapport qui se déclare complet.

**Non corrigés, avec la raison :**

1. **A-P0a — éclater `vault_service.dart` (1352 l.) et `vault_screen.dart` (1209 l.).** Non fait,
   **volontairement**. Le plan §8 place les tests (point 19) *avant* la refonte (point 20), et il a
   raison : les 25 tests existent depuis quelques heures. Refondre 1352 lignes de crypto dans la
   même session où l'on vient d'écrire leur filet, c'est se priver de tout recul sur le filet
   lui-même. À faire dans une session dédiée, avec ces tests comme garde-fou.

2. **Octet NUL brut dans `zip_viewer_screen.dart:168`.** Le code est fonctionnellement correct
   (c'est bien un contrôle anti-NUL), mais l'octet brut fait traiter le fichier comme **binaire**
   par git et grep — les diffs y sont invisibles en revue. Quatre tentatives de réécriture ont été
   **systématiquement annulées** : un processus externe (éditeur gardant le fichier ouvert) restaure
   le contenu, y compris quand la vérification se fait dans le même processus Python. À reprendre
   éditeur fermé. C'est un point cosmétique, mais il masque les revues futures de ce fichier.

3. **C-C5 — 19 `SnackBar` d'erreur inline** contre 7 usages de `showErrorSnack`. Confirmé, non
   traité : 19 sites mécaniques, sans risque, mais sans rapport avec la sécurité ni la perte de
   données. Le prompt ordonne « cohérence et refactos en dernier ».

4. **A-P1b — `docx_viewer_screen.dart:81` réimplémente `extractDocxText`.** Confirmé. Les deux
   implémentations sont aujourd'hui d'accord (j'ai vérifié qu'elles capent toutes deux l'entrée
   décompressée), mais elles divergeront. Refacto sans urgence.

5. **C-C8 / C-C9 partiels.** Restent `duplicates_screen.dart:89,213`,
   `global_search_screen.dart:215` pour le style destructif, et `zip_viewer_screen.dart:65` pour le
   formatage de tailles — ce dernier bloqué par le point 2.

6. **SECURITY.md** s'arrête à v2.13.2. Le README est resynchronisé, pas lui : ajouter une entrée
   v2.14.0/v2.15 relève de la release, pas de l'audit.

7. **W-H2 — F-Droid.** Aggravé plutôt que résolu : aucun dossier `fastlane/`, aucun `productFlavors`,
   et ML Kit + Syncfusion + Play Services rendent un build FOSS impossible sans flavor dédié. La
   distribution F-Droid annoncée dans le manifeste (`:34`) n'est pas atteignable en l'état. Décision
   produit.

8. **A-P1a (DI) et C-C11 (i18n)** : hors périmètre d'une consolidation.

**Non vérifiés, et je le dis :**

- Le **comportement du scanner sur un appareil sans services Google Play** n'a pas pu être testé :
  le S9 de test en dispose. Le constat §3.7 s'appuie sur la nature du paquet
  (`play-services-mlkit-document-scanner`, client léger sans modèle embarqué) et sur la lecture du
  `catch`, pas sur une exécution.
- Les **parcours UI du coffre sur appareil** (verrouillage à la sortie de l'écran, `FLAG_SECURE`
  libéré, partage qui aboutit) sont couverts par les tests unitaires et la lecture du code, mais
  n'ont pas été rejoués manuellement sur le S9 — seul le démarrage de l'APK release a été vérifié.
  **C'est le premier contrôle à faire avant de taguer.**
- **XML dans DOCX/ODT (§3.2 du prompt)** : l'extraction passe par des `RegExp` sur le texte brut,
  **jamais par un parseur XML**. Ni expansion d'entités, ni *billion laughs*, ni entité externe ne
  sont donc atteignables — il n'y a pas de résolveur d'entités. `_decodeEntities` ne traite que six
  entités littérales. **Rien à signaler**, et c'est une propriété du choix d'implémentation, pas
  une garde.
- **CSV en écriture** : `csv_safe_test.dart` couvre bien l'export — `CsvSafe.encodeSafe` est appelé
  dans `csv_editor_screen.dart:_save`. **Rien à signaler.**
- **XLSX et images** : caps en place (`FileCaps.spreadsheet`, `FileCaps.imageFile`, `ImageBounds`
  pour les dimensions déclarées). Non approfondis au-delà de la vérification des caps.

---

## 7. Commits de cette session

| Commit | Thème |
|---|---|
| `91af854` | Copie/déplacement : plus d'écrasement silencieux (3 sites, 7 tests) |
| `040e34b` | Coffre : 25 tests, verrouillage à la sortie, plaintext purgé |
| `7f006d1` | Viewers : plus de requête sortante depuis un `.md` ou un `.html` piégé |
| `0ff173b` | Mémoire : plus de lecture intégrale non bornée |
| `827dd52` | Documents légaux : permissions, Syncfusion, versions |
| `fd4ffc4` | Corrections issues de la relecture externe GPT-5.2 |
| `8f229ce` | Concurrence : gardes de réentrance, bug du label minify |
| `fc45bbb` | CI : les tests bloquent, la release publie 4 APK étiquetés juste |

---

## 8. Décisions qui vous reviennent

1. **`INTERNET` doit-elle être retirée** par `tools:node="remove"` ? Elle n'est utilisée
   légitimement que par la vérification de mise à jour GitHub de `files_tech_core`. La retirer
   supprimerait aussi la télémétrie ML Kit et rendrait « 100 % local » **vérifiable** au lieu
   qu'annoncé — au prix du check de mise à jour.
2. **`REQUEST_INSTALL_PACKAGES`** : garder l'installation en un tap, ou passer par « Ouvrir avec »
   et retirer la permission la plus lourde du portefeuille (§3.8) ?
3. **Community License Syncfusion** : à enregistrer auprès de Syncfusion (§3.9).
4. **F-Droid** : objectif réel, ou retirer la mention du manifeste (§6.7) ?
