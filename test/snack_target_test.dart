import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/snack_utils.dart';

/// Ces tests portent sur une seule question : **le bandeau s'affiche-t-il
/// encore quand l'écran qui l'a demandé n'est plus là ?**
///
/// C'est le cas qui compte. Une conversion, une copie, un import durent
/// plusieurs secondes, et l'utilisateur ne reste pas à regarder. Le motif
/// `if (!mounted) return;` posé *avant* l'affichage faisait taire l'échec
/// exactement dans cette situation — plus l'opération était longue, plus le
/// silence était probable.
void main() {
  /// Monte un écran qui capture un [SnackTarget], puis rend la fonction
  /// permettant de le récupérer après que l'écran a disparu.
  Future<SnackTarget> pumpAndCapture(WidgetTester tester) async {
    late SnackTarget captured;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) {
              captured = SnackTarget.of(context);
              return const SizedBox.shrink();
            },
          ),
        ),
      ),
    );
    return captured;
  }

  testWidgets('un bandeau d\'erreur s\'affiche', (tester) async {
    final snack = await pumpAndCapture(tester);
    snack.error('échec de la copie');
    await tester.pump();
    expect(find.text('échec de la copie'), findsOneWidget);
  });

  testWidgets('le bandeau survit à la disparition du widget qui l\'a demandé', (
    tester,
  ) async {
    // Un écran pousse une page, y capture la cible, puis la page est
    // retirée — comme lorsque l'utilisateur revient en arrière pendant une
    // opération longue.
    late SnackTarget captured;
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigator,
        home: const Scaffold(body: Text('accueil')),
      ),
    );

    navigator.currentState!.push(
      MaterialPageRoute<void>(
        builder: (context) {
          captured = SnackTarget.of(context);
          return const Scaffold(body: Text('travail en cours'));
        },
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('travail en cours'), findsOneWidget);

    navigator.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('travail en cours'), findsNothing);

    // L'écran est parti ; le message doit tout de même parvenir.
    captured.error('la copie a échoué');
    await tester.pump();
    expect(find.text('la copie a échoué'), findsOneWidget);
  });

  testWidgets('showErrorSnack renonce sur un contexte démonté', (tester) async {
    // Le pendant du test précédent : la fonction à `BuildContext`, elle,
    // continue de se taire. Ce n'est pas un défaut mais un contrat, et les
    // deux comportements doivent rester distincts — sans quoi il n'y aurait
    // aucune raison d'avoir gardé les deux.
    late BuildContext captured;
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigator,
        home: const Scaffold(body: Text('accueil')),
      ),
    );
    navigator.currentState!.push(
      MaterialPageRoute<void>(
        builder: (context) {
          captured = context;
          return const Scaffold(body: Text('travail'));
        },
      ),
    );
    await tester.pumpAndSettle();
    navigator.currentState!.pop();
    await tester.pumpAndSettle();

    showErrorSnack(captured, 'jamais affiché');
    await tester.pump();
    expect(find.text('jamais affiché'), findsNothing);
  });

  testWidgets('l\'erreur porte les couleurs d\'erreur du thème', (
    tester,
  ) async {
    final snack = await pumpAndCapture(tester);
    snack.error('rouge');
    await tester.pump();

    final bar = tester.widget<SnackBar>(find.byType(SnackBar));
    final context = tester.element(find.byType(Scaffold));
    final cs = Theme.of(context).colorScheme;
    expect(bar.backgroundColor, cs.errorContainer);
    expect(bar.behavior, SnackBarBehavior.floating);

    final text = tester.widget<Text>(find.text('rouge'));
    expect(text.style?.color, cs.onErrorContainer);
  });

  testWidgets('un bandeau à action n\'est pas permanent', (tester) async {
    // `SnackBar.persist` vaut par défaut `action != null` : sans le
    // `persist: false` posé dans le helper, ce bandeau resterait affiché
    // indéfiniment et sa durée serait ignorée. C'est le défaut corrigé en
    // v2.14.0 ; ce test empêche qu'il revienne par une nouvelle voie.
    final snack = await pumpAndCapture(tester);
    snack.info(
      'terminé',
      duration: const Duration(milliseconds: 500),
      action: SnackBarAction(label: 'Partager', onPressed: () {}),
    );
    // L'animation d'entrée doit s'achever avant que le minuteur de retrait ne
    // parte : le mesurer sur une entrée inachevée donnerait un test vert pour
    // la mauvaise raison.
    await tester.pumpAndSettle();
    expect(find.text('terminé'), findsOneWidget);

    final bar = tester.widget<SnackBar>(find.byType(SnackBar));
    expect(bar.persist, isFalse);
    expect(bar.duration, const Duration(milliseconds: 500));

    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(find.text('terminé'), findsNothing);
  });

  testWidgets('info et error se distinguent', (tester) async {
    // Un import partiellement échoué s'affichait avec le même fond qu'un
    // import réussi. Les deux variantes doivent rester visuellement séparées.
    final snack = await pumpAndCapture(tester);
    snack.info('neutre');
    await tester.pump();
    final infoBar = tester.widget<SnackBar>(find.byType(SnackBar));
    final infoBg = infoBar.backgroundColor;

    snack.clear();
    await tester.pumpAndSettle();

    snack.error('grave');
    await tester.pump();
    final errorBar = tester.widget<SnackBar>(find.byType(SnackBar));

    expect(errorBar.backgroundColor, isNot(infoBg));
  });

  testWidgets('un messager imbrique et demonte ne fait pas planter', (
    tester,
  ) async {
    // Trouve par la relecture GPT du 2026-08-09, et le point etait juste :
    // les autres tests capturent le `ScaffoldMessenger` du `MaterialApp`, qui
    // survit a tout. Ils prouvent donc « pas besoin d'un BuildContext monte »,
    // pas « resiste a un messager mort ». Un `ScaffoldMessenger` imbriquee,
    // lui, disparait avec son sous-arbre.
    //
    // L'application n'en contient aucun a ce jour. Ce test existe pour que le
    // jour ou l'un apparaitra ne se solde pas par un plantage loin de sa cause.
    late SnackTarget captured;
    await tester.pumpWidget(
      MaterialApp(
        home: ScaffoldMessenger(
          child: Scaffold(
            body: Builder(
              builder: (context) {
                captured = SnackTarget.of(context);
                return const Text('sous-arbre');
              },
            ),
          ),
        ),
      ),
    );
    expect(find.text('sous-arbre'), findsOneWidget);

    // Le sous-arbre entier disparait : le messager capture est libere.
    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Text('autre chose'))),
    );
    await tester.pumpAndSettle();
    expect(find.text('sous-arbre'), findsNothing);

    // Ne doit rien afficher, et surtout rien lever.
    captured.error('message perdu');
    captured.info('autre message perdu');
    captured.clear();
    await tester.pump();
    expect(find.text('message perdu'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('stillWanted permet a un ecran de renoncer a son bandeau', (
    tester,
  ) async {
    // Le coffre a besoin du comportement INVERSE des autres ecrans. Son
    // `dispose()` verrouille (V-H1) : quitter l'ecran, c'est en avoir fini. Un
    // bandeau « Exporte : releve_bancaire.pdf » qui arrive apres coup
    // afficherait un nom de fichier du coffre sur un ecran quelconque, alors
    // que le coffre est deja referme.
    var present = true;
    late SnackTarget captured;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) {
              captured = SnackTarget.of(context, stillWanted: () => present);
              return const SizedBox.shrink();
            },
          ),
        ),
      ),
    );

    captured.info('tant que l ecran est la');
    await tester.pump();
    expect(find.text('tant que l ecran est la'), findsOneWidget);
    captured.clear();
    await tester.pumpAndSettle();

    // L'ecran s'en va.
    present = false;
    captured.info('nom du fichier du coffre');
    captured.error('echec de restauration');
    await tester.pump();
    expect(find.text('nom du fichier du coffre'), findsNothing);
    expect(find.text('echec de restauration'), findsNothing);
  });

  testWidgets('sans stillWanted, le bandeau part quand meme', (tester) async {
    // Le temoin : sans l'option, le comportement par defaut est inchange.
    // Sans lui, le test precedent pourrait passer au vert parce que plus
    // rien ne s'affiche du tout.
    final snack = await pumpAndCapture(tester);
    snack.info('toujours affiche');
    await tester.pump();
    expect(find.text('toujours affiche'), findsOneWidget);
  });
}
