import 'dart:io';

import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

/// Accès au stockage partagé, selon la version d'Android.
///
/// **Pourquoi ce fichier existe.** L'application testait partout
/// `Permission.manageExternalStorage.isGranted` et demandait cette seule
/// permission. Or `MANAGE_EXTERNAL_STORAGE` n'existe **qu'à partir d'Android
/// 11** (API 30). En dessous — c'est-à-dire sur toute la plage Android 7→10
/// que `minSdk 24` annonce pourtant supporter — la permission n'existe pas :
///
/// - `isGranted` répondait `false` pour toujours, donc le bandeau « Accès aux
///   fichiers limité — autorisez tous les fichiers » restait affiché en
///   permanence ;
/// - son bouton « Réglages » ouvrait la fiche d'information de l'application,
///   où **aucune option de ce nom n'existe** : impasse complète ;
/// - et l'explorateur, soumis au scoped storage, ne pouvait pas lister les
///   dossiers de l'utilisateur.
///
/// Constaté sur Galaxy S9 (Android 10, API 29) le 2026-08-09, en exécutant
/// l'application. Ni l'audit du 2026-08-02 ni deux relectures externes ne
/// l'avaient vu : aucune lecture de code ne le donne, il fallait l'appareil.
///
/// La contrepartie côté manifeste est `requestLegacyExternalStorage="true"`,
/// qui rend l'accès effectif sur Android 10.
abstract final class StorageAccess {
  StorageAccess._();

  static const _channel = MethodChannel('com.readfilestech/lifecycle');

  /// Niveau d'API du système, mis en cache après le premier appel.
  static int? _sdk;

  /// `false` quand [_sdk] est une **supposition** et non une mesure.
  ///
  /// **Pourquoi cette distinction existe.** Le canal indisponible, le code
  /// posait `_sdk = 30` — alors que la documentation juste au-dessus annonçait
  /// `-1`. Au-delà de la contradiction, supposer est ici un piège symétrique :
  ///
  /// - supposer 30 sur un appareil Android ≤ 10 interroge une permission qui
  ///   **n'existe pas** sur cet appareil, donc toujours refusée, et envoie
  ///   l'utilisateur dans un écran de réglages où il ne trouvera rien. C'est
  ///   exactement l'impasse constatée sur le Galaxy S9 le 2026-08-09 ;
  /// - supposer ≤ 10 sur un Android 13+ demande `READ_EXTERNAL_STORAGE`, qui
  ///   n'y a plus d'effet : l'accès resterait refusé sans explication.
  ///
  /// Aucune valeur par défaut n'est donc bonne. Quand la version est inconnue,
  /// on cesse de deviner et on interroge **les deux** modèles de permission :
  /// celui qui existe répondra.
  static bool _sdkKnown = false;

  /// Niveau d'API mesuré, ou `-1` hors Android.
  ///
  /// Rend une **supposition** (30) si le canal est indisponible ; voir
  /// [_sdkKnown], et [isGranted] pour ce que cette incertitude change.
  static Future<int> sdkInt() async {
    if (_sdk != null) return _sdk!;
    if (!Platform.isAndroid) {
      _sdkKnown = true;
      return _sdk = -1;
    }
    try {
      final v = await _channel.invokeMethod<int>('sdkInt');
      if (v != null && v > 0) {
        _sdkKnown = true;
        return _sdk = v;
      }
      _sdk = 30;
    } catch (_) {
      _sdk = 30;
    }
    return _sdk!;
  }

  /// `true` si Android expose « Accès à tous les fichiers » (Android 11+).
  static Future<bool> usesAllFilesAccess() async => (await sdkInt()) >= 30;

  /// `true` si l'application peut réellement parcourir le stockage partagé.
  ///
  /// Android 11+ : `MANAGE_EXTERNAL_STORAGE`.
  /// Android ≤ 10 : `READ_EXTERNAL_STORAGE`, qui suffit grâce au legacy
  /// storage déclaré dans le manifeste.
  ///
  /// Version inconnue : les deux sont interrogées, et l'une suffit. Sur un
  /// appareil donné, une seule des deux peut être accordée — il n'y a donc
  /// aucun risque de conclure à tort à un accès.
  static Future<bool> isGranted() async {
    if (!Platform.isAndroid) return true;
    final moderne = await usesAllFilesAccess();
    if (!_sdkKnown) {
      return await Permission.manageExternalStorage.isGranted ||
          await Permission.storage.isGranted;
    }
    if (moderne) return Permission.manageExternalStorage.isGranted;
    return Permission.storage.isGranted;
  }

  /// Demande l'accès, avec la permission qui correspond à la version.
  ///
  /// Sur Android ≤ 10 c'est une vraie boîte de dialogue système, à laquelle
  /// l'utilisateur peut répondre — contrairement à la demande de
  /// `MANAGE_EXTERNAL_STORAGE`, qui n'affichait rien du tout.
  ///
  /// Version inconnue : la demande moderne est tentée d'abord, puis la
  /// legacy. Celle qui n'a pas cours sur l'appareil échoue sans rien afficher,
  /// donc l'utilisateur ne voit qu'une seule boîte — la bonne.
  static Future<bool> request() async {
    if (!Platform.isAndroid) return true;
    if (await usesAllFilesAccess()) {
      final s = await Permission.manageExternalStorage.request();
      if (s.isGranted) return true;
      if (_sdkKnown) return false;
    }
    final s = await Permission.storage.request();
    return s.isGranted;
  }

  /// Ouvre l'écran de réglages pertinent : la page « Accès à tous les
  /// fichiers » sur Android 11+, la fiche de l'application sinon — où se
  /// trouve bien, cette fois, la permission « Stockage » à activer.
  ///
  /// Si l'ouverture de l'écran moderne échoue — canal indisponible, ou intent
  /// absente sur cet appareil — on retombe sur la fiche de l'application
  /// plutôt que de laisser l'exception remonter. Un bouton « Réglages » qui ne
  /// fait rien du tout est pire qu'un bouton qui ouvre l'écran voisin.
  static Future<void> openSettings() async {
    if (await usesAllFilesAccess()) {
      try {
        await _channel.invokeMethod('openAllFilesAccess');
        return;
      } catch (_) {
        // Repli ci-dessous.
      }
    }
    await openAppSettings();
  }

  /// Texte du bandeau, adapté à ce que l'utilisateur va réellement trouver
  /// dans ses réglages. Annoncer « tous les fichiers » à quelqu'un qui n'a pas
  /// cette option, c'est l'envoyer chercher ce qui n'existe pas.
  static Future<String> bannerMessage() async {
    return (await usesAllFilesAccess())
        ? 'Accès aux fichiers limité — autorisez tous les fichiers.'
        : 'Accès aux fichiers limité — autorisez le stockage.';
  }
}
