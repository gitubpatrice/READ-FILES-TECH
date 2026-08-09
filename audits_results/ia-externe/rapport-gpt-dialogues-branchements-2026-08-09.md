## 1) Dialogues (`showDialog`, `showModalBottomSheet`, `SnackBar`)

### [MOYEN — CONFIRMÉ] Bouton « Ignorer » inopérant (dialogue de sortie) → impossible de quitter sans sauvegarder
- **CodeEditor** : `lib/screens/editors/code_editor_screen.dart:249-278`
- **CSV Editor** : `lib/screens/editors/csv_editor_screen.dart:180-208`
- **Scénario utilisateur**
  1. L’utilisateur modifie un fichier.
  2. Appuie sur “retour”.
  3. Choisit **« Ignorer »** (quitter sans sauvegarder).
  4. Résultat : l’écran **ne se ferme pas** (le dialogue se ferme, mais rien ne pop ensuite).
- **Cause**
  - Le bouton « Ignorer » fait `Navigator.pop(..., false)` puis le code appelle `nav.pop()` **uniquement si** le résultat est `true` (`CodeEditor:286-289`, `CsvEditor: onPopInvokedWithResult` similaire).
  - Donc « Ignorer » = `false` ⇒ jamais de pop.

---

### [ÉLEVÉ — CONFIRMÉ] `showModalBottomSheet` : `BuildContext` (du bottom sheet) utilisé après un `await` → pop potentiellement la mauvaise route
- `lib/screens/editors/code_editor_screen.dart:170-200` (callback “Restaurer”)
- **Scénario utilisateur**
  1. Ouvre **Historique**.
  2. Tape sur **« Restaurer »** d’une sauvegarde.
  3. Avant la fin de `await f.readAsString()`, il **ferme la bottom sheet** (swipe down / back).
  4. À la reprise, le code fait `final nav = Navigator.of(ctx); ... nav.pop();`.
  5. Résultat possible : **pop d’une autre route que la bottom sheet** (ex. l’éditeur lui‑même), ou exception liée à un contexte désactivé.
- **Cause**
  - `ctx` est le `BuildContext` de l’item de bottom sheet (potentiellement démonté), mais la garde ne vérifie que `mounted` du `State` parent, pas `ctx.mounted`.

---

### [ÉLEVÉ — CONFIRMÉ] Dialogues de progression (backup export/restore) : contrôleur basé sur un `ctx` stocké → crash/pop erroné si le dialogue est fermé (back) ou si `close()` est appelé 2×
- `lib/screens/vault/vault_screen.dart:790-835`, `839-923`, `927-969`, `1146-1158`
- **Scénarios utilisateurs**
  - **A. Fermeture par “retour” pendant la progression**
    1. L’utilisateur lance **Exporter le coffre** ou **Restaurer un coffre**.
    2. Le dialogue de progression apparaît (`barrierDismissible: false`), mais **le bouton retour Android peut quand même fermer le dialog**.
    3. Le job continue et appelle `progressDialog.refresh()` (`:819-822` / `:893-896`) alors que la `StatefulBuilder` du dialog n’existe plus.
    4. Résultat : **`setState` sur widget démonté** (crash/erreur Flutter) ou comportements erratiques.
  - **B. `close()` appelé alors que le contexte du dialog n’est plus valide**
    - `close()` fait `Navigator.of(ctx)` sur un `ctx` potentiellement démonté (`:1152-1156`) ⇒ risque d’exception (“deactivated widget”) ou de pop d’une mauvaise route.
  - **C. `close()` potentiellement appelé deux fois**
    - Dans `_exportBackup`, `progressDialog.close()` est appelé en succès (`:825`) puis **à nouveau** en `catch` (`:832`) si `Share.shareXFiles` throw (`:827-829` est dans le `try`).
    - Si le dialog est déjà fermé, le 2e `close()` peut viser autre chose (ou planter) car il repose sur un `BuildContext` stocké.

---

### [MOYEN — CONFIRMÉ] Setup coffre : dialogue de progression non “verrouillé” au retour arrière + `pop(rootNavigator)` → risque de `pop` de la mauvaise route
- `lib/screens/vault/vault_screen.dart:277-316`
- **Scénario utilisateur**
  1. L’utilisateur lance “Créer le coffre” → dialog de progression affiché.
  2. Il appuie sur **retour** (Android) : le dialog peut être fermé par back (même si `barrierDismissible: false`).
  3. Quand `setupWithPassword` finit, le code exécute `Navigator.of(context, rootNavigator: true).pop();` (`:304`) en pensant fermer le dialog.
  4. Résultat possible : **pop de l’écran VaultScreen** (ou d’une route au‑dessus), puisque le dialog n’est plus sur la pile.
- **Remarque**
  - Ici le risque vient du couple “dialog non annulable proprement” + “dialog néanmoins fermable par back” + “pop rootNavigator sans vérifier que c’est bien le dialog”.

---

### [ÉLEVÉ — HYPOTHÈSE] Confirmation “Mode panique” : pas d’autofocus explicite sur « Annuler »
- `lib/screens/settings_screen.dart:76-110`
- **Scénario utilisateur**
  - Sur certains dispositifs/navigation clavier (ou si un focus implicite se place sur le bouton tonal), “Entrée” pourrait activer **« Effacer tout »**.
- **Pourquoi HYPOTHÈSE**
  - Le focus par défaut dépend du contexte (plateforme, mode d’accessibilité). Le code ne force pas un focus sûr.

---

## 2) Branchements (wiring)

### Rien à signaler (sur les fichiers fournis)
- Pas vu de **callbacks reçus et ignorés** ni de routes manifestement mortes dans ces extraits.
- `Navigator.push<int>` de l’import dossier est **géré** (retour `imported`) : `lib/screens/vault/vault_screen.dart:770-785`.

(Le point “Ignorer” inopérant est traité plus haut car c’est un wiring *dialogue → retour*.)

---

## 3) Interactions & cycle de vie (réentrance, `setState` après `dispose`, etc.)

### [ÉLEVÉ — CONFIRMÉ] `setState` après `dispose` lors du chargement initial (éditeur code)
- `lib/screens/editors/code_editor_screen.dart:73-91`
- **Scénario utilisateur**
  1. Ouvre l’éditeur sur un fichier (ou lance le picker puis sélectionne un fichier).
  2. Quitte immédiatement l’écran (retour) pendant `readAsString()`.
  3. Quand la lecture finit, `_load()` appelle `setState(() => _isLoading = false);` (`:82` / `:84`) **sans guard `mounted`**.
  4. Résultat : **exception Flutter** (setState after dispose).

### [ÉLEVÉ — CONFIRMÉ] `setState` après `dispose` lors du chargement initial (éditeur CSV)
- `lib/screens/editors/csv_editor_screen.dart:53-69`
- **Scénario utilisateur identique**
  - `_load()` fait `setState(...)` (`:57-60` puis `:62`) sans vérifier `mounted` après les `await`.

---

### [ÉLEVÉ — CONFIRMÉ] Vault content : `_refresh()` fait un `setState` immédiat → crash si appelé après un `await` alors que l’écran a été quitté
- `lib/screens/vault/vault_screen.dart:584-592` (implémentation)
- **Appels problématiques**
  - Import : `await _refresh();` sans vérifier `mounted` *avant* d’appeler (`:641-642` ne protège qu’après-coup)
  - Delete : `_refresh();` non awaited (`:753-755`)
- **Scénarios utilisateurs**
  - **Import** :
    1. Lance un import de plusieurs fichiers (opération longue).
    2. Quitte l’écran coffre pendant l’import.
    3. Fin d’import → `await _refresh()` démarre, exécute `setState(() => _loading = true)` (`:585`) alors que le `State` est démonté.
    4. Résultat : **setState after dispose**.
  - **Suppression** :
    1. Supprime un fichier du coffre.
    2. Appuie sur retour pendant `await widget.service.deleteFile(enc)` (`:753`).
    3. À la fin, `_refresh()` est appelé (`:754`) et fait `setState` immédiatement (`:585`) → **crash**.

---

### [MOYEN — CONFIRMÉ] Réentrance : `_scan()` n’a pas de garde synchronisée → double-tap peut lancer 2 scans
- `lib/screens/tools/scanner_screen.dart:25-38`
- **Scénario utilisateur**
  1. Double-tap très rapide sur **« Lancer le scan »**.
  2. Avant que le rebuild ne désactive le bouton, `_scan()` peut être appelé 2 fois.
  3. Résultat possible : **deux `DocumentScanner` en parallèle**, comportements plugin instables, erreurs ou UI incohérente.
- **Cause**
  - Pas de `if (_busy) return;` au tout début de `_scan()` (la désactivation UI arrive “après”, au prochain build).

---

## Zones explicitement saines (dans les extraits fournis)

- **`ConvertScreen`** : rien à signaler côté réentrance / `mounted` / dialogues (garde `_busy` posée avant `await`, callbacks UI désactivés quand occupé).
- **`VaultImportFolderScreen`** : rien à signaler côté réentrance (garde `_running` posée avant le 1er `await`, reset en `finally`), et pas de `setState` évident après `dispose` dans les chemins principaux.  
- **Gestion des retours `showDialog<bool>`** : dans plusieurs endroits sensibles, `null` est correctement traité comme “non” (`res ?? false`, `!= true`).