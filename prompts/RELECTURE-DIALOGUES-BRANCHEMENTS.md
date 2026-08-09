# Relecture ciblée — dialogues, branchements, interactions (Read Files Tech)

Read Files Tech : application Flutter/Dart **Android uniquement** (explorateur de fichiers,
visionneuses, OCR, scanner, coffre-fort chiffré, corbeille, conversion). Code et commentaires en
français. Distribuée hors Play Store. State management 100 % `setState`, aucune injection de
dépendances, singletons `.instance`.

Trois axes ont déjà été audités ailleurs et **ne t'intéressent pas ici** : la cryptographie du
coffre, les caps mémoire anti-OOM, et les fuites réseau des visionneuses. Ne les re-signale pas.

Ce que je veux, c'est **ce que personne n'a regardé** : les dialogues, les branchements et les
interactions.

## 1. Dialogues (`showDialog`, `showModalBottomSheet`, `SnackBar`)

- **Action destructive sans confirmation**, ou confirmation dont le bouton par défaut / autofocus
  est l'action destructive plutôt que « Annuler ».
- **`BuildContext` utilisé après un `await`** sans garde `mounted` / `context.mounted` — le
  dialogue s'ouvre sur un arbre démonté, ou `Navigator.pop` dépile la mauvaise route.
- **`Navigator.pop` en trop ou en moins** : dialogue qui ferme l'écran appelant, double `pop` sur
  double-tap, dialogue non refermé sur un chemin d'erreur.
- **`TextEditingController` / `FocusNode` créés pour un dialogue et jamais `dispose()`**.
- **Valeur de retour non gérée** : `showDialog<bool>` dont le `null` (retour arrière système,
  tap hors du dialogue) est traité comme un « oui ».
- **Dialogue qui ment** : le texte annonce une chose, le code en fait une autre (nombre d'éléments,
  irréversibilité, emplacement de destination).
- **Barrière** : `barrierDismissible` laissé à `true` sur un dialogue de progression qu'on ne peut
  pas annuler proprement.

## 2. Branchements (wiring)

- **Callback déclaré et jamais appelé**, ou paramètre `onX` reçu et ignoré.
- **Bouton / `IconButton` / entrée de menu sans action, ou dont l'action ne fait rien** dans un
  cas particulier (état vide, chargement en cours).
- **Écran ou fonctionnalité inatteignable** : widget construit mais jamais poussé, route morte,
  entrée de menu conditionnée par un booléen toujours faux.
- **`MethodChannel` Dart sans pendant Kotlin**, ou méthode native jamais appelée depuis Dart.
- **État non rafraîchi après une mutation** : suppression / renommage / déplacement qui ne
  déclenche pas de rafraîchissement, ou en déclenche un sur le mauvais écran.
- **Résultat de `Navigator.push` ignoré** alors que l'écran enfant renvoie une valeur significative.

## 3. Interactions et cycle de vie

- **Réentrance** : double-tap sur un bouton qui lance une opération longue, garde posée **après**
  le premier `await` (donc inopérante).
- **`setState` après `dispose`** : `await` puis `setState` sans `mounted`.
- **Timer / `StreamSubscription` / `ReceivePort` non annulé au `dispose`**.
- **Bouton actif pendant l'opération qu'il a lancée** (pas de `_isProcessing` ou non branché sur
  `onPressed: null`).
- **`PopScope` / retour arrière** : travail non sauvegardé perdu sans avertissement, ou au
  contraire dialogue de confirmation qui bloque une sortie légitime.

## Règles de sortie — non négociables

- **Chaque constat cite `fichier:ligne` et décrit un scénario concret** : ce que l'utilisateur
  fait, et ce qu'il obtient de travers. Un constat sans scénario n'en est pas un.
- **Classe chaque constat CONFIRMÉ (prouvable sur le code fourni) ou HYPOTHÈSE.** Sur un audit
  comparable de ce portefeuille, **6 constats externes sur ~20 étaient réfutables contre le
  code** — dont un signalé en ÉLEVÉ par deux relecteurs simultanément, et faux. Ne gonfle pas la
  liste : une liste courte et juste vaut mieux qu'une longue à trier.
- **Ne signale aucune préférence de style**, ni renommage, ni architecture « idéale », ni
  suggestion de migrer vers un gestionnaire d'état.
- **Ne propose jamais d'ajouter une permission Android.**
- Si une zone est saine, **dis-le explicitement** : « rien à signaler sur X » est une information
  que j'utilise.
- Sévérité : CRITIQUE / ÉLEVÉ / MOYEN / FAIBLE. Justifie CRITIQUE et ÉLEVÉ par l'impact réel sur
  l'utilisateur (perte de données, action destructive non voulue, fonctionnalité inatteignable).

## Contexte utile, à ne pas re-découvrir

- `showErrorSnack` (`lib/utils/snack_utils.dart`) est le helper canonique ; ~19 `SnackBar` inline
  ne l'utilisent pas. **C'est connu et assumé pour l'instant** — ne le re-signale pas comme constat.
- `dangerFilledButtonStyle()` / `kDangerRed` (`lib/widgets/danger_style.dart`) est le style
  destructif canonique. Quelques déviations subsistent (`duplicates_screen`, `global_search_screen`) :
  **connues, ne pas re-signaler**.
- Le verrouillage du coffre a été refait : `dispose()` verrouille, un minuteur d'inactivité de
  3 min tourne dans `main.dart`, et `VaultScreen` écoute `VaultService.unlockedNotifier`.

Les sources suivent.
