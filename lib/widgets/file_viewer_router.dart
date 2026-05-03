import 'dart:io';
import 'package:flutter/material.dart';
import '../screens/viewers/txt_viewer_screen.dart';
import '../screens/viewers/md_viewer_screen.dart';
import '../screens/viewers/json_viewer_screen.dart';
import '../screens/viewers/html_viewer_screen.dart';
import '../screens/viewers/csv_viewer_screen.dart';
import '../screens/viewers/xlsx_viewer_screen.dart';
import '../screens/viewers/docx_viewer_screen.dart';
import '../screens/viewers/pdf_viewer_screen.dart';
import '../screens/viewers/zip_viewer_screen.dart';
import '../screens/viewers/image_viewer_screen.dart';
import '../screens/viewers/reader_viewer_screen.dart';

/// Source unique de routing « extension de fichier → écran de visualisation
/// interne ». Utilisé partout dans l'app (file_explorer, output cards des
/// outils, recents) pour garantir une UX cohérente.
class FileViewerRouter {
  FileViewerRouter._();

  static const _editableExts = {
    'txt',
    'md',
    'csv',
    'xml',
    'json',
    'html',
    'htm',
    'css',
    'js',
    'php',
    'dart',
    'yaml',
    'yml',
    'log',
    'ini',
    'conf',
  };
  static const _viewableExts = {
    'docx',
    'doc',
    'odt',
    'odp',
    'xlsx',
    'xls',
    'ods',
    'pdf',
    'zip',
    'epub',
  };
  static const _imageExts = {'jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'};

  /// Retourne true si Read Files Tech sait visualiser ce fichier en interne.
  static bool canViewInternally(String path) {
    final ext = _ext(path);
    return _editableExts.contains(ext) ||
        _viewableExts.contains(ext) ||
        _imageExts.contains(ext);
  }

  /// Retourne l'écran viewer adapté à ce path, ou null si non géré
  /// (l'appelant peut alors fallback sur un chooser système).
  ///
  /// [imageSiblings] : pour les images, liste des paths frères du même
  /// dossier pour activer le swipe entre images (utilisé par l'explorateur).
  /// Laisser vide pour les outputs des outils (un seul fichier produit).
  static Widget? screenFor(
    String path, {
    List<String> imageSiblings = const [],
  }) {
    final ext = _ext(path);
    if (_imageExts.contains(ext)) {
      return ImageViewerScreen(path: path, siblings: imageSiblings);
    }
    if (_editableExts.contains(ext)) {
      switch (ext) {
        case 'md':
          return MdViewerScreen(path: path);
        case 'json':
          return JsonViewerScreen(path: path);
        case 'html':
        case 'htm':
          return HtmlViewerScreen(path: path);
        case 'csv':
          return CsvViewerScreen(path: path);
        default:
          return TxtViewerScreen(
            path: path,
            highlightLanguage: ['css', 'js', 'php', 'xml', 'dart'].contains(ext)
                ? ext
                : null,
          );
      }
    }
    switch (ext) {
      case 'docx':
      case 'doc':
      case 'odt':
      case 'odp':
        return DocxViewerScreen(path: path);
      case 'xlsx':
      case 'xls':
      case 'ods':
        return XlsxViewerScreen(path: path);
      case 'pdf':
        return PdfViewerScreen(path: path);
      case 'zip':
        return ZipViewerScreen(path: path);
      case 'epub':
        return ReaderViewerScreen(path: path, isEpub: true);
    }
    return null;
  }

  /// Ouvre le fichier dans le viewer interne adapté. Si aucun viewer ne
  /// correspond, ou si le fichier n'existe pas, affiche un SnackBar d'erreur.
  /// Retourne true si la navigation a eu lieu.
  ///
  /// [imageSiblings] : voir [screenFor].
  static Future<bool> open(
    BuildContext context,
    String path, {
    List<String> imageSiblings = const [],
  }) async {
    final file = File(path);
    if (!await file.exists()) {
      if (!context.mounted) return false;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Fichier introuvable')));
      return false;
    }
    final screen = screenFor(path, imageSiblings: imageSiblings);
    if (screen == null) {
      if (!context.mounted) return false;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Format non supporté en interne — utilisez « Ouvrir avec… »',
          ),
        ),
      );
      return false;
    }
    if (!context.mounted) return false;
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
    return true;
  }

  /// Extension fichier en minuscules. Gère correctement :
  /// - `foo.txt`        → `txt`
  /// - `archive.tar.gz` → `gz` (dernière extension)
  /// - `.bashrc`        → `''`  (dotfile, pas d'extension)
  /// - `Makefile`       → `''`
  static String _ext(String path) {
    final basename = path.split(_kSepRe).last;
    // Dotfile (commence par '.') sans autre point → pas d'extension.
    if (basename.startsWith('.') && basename.lastIndexOf('.') == 0) {
      return '';
    }
    final dot = basename.lastIndexOf('.');
    if (dot < 0) return '';
    return basename.substring(dot + 1).toLowerCase();
  }

  static final _kSepRe = RegExp(r'[/\\]');
}
