## 1) Fuite de `BuildContext` / de State

### Probable — `SnackTarget` peut appeler un `ScaffoldMessengerState` démonté (crash possible)
- **Localisation :** `lib/utils/snack_utils.dart:42-82` (stockage de `ScaffoldMessengerState` puis `_messenger.showSnackBar(...)`).
- **Pourquoi ça peut casser :**
  - `SnackTarget` retient un **objet `State`** (`ScaffoldMessengerState`). Si ce `ScaffoldMessenger` n’est **pas** celui du `MaterialApp` racine (ex. `ScaffoldMessenger`/`MaterialApp` imbriqué, sous-arbre remplacé), alors au moment où l’async se termine, `_messenger` peut être **déjà `dispose()`**.
  - Dans ce cas, appeler `showSnackBar()` peut finir par déclencher un `setState()` sur un `State` démonté (en release, ça peut devenir un **`Null check operator used on a null value`** via `_element!` plutôt qu’un assert debug).
- **Impact concret du correctif :** avant, beaucoup de sites faisaient `if (!mounted) return;` *avant* `messenger.showSnackBar(...)`, ce qui évitait aussi (par effet de bord) d’appeler un messenger potentiellement mort. Maintenant on appelle le messenger même quand l’écran est parti → le risque “messenger mort” devient **réel** si le messenger n’est pas global/racine.

### À vérifier — cas “plus aucun `Scaffold` enregistré”
- **Localisation :** `lib/utils/snack_utils.dart:55-82`.
- `ScaffoldMessengerState.showSnackBar` peut échouer si, au moment de l’appel, il n’y a **aucun `Scaffold` attaché** à ce messenger (selon versions Flutter / assertions).
- Si l’utilisateur quitte vers une route “sans scaffold” (ou une phase transitoire), l’appel post-`await` peut lever au lieu d’afficher.

## 2) `if (!mounted) return;` retiré à tort (pas seulement “bandeau”)

### Confirmé — auto-partage OCR peut maintenant se déclencher après avoir quitté l’écran
- **Localisation :** `lib/screens/tools/ocr_screen.dart:74-96` (hunk `@@ -73,7 +74,7 @@`).
- **Avant :**
  - `if (!mounted) return;` était placé **avant** la SnackBar *et* avant l’auto-share.
  - Donc si l’utilisateur quittait l’écran pendant l’écriture, **aucun partage** ne se lançait.
- **Après :**
  - le `return` a disparu ; seul `setState` est conditionné (`if (mounted) setState(...)`), puis **`if (autoShare) await Share.shareXFiles(...)` s’exécute quand même**.
- **Conséquence :** l’utilisateur peut revenir en arrière / changer d’écran, et **voir surgir la feuille de partage** alors qu’il n’est plus dans l’écran OCR (régression fonctionnelle, pas juste cosmétique).

### Probable — opérations longues + logique de navigation non protégée
- **Localisation :** `lib/screens/vault/vault_import_folder_screen.dart:209+` (hunk `@@ -208,7 +209,13 @@`), fin de `_runInner()` :
  - `Navigator.of(context).pop(ok);`
- Le correctif rend *plus acceptable* le fait de quitter l’écran pendant une opération (puisqu’on veut quand même notifier). Du coup, tout `Navigator...` fait après un long traitement devient plus dangereux.
- Ici, si l’utilisateur a déjà quitté l’écran (ou si la route a été remplacée), ce `pop` sur un `context` démonté peut **lever** (ou popper autre chose si le contexte est encore référencé mais plus valide).

## 3) `setState` après `dispose()` / incohérences d’état

### Rien de clairement “cassant” trouvé dans les remplacements directs `setState(...); if (!mounted)` → `if (mounted) setState(...)`
- Les migrations visibles (ex. `lib/screens/editors/code_editor_screen.dart:232+`, `lib/screens/editors/csv_editor_screen.dart:91+`, `lib/screens/tools/zip_creator_screen.dart:92+`) vont bien dans le sens “pas de `setState` si démonté”.

### À vérifier — appels post-`await` qui continuent d’exécuter du code “non SnackBar”
- **Localisation :** `lib/screens/vault/vault_screen.dart:888-907` (hunk `@@ -901,28 +888,21 @@`) dans `_restoreBackup()` :
  - vous avez remplacé `if (!mounted) return; progressDialog.close(); ... if (!mounted) return;` par `if (mounted) progressDialog.close(); await _refresh(); ... snack.info(...)`.
- **Risque :** si `_refresh()` fait un `setState` sans garde interne, c’est maintenant atteignable même quand le widget est démonté (avant, le `return` coupait la suite).
- Même remarque pour `_exportBackup()` : `lib/screens/vault/vault_screen.dart:822-840` (hunk `@@ -834,16 +822,15 @@`) : le catch ne `return` plus avant la suite (même si ici la méthode semble se terminer juste après).

## 4) Messages affichés deux fois / affichés alors qu’ils ne devraient pas

### Confirmé (même symptôme que #2, côté UX) — actions “hors écran”
- **Localisation :** `lib/screens/tools/ocr_screen.dart:74-96`.
- Ce n’est pas une “double notif”, mais c’est un **effet visible** : une action utilisateur (partage) peut apparaître sur un autre écran, ce qui ressemble à un bug ou à un “ghost action”.

### Probable — `clear()` peut effacer une SnackBar d’une autre opération/écran
- **Localisation :** `lib/screens/vault/vault_screen.dart:777-787` (hunk `@@ -787,7 +777,7 @@`) : `snack.clear();`
- Comme `SnackTarget` vise le messenger capturé (souvent global), `clear()` peut masquer un bandeau **sans rapport** (ex. un résultat d’opération longue d’un autre écran), surtout maintenant que plus d’opérations affichent des bandeaux “après départ”.

## 5) Tests (`test/snack_target_test.dart`)

### Confirmé — un test ne prouve pas ce que son scénario “réel” prétend couvrir (messenger racine uniquement)
- **Localisation :** `test/snack_target_test.dart:40-74` (“survit à la disparition…”).
- Le test capture `SnackTarget` dans une route poussée, puis pop la route, puis appelle `captured.error(...)`.
- En pratique, `ScaffoldMessenger.of(context)` (capturé dans `SnackTarget.of`) va très probablement retourner le **`ScaffoldMessenger` du `MaterialApp`** (donc survivant), pas un messenger “de la page”.
- Donc le test prouve bien : “`SnackTarget` n’a pas besoin d’un `BuildContext` monté pour afficher”.
- Mais il **ne prouve pas** : “ça marche si le messenger capturé était lui-même démonté” (cas à risque réel avec `MaterialApp`/`ScaffoldMessenger` imbriqué).

### Confirmé — les tests ne détectent pas un retour du défaut “au niveau des appelants”
- **Localisation :** ensemble du fichier, mais typiquement `test/snack_target_test.dart:33-74`.
- Le défaut initial mentionné est aussi : des call-sites faisaient `if (!mounted) return;` *avant* d’afficher.
- Les tests ne couvrent **aucun** call-site réel (copy/move/import/etc.). Si quelqu’un réintroduit `if (!mounted) return;` dans un écran, la suite de tests peut rester verte.

### Rien trouvé — les tests de `persist: false` / couleur font bien tomber des régressions locales dans `SnackTarget`
- **Localisation :**
  - `test/snack_target_test.dart:123-147` (action non permanente)
  - `test/snack_target_test.dart:106-121` (couleurs erreur)
- Si on enlève `persist: false` dans `SnackTarget._build` (`lib/utils/snack_utils.dart:87-102`), le test “pas permanent” devrait échouer.

---

### Synthèse “ce qui casse le plus”
1) **Régression fonctionnelle confirmée :** auto-share OCR peut se lancer après avoir quitté l’écran (`ocr_screen.dart:74-96`).  
2) **Risque probable de crash :** `SnackTarget` peut invoquer un `ScaffoldMessengerState` démonté si messenger non-racine (`snack_utils.dart:42-82`).  
3) **Risque à vérifier :** suppression de `return`/garde autour de code non-UI (ex. `_refresh()`) dans `_restoreBackup` (`vault_screen.dart:888-907`).