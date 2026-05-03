import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Catégories de sortie : chacune a son propre sous-dossier dans
/// `<Files Tech>/`. Permet de retrouver "tous mes scans" en un coup d'œil.
enum OutputCategory {
  scans,
  conversions,
  compressions,
  signatures,
  exifClean,
  ocr,
}

extension OutputCategoryX on OutputCategory {
  String get folderName {
    switch (this) {
      case OutputCategory.scans:
        return 'Scans';
      case OutputCategory.conversions:
        return 'Conversions';
      case OutputCategory.compressions:
        return 'Compressions';
      case OutputCategory.signatures:
        return 'Signatures';
      case OutputCategory.exifClean:
        return 'Sans-EXIF';
      case OutputCategory.ocr:
        return 'OCR';
    }
  }
}

/// Service central de sauvegarde des fichiers générés par l'app.
///
/// Stratégie :
/// - Dossier de base par défaut : `/storage/emulated/0/Files Tech/`
///   (visible dans tout explorateur, hors-cache, **persistant**)
/// - L'utilisateur peut le redéfinir via les Réglages (clé prefs `output_base_path`)
/// - Sous-dossier par catégorie (Scans, Conversions, …)
/// - Si l'écriture échoue (perm refusée, FS read-only), fallback vers le
///   dossier app-privé externe (`/Android/data/<pkg>/files/Files Tech/`),
///   toujours visible mais sans avoir besoin de MANAGE_EXTERNAL_STORAGE.
/// - Filenames : `<base>_<yyyyMMdd_HHmmss>.<ext>` ; collision → suffixe `_N`.
class OutputStorageService {
  static const _kBasePath = 'output_base_path';
  static const _kAutoShare = 'output_auto_share';
  static const _defaultBase = '/storage/emulated/0/Files Tech';

  /// Retourne le dossier de base configuré (sans création).
  Future<String> getBasePath() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_kBasePath) ?? _defaultBase;
  }

  /// N'accepte que des chemins dans le stockage partagé (visible utilisateur)
  /// ou dans les dossiers app (privés). Refuse explicitement les chemins
  /// pointant vers une autre app (`/data/data/<other-pkg>/`) : Android l'aurait
  /// bloqué de toute façon, mais on évite de stocker une préférence cassée.
  Future<void> setBasePath(String path) async {
    final allowed =
        path.startsWith('/storage/emulated/0') ||
        path.startsWith('/storage/') ||
        path.startsWith('/sdcard/') ||
        path == '/sdcard' ||
        path == _defaultBase;
    // On laisse aussi passer les paths app (récupérés via path_provider) :
    // ils contiennent typiquement `/files/` ou `/cache/` à la fin.
    final isAppDir =
        path.contains('/Android/data/') ||
        path.contains('/files/') ||
        path.contains('/cache/');
    if (!allowed && !isAppDir) {
      throw ArgumentError(
        'Chemin non autorisé. Choisissez un dossier sur le stockage interne.',
      );
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kBasePath, path);
  }

  /// Pré-crée tous les sous-dossiers de sortie. À appeler au boot de l'app
  /// pour que le dossier soit visible immédiatement dans l'explorateur, sans
  /// attendre la première sauvegarde.
  /// Retourne le chemin effectivement créé (peut différer du configuré si
  /// fallback). Retourne null si toutes les tentatives ont échoué.
  Future<String?> ensureFolders() async {
    final basePath = await getBasePath();
    // Tente le dossier configuré
    final base = Directory(basePath);
    try {
      if (!await base.exists()) await base.create(recursive: true);
      // Crée tous les sous-dossiers
      for (final c in OutputCategory.values) {
        final sub = Directory('${base.path}/${c.folderName}');
        if (!await sub.exists()) await sub.create(recursive: true);
      }
      return base.path;
    } catch (_) {}
    // Fallback : app-private external
    try {
      final ext = await getExternalStorageDirectory();
      if (ext != null) {
        final fbBase = Directory('${ext.path}/Files Tech');
        if (!await fbBase.exists()) await fbBase.create(recursive: true);
        for (final c in OutputCategory.values) {
          final sub = Directory('${fbBase.path}/${c.folderName}');
          if (!await sub.exists()) await sub.create(recursive: true);
        }
        return fbBase.path;
      }
    } catch (_) {}
    return null;
  }

  Future<bool> getAutoShare() async {
    final prefs = await SharedPreferences.getInstance();
    // Désactivé par défaut : la carte de résultat affiche déjà le chemin
    // sauvegardé + bouton "Voir le fichier" + boutons cloud. Forcer un partage
    // immédiat masquerait ces infos derrière le sélecteur Android.
    return prefs.getBool(_kAutoShare) ?? false;
  }

  Future<void> setAutoShare(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_kAutoShare, value);
  }

  /// Crée un fichier persistant pour une catégorie donnée.
  /// [suggestedName] : nom de base souhaité (sans timestamp). Sera sanitizé,
  /// puis suffixé par un timestamp pour garantir l'unicité.
  /// Retourne le [File] créé (vide ; à l'appelant d'écrire les bytes).
  ///
  /// En cas d'échec de création dans le dossier configuré (perm, R/O), on
  /// retombe automatiquement sur le dossier app-privé externe **sans lever
  /// d'erreur** — le fichier reste persistant et visible via Android/data.
  Future<File> reserveFile({
    required OutputCategory category,
    required String suggestedName,
    required String extension,
  }) async {
    final safeBase = PathSafe.sanitizeForFs(suggestedName);
    final ts = _timestamp();
    final fileName = '${safeBase}_$ts.$extension';

    // 1. Tente le dossier configuré (par défaut /storage/emulated/0/Files Tech/<cat>/)
    final basePath = await getBasePath();
    final primaryDir = Directory('$basePath/${category.folderName}');
    final primary = await _tryCreateAndReserve(primaryDir, fileName);
    if (primary != null) return primary;

    // 2. Fallback : dossier app-privé externe (toujours accessible).
    final ext = await getExternalStorageDirectory();
    if (ext != null) {
      final fallbackDir = Directory(
        '${ext.path}/Files Tech/${category.folderName}',
      );
      final fallback = await _tryCreateAndReserve(fallbackDir, fileName);
      if (fallback != null) return fallback;
    }

    // 3. Dernier recours : documents app (toujours en lecture/écriture).
    final docs = await getApplicationDocumentsDirectory();
    final docDir = Directory('${docs.path}/Files Tech/${category.folderName}');
    final doc = await _tryCreateAndReserve(docDir, fileName);
    if (doc != null) return doc;

    throw FileSystemException('Aucun emplacement d\'écriture disponible');
  }

  /// Tente de créer le dossier puis un fichier unique dedans.
  /// Retourne null si non accessible (perm, R/O, FS plein…).
  Future<File?> _tryCreateAndReserve(Directory dir, String fileName) async {
    try {
      if (!await dir.exists()) await dir.create(recursive: true);
      var dest = File('${dir.path}/$fileName');
      // Collision improbable (le timestamp inclut les ms), mais on suffixe au
      // cas où, plutôt que d'écraser silencieusement.
      var counter = 1;
      while (await dest.exists()) {
        final dot = fileName.lastIndexOf('.');
        final base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        final ext = dot >= 0 ? fileName.substring(dot) : '';
        dest = File('${dir.path}/${base}_$counter$ext');
        counter++;
      }
      // Test d'écriture par création vide
      await dest.create();
      return dest;
    } catch (_) {
      return null;
    }
  }

  /// Vérifie si l'app a la perm pour écrire dans le dossier configuré.
  /// Utile pour afficher un avertissement dans Settings.
  Future<bool> canWriteToConfiguredBase() async {
    final basePath = await getBasePath();
    if (basePath.startsWith('/storage/emulated/0') ||
        basePath.startsWith('/sdcard')) {
      if (!Platform.isAndroid) return true;
      return await Permission.manageExternalStorage.isGranted;
    }
    // Hors stockage partagé : on tente une écriture test.
    try {
      final dir = Directory(basePath);
      if (!await dir.exists()) await dir.create(recursive: true);
      final probe = File(
        '${dir.path}/.probe_${DateTime.now().millisecondsSinceEpoch}',
      );
      await probe.writeAsString('');
      await probe.delete();
      return true;
    } catch (_) {
      return false;
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  /// yyyyMMdd_HHmmss
  String _timestamp() {
    final n = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    return '${n.year}${two(n.month)}${two(n.day)}_${two(n.hour)}${two(n.minute)}${two(n.second)}';
  }
}
