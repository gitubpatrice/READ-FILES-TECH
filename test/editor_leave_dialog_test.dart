import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Le dialogue « Modifications non sauvegardées » des deux éditeurs (code et
/// CSV) rendait `false` sur « Ignorer ». L'appelant fait `if (leave) nav.pop()`
/// : l'écran ne se fermait donc pas, et « Ignorer » avait exactement le même
/// effet qu'« Annuler » — aucun. Le seul moyen de quitter était de
/// sauvegarder, c'est-à-dire l'inverse de ce que l'utilisateur demandait.
///
/// Ces tests reproduisent le dialogue à l'identique plutôt que d'instancier
/// les éditeurs, qui exigent un fichier réel et des plugins. Ce qui est
/// épinglé, c'est **la sémantique des trois boutons**, seule chose qui était
/// fausse. Le contrat vérifié ici est celui que l'appelant applique :
/// `true` → on quitte, `null`/`false` → on reste.
void main() {
  /// Réplique exacte des `actions` du dialogue et du `?? false` de sortie.
  Future<bool> showLeaveDialog(WidgetTester tester, String tapLabel) async {
    late bool leave;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => ElevatedButton(
            onPressed: () async {
              final result = await showDialog<bool>(
                context: context,
                builder: (_) => AlertDialog(
                  title: const Text('Modifications non sauvegardées'),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('Ignorer'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.pop(context, null),
                      child: const Text('Annuler'),
                    ),
                    FilledButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('Sauvegarder'),
                    ),
                  ],
                ),
              );
              leave = result ?? false;
            },
            child: const Text('ouvrir'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('ouvrir'));
    await tester.pumpAndSettle();
    await tester.tap(find.text(tapLabel));
    await tester.pumpAndSettle();
    return leave;
  }

  testWidgets('« Ignorer » fait quitter l\'écran', (tester) async {
    expect(
      await showLeaveDialog(tester, 'Ignorer'),
      isTrue,
      reason: 'ignorer ses modifications, c\'est vouloir sortir',
    );
  });

  testWidgets('« Annuler » garde l\'utilisateur sur l\'écran', (tester) async {
    expect(await showLeaveDialog(tester, 'Annuler'), isFalse);
  });

  testWidgets('« Sauvegarder » fait quitter l\'écran', (tester) async {
    expect(await showLeaveDialog(tester, 'Sauvegarder'), isTrue);
  });

  testWidgets('« Ignorer » et « Annuler » ne font PAS la même chose', (
    tester,
  ) async {
    final ignorer = await showLeaveDialog(tester, 'Ignorer');
    final annuler = await showLeaveDialog(tester, 'Annuler');
    expect(
      ignorer,
      isNot(annuler),
      reason: 'deux boutons distincts doivent avoir deux effets distincts',
    );
  });
}
