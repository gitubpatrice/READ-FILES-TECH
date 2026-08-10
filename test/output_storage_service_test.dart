import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/output_storage_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// La reservation d'un chemin de sortie n'avait aucun test.
///
/// Deux defauts trouves par la relecture GPT du 2026-08-10 :
///
///  - `_timestamp()` s'arrete a la SECONDE — contrairement a ce qu'affirmait
///    un commentaire — et `create()` etait appele sans exclusivite. Deux
///    reservations dans la meme seconde repartaient donc avec le meme chemin,
///    et la seconde ecriture ecrasait la premiere ;
///  - l'extension alimentait un chemin sans etre assainie.
void main() {
  // `setBasePath` passe par SharedPreferences, donc par un canal de plateforme.
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  late Directory tmp;
  late OutputStorageService svc;

  setUp(() async {
    // `setBasePath` n'accepte que des emplacements plausibles sur Android. Le
    // test emprunte la forme « dossier app » (`/files/`) qu'il autorise deja,
    // plutot que d'affaiblir cette validation pour les besoins du test.
    tmp = Directory.systemTemp.createTempSync('rft_out_');
    final base =
        '${tmp.path.split(Platform.pathSeparator).join('/')}/files/sortie';
    Directory(base).createSync(recursive: true);
    svc = OutputStorageService();
    await svc.setBasePath(base);
  });

  tearDown(() {
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {}
  });

  test(
    'vingt reservations simultanees donnent vingt chemins DISTINCTS',
    () async {
      // Toutes tombent dans la meme seconde : c'est exactement le cas que le
      // nom de fichier ne distinguait pas.
      final fichiers = await Future.wait(
        List.generate(
          20,
          (_) => svc.reserveFile(
            category: OutputCategory.conversions,
            suggestedName: 'doc',
            extension: 'pdf',
          ),
        ),
      );

      final chemins = fichiers.map((f) => f.path).toSet();
      expect(
        chemins.length,
        20,
        reason:
            'des reservations partagent le meme chemin : elles s ecraseront',
      );
      for (final f in fichiers) {
        expect(f.existsSync(), isTrue, reason: '${f.path} n a pas ete reserve');
      }
    },
  );

  test(
    'une extension traversante n echappe pas au dossier de sortie',
    () async {
      final f = await svc.reserveFile(
        category: OutputCategory.scans,
        suggestedName: 'x',
        extension: '../../evade',
      );
      expect(
        f.path.contains('..'),
        isFalse,
        reason: 'le chemin sort du dossier : ${f.path}',
      );
      expect(f.path, contains('Scans'));
    },
  );

  test('la sauvegarde du coffre a bien son dossier dedie', () async {
    final f = await svc.reserveFile(
      category: OutputCategory.backups,
      suggestedName: 'coffre',
      extension: 'rftvault',
    );
    expect(f.path, contains('Sauvegardes coffre'));
    expect(f.path, endsWith('.rftvault'));
  });
}
