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

  /// `-1` si indéterminable (hors Android, ou canal indisponible).
  static Future<int> sdkInt() async {
    if (_sdk != null) return _sdk!;
    if (!Platform.isAndroid) return _sdk = -1;
    try {
      _sdk = await _channel.invokeMethod<int>('sdkInt') ?? -1;
    } catch (_) {
      // Canal indisponible : on suppose Android 11+, le cas le plus courant
      // aujourd'hui, plutôt que de dégrader le comportement moderne.
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
  static Future<bool> isGranted() async {
    if (!Platform.isAndroid) return true;
    if (await usesAllFilesAccess()) {
      return Permission.manageExternalStorage.isGranted;
    }
    return Permission.storage.isGranted;
  }

  /// Demande l'accès, avec la permission qui correspond à la version.
  ///
  /// Sur Android ≤ 10 c'est une vraie boîte de dialogue système, à laquelle
  /// l'utilisateur peut répondre — contrairement à la demande de
  /// `MANAGE_EXTERNAL_STORAGE`, qui n'affichait rien du tout.
  static Future<bool> request() async {
    if (!Platform.isAndroid) return true;
    if (await usesAllFilesAccess()) {
      final s = await Permission.manageExternalStorage.request();
      return s.isGranted;
    }
    final s = await Permission.storage.request();
    return s.isGranted;
  }

  /// Ouvre l'écran de réglages pertinent : la page « Accès à tous les
  /// fichiers » sur Android 11+, la fiche de l'application sinon — où se
  /// trouve bien, cette fois, la permission « Stockage » à activer.
  static Future<void> openSettings() async {
    if (await usesAllFilesAccess()) {
      await _channel.invokeMethod('openAllFilesAccess');
      return;
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
