# Relecture adversariale — correctifs SnackBar / cycle de vie (Read Files Tech v2.15)

Tu relis des correctifs qui viennent d'être écrits. Ton travail n'est pas de les
valider. **Une relecture qui valide n'apporte rien.** Cherche ce qui casse.

## Contexte

Application Flutter/Dart (Android). Explorateur de fichiers, visionneuses,
convertisseurs, coffre chiffré. Le correctif porte sur l'affichage des messages
d'erreur après une opération asynchrone longue.

## Le défaut corrigé

Deux écritures cohabitaient :

1. `showErrorSnack(context, e)` — teste `context.mounted` et **renonce** si
   l'écran est parti.
2. `final messenger = ScaffoldMessenger.of(context);` capturé avant l'`await`,
   puis `messenger.showSnackBar(...)` — MAIS tous les sites faisaient précéder
   l'appel d'un `if (!mounted) return;`, ce qui annulait le bénéfice de la
   capture.

Résultat : plus une opération durait, plus l'utilisateur avait de chances
d'avoir quitté l'écran, donc plus son échec avait de chances d'être tu.

## Le correctif

Une classe `SnackTarget` capture `ScaffoldMessengerState` + `ColorScheme` avant
l'`await`. `showErrorSnack` / `showFloatingSnack` délèguent à la même mise en
forme et conservent leur contrat (silence si contexte mort).

Les `if (!mounted) return;` placés avant un affichage ont été retirés ; ceux qui
protègent un `setState` ont été conservés — et plusieurs `setState` qui étaient
appelés AVANT leur garde ont été replacés après.

## Ce que je te demande de chercher, dans cet ordre

1. **Fuite de `BuildContext` / de State.** `SnackTarget` retient un
   `ScaffoldMessengerState`. Est-ce que le conserver au-delà de la vie du widget
   peut retenir un arbre entier en mémoire, ou lever si le Scaffold lui-même a
   été démonté (et pas seulement la route poussée par-dessus) ? Que se passe-t-il
   si l'app change de `MaterialApp`, ou si l'écran capturé était lui-même le
   dernier Scaffold ?
2. **Un `if (!mounted) return;` retiré à tort.** Certains de ces gardes ne
   protégeaient pas que le bandeau : cherche les cas où la ligne retirée
   protégeait aussi un `Navigator.pop`, un `progressDialog.close()`, un
   `_refresh()`, ou un accès à `widget.` / `context` juste après.
3. **`setState` après `dispose()`.** J'ai remplacé
   `setState(...); if (!mounted) return;` par `if (mounted) setState(...);`.
   Ai-je manqué un site ? Ai-je créé un état incohérent en mettant à jour un
   champ hors `setState` dans la branche `else` ?
4. **Un message maintenant affiché deux fois**, ou affiché alors qu'il ne
   devrait pas l'être (opération annulée par l'utilisateur, écran remplacé).
5. **Les tests.** `test/snack_target_test.dart`. Est-ce qu'un de ces tests
   passerait au vert pour une raison autre que celle annoncée ? Est-ce qu'il
   reste vert si on remet le défaut ? Dis-le franchement si un test ne prouve
   pas ce que son nom prétend.

## Règles

- Cite `fichier:ligne`. Un constat sans localisation ne sera pas traité.
- Distingue **confirmé** (tu as vu le code) / **probable** / **à vérifier**.
- Ne signale aucune préférence de style, aucun renommage, aucune suggestion
  d'architecture générale.
- Si tu ne trouves rien de réel sur un axe, écris-le : « rien trouvé sur X ».
  C'est une information utile. N'invente pas pour remplir.
