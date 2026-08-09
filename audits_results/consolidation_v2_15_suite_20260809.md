# Consolidation v2.15 — deuxième session, 2026-08-09

> Suite de `consolidation_v2_15_20260809.md`, qui traitait les 23 points du plan
> d'audit du 2026-08-02. Ce document couvre ce qui restait après lui : les
> décisions produit, la dette signalée mais non traitée, et ce que la recherche
> des jumeaux a fait remonter en chemin.
>
> Il est écrit dans l'ordre qui compte : d'abord ce qui a changé pour
> l'utilisateur, puis ce que je me suis trompé à faire, puis ce que je n'ai
> délibérément pas fait.

## 1. Verdict sur la question posée

**« Retirer ou garder `INTERNET` ? » — la garder, et surtout la déclarer.**

Le document de reprise affirmait que la permission n'était qu'un effet de bord
de la télémétrie ML Kit. C'était faux, et `PRIVACY.fr.md` le répétait en toutes
lettres : « Non demandées par l'application ». L'application émet elle-même une
requête sortante, **à chaque lancement, sans action de l'utilisateur** :

| | |
|---|---|
| Déclencheur | `home_screen.dart:84`, `postFrameCallback` → `_checkUpdate()` |
| Destination | `https://api.github.com/repos/gitubpatrice/read-files-tech/releases/latest` |
| Fréquence | au plus une fois par 12 h (cache `SharedPreferences`) |
| Contenu sortant | en-tête `Accept`, adresse IP publique. Rien d'autre. |

Trois raisons de garder :

1. **`tools:node="remove"` casserait la vérification en silence.** `UpdateService`
   avale la `SocketException` et retourne `null` : aucun message, aucune trace.
   La fonction disparaîtrait sans que personne ne le voie.
2. **C'est le seul canal qui signale un correctif de sécurité.** Sideload
   uniquement, pas de magasin — et F-Droid vient d'être écarté. Couper ce canal,
   c'est laisser les installations sur une version vulnérable indéfiniment.
3. **Read Files Tech n'a jamais promis « zéro permission Internet ».** Elle n'est
   pas dans la liste du portefeuille qui le fait. Pass Tech est le précédent :
   elle déclare `INTERNET` ouvertement pour exactement le même usage.

**Le défaut n'était pas la permission, c'était qu'elle soit indéclarée.** Un
relecteur qui diffait le manifeste source concluait que l'application n'avait
aucun accès réseau. Elle est maintenant déclarée, avec son motif et
l'avertissement de ne pas la retirer sans retirer d'abord l'appel.

### L'erreur de raisonnement, parce qu'elle est instructive

J'avais écrit, plus tôt dans ce chantier, que la permission n'était « pas
demandée par l'application ». Le fait de départ était exact — elle était absente
du manifeste source. **L'inférence ne l'était pas** : Android accorde les
permissions du manifeste **fusionné**. Rien n'empêchait donc la requête de
partir, et elle partait, en s'appuyant sans le dire sur une permission apportée
par une dépendance de télémétrie.

Cette « correction » avait dégradé un document qui était juste avant elle. Elle
est corrigée, et la correction de la correction est consignée dans les deux
langues plutôt que réécrite silencieusement.

### Décisions actées

| Point | Décision | État |
|---|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | **Gardées et déclarées** | fait |
| Community License Syncfusion | **Reportée sciemment** | documentée sans être adoucie |
| F-Droid | **Pas un objectif à ce jour** | mentions retirées du manifeste et de `SECURITY.md` |
| `REQUEST_INSTALL_PACKAGES` | conservée | commentaire du manifeste corrigé |

## 2. Ce qui a été corrigé

### 2.1 — L'échec d'une opération longue était tu, précisément quand elle avait été longue

Le point d'audit C-C5 signalait une incohérence de style. En la traitant, le
défaut réel est apparu, et il n'est pas cosmétique.

Deux écritures cohabitaient. `showErrorSnack(context, e)` teste `context.mounted`
et **renonce** si l'écran est parti. Ailleurs, un `ScaffoldMessenger` était
capturé avant l'`await` — bonne intention — mais **tous** les sites le faisaient
suivre d'un `if (!mounted) return;` juste avant l'affichage, ce qui annulait
exactement ce que la capture servait à obtenir.

Conséquence : conversion PDF, copie, déplacement, import dans le coffre,
restauration de sauvegarde. **Plus l'opération durait, plus l'utilisateur avait
de chances d'avoir quitté l'écran, donc plus son échec avait de chances de ne
rien afficher du tout.**

`SnackTarget` (`lib/utils/snack_utils.dart`) capture le messager **et** le
`ColorScheme` en une ligne. Le messager appartient au `Scaffold` englobant, qui
survit à l'écran quitté.

Trouvés en chemin, dans les mêmes méthodes :

- **`setState` appelé AVANT le garde `mounted`** dans les blocs `catch` des deux
  éditeurs : une sauvegarde échouant après la fermeture de l'écran levait
  « setState() called after dispose() » *depuis un gestionnaire d'erreur*.
- **Bandeaux mixtes annoncés comme des succès** : import coffre, import dossier,
  copie/déplacement par lot, vidage de corbeille affichaient « n erreur(s) » du
  même ton qu'une réussite totale.
- **`_mergeCsv` annonçait « -1 lignes fusionnées »** quand tous les fichiers
  avaient été écartés par le cap.
- **La docstring de `showFloatingSnack` promettait `rootMessenger`**, que le code
  n'a jamais utilisé.

### 2.2 — Deux gardes anti-zip-bomb oubliées, sur le chemin d'extraction

**Le constat le plus sérieux de la session.**

Le chantier précédent avait corrigé quatre sites où la borne se fiait à
`ArchiveFile.size`, valeur lue dans l'en-tête du ZIP et donc choisie par celui
qui fabrique l'archive. **Il en restait deux**, dans `zip_viewer_screen.dart`,
et ce sont les plus exposés : ils n'affichent pas un aperçu, ils **écrivent sur
le stockage**.

| Site | Défaut |
|---|---|
| `_extractAndShare` | test sur `file.size`, puis `file.content` — décompression sans borne |
| `_extractAll` | idem par entrée, **plus** `totalExtracted += file.size` |

Le troisième est le pire : le cumul s'incrémentait de la taille **déclarée**. Une
archive annonçant 1 Ko par entrée pouvait écrire des gigaoctets sans jamais
approcher le plafond de 1 Go. **La borne cumulée était décorative.**

Corrigé : `safeEntryBytes` sur les deux sites, cumul sur `bytes.length`, cap de
l'entrée courante borné par le budget restant, validation anti-zip-slip déplacée
**avant** la décompression.

**Pourquoi ils avaient été manqués** : la recherche des jumeaux s'était faite sur
le motif « aperçu de contenu ». Ceux-ci sont sur le chemin « extraction », où le
même `.size` sert à autre chose.

### 2.3 — L'écran DOCX et le service d'extraction ne lisaient pas le même texte

Le rapport précédent notait que les deux implémentations étaient « aujourd'hui
d'accord ». **C'était faux, et vérifiable en trois lignes.** La copie de l'écran
décodait `&amp;` en premier :

```
'&amp;lt;'  →  '&lt;'  →  '<'
```

Un document contenant `&amp;lt;` — le texte littéral « &lt; » — s'affichait
« < » dans la visionneuse, alors que l'outil de conversion en `.txt` rendait
« &lt; ». Même fichier, deux textes.

Elle ignorait aussi `<w:br>`, `<w:tab>`, les entités numériques, et la signature
OLE des `.doc` Word 97-2003. Et ses échecs étaient rendus **comme du contenu** :
« Impossible de lire le document. » s'affichait dans le corps du texte,
sélectionnable.

L'extraction OpenDocument, elle aussi dans l'écran et sans aucun test, rejoint le
service. 7 tests ajoutés.

### 2.4 — Le reste

| Point | Correction |
|---|---|
| C-C8 | `duplicates_screen` écrivait sa propre confirmation de suppression avec `Colors.red` et sans `autofocus` sur « Annuler », alors que `confirmDelete` existe. C'était l'écran le plus destructeur de l'app. |
| C-C8 | « Arrêter » de la recherche globale était rouge. Interrompre une recherche ne détruit rien ; employer la couleur des actions irréversibles pour une action bénigne affaiblit le signal là où il compte. |
| C-C9 | `_formatSize` de `zip_viewer` était le **dernier** formateur de taille écrit à la main. Il ne connaissait pas le gigaoctet : une entrée de 2 Go s'affichait « 2048.00 MB ». |
| C-C9 | `_maxEntryBytes` / `_maxTotalExtractBytes` codés en dur alors que la valeur existait sous `FileCaps`. |
| Octet NUL | `zip_viewer_screen.dart` était traité comme **binaire** par git : ses diffs étaient invisibles en revue. Quatre tentatives antérieures n'avaient pas persisté ; celle-ci est vérifiée par relecture des octets depuis le disque, et le blob commité en contient zéro. |
| Causes avalées | `catch (_)` sur le calcul de hash, le renommage, l'installation d'APK, l'ouverture système, la création de dossier, la lecture d'archive. |

### 2.5 — Un crash, trouvé par la seconde relecture

`late Archive _archive` n'était affecté que par le chemin de **succès** de
`_load`. Le bouton « Extraire tout » ne dépendait que de `_isLoading`. Donc :
sur une archive corrompue, `_error` était renseigné, l'icône restait cliquable,
et `_extractAll` levait un `LateInitializationError`.

**Ouvrir un ZIP illisible puis toucher ce bouton faisait planter
l'application.** Le champ est devenu nullable — le système de types signale
désormais tout oubli — et le bouton est désactivé tant qu'aucune archive n'est
lue.

Trois autres constats de la même relecture :

- **La borne s'exprimait trop tard sur les entrées STORE.** `_CappedOutput` ne
  vérifie le plafond qu'*après* chaque écriture, et le chemin non-DEFLATE ne
  faisait qu'un seul `writeInputStream` : tout était copié avant que la borne
  n'ait voix au chapitre. Copie par tranches de 64 Ko désormais. **Aucun des six
  tests existants ne couvrait une entrée STORE** — ils construisaient tous des
  entrées compressées. Trois ajoutés.
- **Un commentaire menteur, et le trou qu'il masquait.** `_safeJoin` annonçait
  « Vérification finale avec `resolveSymbolicLinks` » et ne résolvait rien. Il ne
  *peut pas* : les dossiers n'existent pas encore quand il s'exécute. La
  résolution réelle a lieu maintenant dans `_extractAll`, après création du
  parent et avant l'écriture — plus un contrôle sur la cible elle-même, signalé
  par Gemini.
- **Feuille de partage hors écran** sur `_extractAndShare`, exactement le
  symptôme corrigé une heure plus tôt sur l'OCR.

Et un constat qui, en cherchant sa cause, s'est révélé plus large : GPT
signalait qu'un ODT « sans texte détecté » devenait une erreur là où il
affichait un écran vide. **La vraie cause était en amont : les titres
(`<text:h>`) n'étaient pas extraits du tout.** Un document structuré perdait
tous ses titres ; un plan qui n'en contient que ressortait entièrement vide.
`<text:line-break/>` et `<text:tab/>` subissaient le même sort, effacés par le
nettoyage générique — deux lignes se retrouvaient collées.

## 3. Ce que j'ai cassé, et qui l'a vu

La relecture adversariale de `gpt-5.2` sur mes propres correctifs a trouvé une
**régression fonctionnelle confirmée** que j'avais introduite.

`ocr_screen.dart` — le `if (!mounted) return;` que j'ai retiré ne protégeait pas
que le bandeau. Il protégeait aussi `if (autoShare) await Share.shareXFiles(...)`.
Après mon correctif : l'utilisateur quitte l'écran OCR pendant l'écriture du
`.txt`, et **la feuille de partage système surgit par-dessus l'écran où il se
trouve**.

C'est exactement le piège que mon propre prompt de relecture demandait de
chercher — « un `if (!mounted) return;` retiré à tort » — et que je n'ai pas vu
en l'écrivant.

Deux autres constats retenus :

- `SnackTarget` retient un `ScaffoldMessengerState`. Il appartient au
  `MaterialApp` racine dans tous les cas actuels, mais rien n'empêche un
  `ScaffoldMessenger` imbriqué qui, lui, disparaît. Garde `mounted` ajouté.
- Du code non-UI atteignable après démontage. Le constat visait `_refresh` de
  `vault_screen` : **réfuté**, il porte déjà son garde. Mais en cherchant ses
  frères, trois méthodes ouvraient bien sur un `setState` non gardé
  (`file_explorer_screen._refresh`, `trash_screen._load`,
  `duplicates_screen._scan`).

Un constat **non retenu** : `snack.clear()` masquerait un bandeau d'un autre
écran. C'était déjà le comportement de `hideCurrentSnackBar()` avant ce
chantier ; le correctif ne l'a ni créé ni aggravé.

GPT notait aussi, à raison, que mes tests capturaient tous le messager racine et
prouvaient donc « pas besoin d'un `BuildContext` monté », pas « résiste à un
messager mort ». Un septième test couvre désormais le cas.

### Ce que les relectures ont dit de faux

Une relecture qui se trompe n'invalide pas l'exercice, mais il faut le dire —
sinon on applique des correctifs à des problèmes qui n'existent pas.

- **`raw.length` renverrait la taille totale du flux** (Gemini). Non : la
  version résolue est `archive` **3.6.1**, où `input_stream.dart:73` calcule
  `_length - (offset - start)` — le **restant**. La 4.x le documente noir sur
  blanc : *« How many bytes are left in the stream »*. Le constat concluait
  d'ailleurs lui-même que le code fonctionne. Il a néanmoins servi : le point
  est désormais ancré dans le fichier, avec la ligne de la source, parce que
  l'ambiguïté du nom est réelle.
- **`_refresh` de `vault_screen` ferait un `setState` non gardé** (GPT). Non, il
  porte son garde en première ligne. Mais en cherchant ses frères, trois autres
  méthodes en manquaient — le constat était faux et utile.
- **`snack.clear()` masquerait un bandeau d'un autre écran** (GPT). C'était déjà
  le comportement de `hideCurrentSnackBar()` avant ce chantier.
- **L'écran DOCX risquerait d'être vide si `result.error` n'est pas affiché**
  (GPT). Le `build` teste `_error` avant `_text`.

Sur trois relectures, **une seule affirmation était fausse sur le fond** (celle
de Gemini), et elle était accompagnée de son propre démenti.

## 4. Vérifications

Sur l'artefact, pas sur le raisonnement.

| Contrôle | Résultat |
|---|---|
| `flutter analyze` | **0 issue** |
| `flutter test` | **121 tests verts** (13 → 14 fichiers, +20 tests) |
| `flutter build apk --release --split-per-abi` | **3 APK produits** — 35,6 / 40,8 / 43,0 Mo |
| `aapt dump permissions` sur l'APK reconstruit | **coïncide exactement** avec le manifeste source |
| `requestLegacyExternalStorage` dans le manifeste fusionné | présent |
| Blob git de `zip_viewer_screen.dart` | **0 octet NUL** |

### Falsifications conduites

Un test vert ne prouve rien tant qu'on n'a pas vu ce qu'il faut casser pour le
faire rougir.

| Sabotage | Effet attendu | Observé |
|---|---|---|
| Retirer `persist: false` du helper | 1 test rouge | conforme |
| Remettre l'ancien décodage d'entités ODT | 2 tests rouges, ciblés | conforme — les autres restent verts |
| Retirer le garde `_messenger.mounted` | 1 test rouge, le nouveau | conforme |

Les trois tests d'entrées STORE ajoutés à `archive_safe_test.dart` n'ont pas été
falsifiés par sabotage, mais par constat : **aucun des six tests préexistants ne
construisait d'entrée non compressée**, si bien que la branche corrigée n'était
couverte par rien. C'est le genre de trou qu'un compteur de tests verts ne
montre pas.

Le couple « le bandeau survit à la disparition du widget » / « `showErrorSnack`
renonce sur un contexte démonté » constitue une falsification structurelle :
même montage, même séquence, résultats opposés. C'est la différence qui prouve,
pas le vert.

## 5. Ce que je n'ai pas fait, et pourquoi

### A-P0a — le découpage de `vault_service.dart` (1451 lignes)

**Non fait, délibérément.** Le document de reprise disait « c'est maintenant le
bon moment ». Je ne le pense plus, pour une raison précise :

- C'est le seul point restant qui n'apporte **rien à l'utilisateur** — sa valeur
  est la lisibilité.
- Il touche `unlockWithPassword` et `restoreFromBackup`, les deux chemins
  résistants au brute-force, dont la logique de verrouillage est entrelacée avec
  `SharedPreferences` et l'horloge monotone à six clés.
- **J'ai introduit une régression aujourd'hui**, dans du code d'affichage, et
  seule une relecture externe l'a vue. Refactorer la cryptographie du coffre en
  fin de session longue, c'est le meilleur moyen de terminer mal une bonne
  journée.

Le moment sera bon dans une session qui commence par lui, avec revalidation sur
appareil à la fin. Les 25 tests du coffre sont là et ne doivent pas bouger
pendant l'opération — s'ils doivent l'être, c'est que le comportement change, et
c'est le signal d'arrêt.

### Le reste

| Point | État |
|---|---|
| `docxXmlToPlainText` écrit une ligne par paragraphe, même vide | Signalé par GPT. C'est le comportement du service, déjà utilisé par l'outil de conversion : les deux surfaces disent enfin la même chose, ce qui était le but. Changer ce filtrage modifierait la sortie de l'outil et mérite d'être décidé à part. |
| A-P1a — injection de dépendances | hors périmètre d'une consolidation |
| C-C11 — internationalisation | hors périmètre |
| P4 — scanner sans services Google Play | invérifiable sur l'appareil disponible |
| Relecture Gemini | `gemini-3-pro-preview` **retiré du service en cours de session** — il répondait à 15 h 27, il rendait 404 à 17 h 30. `gemini-3.1-pro-preview` sature (503). Relecture obtenue avec `gemini-3.5-flash`. **À noter pour la prochaine session : ne pas se fier au modèle inscrit dans le document de reprise, lister d'abord.** |
| Bump de version, tag, release | **interdits sans demande explicite** — non faits |
| Fichiers de test sur le téléphone | `Téléchargements/rft_test` contient toujours `secret.txt` et une bombe zip. À retirer. |

### Limites assumées des correctifs livrés

- `_extractAll` et `_extractAndShare` restent des méthodes privées d'un `State`,
  **non couvertes par un test unitaire**. C'est `safeEntryBytes` qui est testé.
  Ces commits garantissent que les deux sites l'appellent, pas qu'ils l'appellent
  correctement.
- Aucun test ne couvre les appelants réels de `SnackTarget`. Si quelqu'un remet
  un `if (!mounted) return;` dans un écran avant un bandeau, la suite reste
  verte.
- Le balayage des `catch (_)` a porté sur ceux qui **affichent un message**, pas
  sur les 81 du répertoire `lib/`. Les autres ignorent délibérément un échec sans
  conséquence.
- `_showTrashedSnack` reste écrit à la main : bandeau de marque avec couleur,
  icône de fermeture et action « Annuler ». Le faire passer par le helper aurait
  demandé d'y ajouter une variante de style pour un seul appelant.

## 6. Ce que cette session confirme sur la méthode

- **Vérifier sur l'artefact.** `aapt dump permissions` a tranché une question que
  la lecture du manifeste source avait fait trancher **à l'envers**.
- **Un fait exact n'est pas une conclusion.** « La permission est absente du
  manifeste source » était vrai ; « donc l'application n'en fait pas usage » était
  faux. Le manifeste fusionné est ce qui compte.
- **Un correctif n'est fini que quand on a cherché ses frères — et le motif de
  recherche compte.** Chercher « aperçu de contenu » a manqué deux sites
  d'extraction qui portaient exactement le même défaut.
- **Une relecture externe sur ses propres correctifs vaut son coût.** Elle a
  trouvé une régression réelle qu'aucun test ne couvrait et que je venais
  d'écrire.
- **« Les deux implémentations sont d'accord » est une affirmation à vérifier,
  pas à consigner.** Elle figurait dans le rapport précédent ; trois lignes ont
  suffi à la démentir.
