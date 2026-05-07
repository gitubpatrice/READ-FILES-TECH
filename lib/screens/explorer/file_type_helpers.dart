import 'dart:io';
import 'package:flutter/material.dart';
import 'package:files_tech_core/files_tech_core.dart';
import '../../widgets/file_viewer_router.dart';

/// Extension dédupliquant `path.split(...)` partout : usage `widget.path.basename`.
/// Variante non-throwing : retourne la dernière composante brute si invalide
/// (afin d'être utilisable pour l'affichage ; pour un usage sécurité-critique,
/// utiliser directement `PathSafe.basename`).
extension PathBasename on String {
  String get basename {
    try {
      return PathSafe.basename(this);
    } catch (_) {
      return PathUtils.fileName(this);
    }
  }
}

/// Re-exports vers la source unique [FileViewerRouter]. Conservés ici
/// (alias) pour compat des imports existants ; les nouveaux usages doivent
/// préférer `FileViewerRouter.canViewInternally(path)`.
const editableExts = FileViewerRouter.editableExts;
const viewableExts = FileViewerRouter.viewableExts;
const imageExts = FileViewerRouter.imageExts;

const previewExts = {
  'txt',
  'md',
  'json',
  'xml',
  'html',
  'htm',
  'css',
  'js',
  'php',
  'dart',
  'csv',
  'yaml',
  'yml',
  'ini',
  'conf',
  'log',
};

/// Alias rétrocompat — préférer [PathUtils.fileExt] dans les nouveaux usages.
String fileExt(String path) => PathUtils.fileExt(path);

String? mimeOf(String ext) {
  switch (ext) {
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'png':
      return 'image/png';
    case 'gif':
      return 'image/gif';
    case 'webp':
      return 'image/webp';
    case 'mp4':
      return 'video/mp4';
    case 'mp3':
      return 'audio/mpeg';
    case 'pdf':
      return 'application/pdf';
    case 'txt':
      return 'text/plain';
    case 'html':
    case 'htm':
      return 'text/html';
    case 'csv':
      return 'text/csv';
    case 'zip':
      return 'application/zip';
    case 'apk':
      return 'application/vnd.android.package-archive';
    case 'json':
      return 'application/json';
    case 'xml':
      return 'application/xml';
    case 'doc':
      return 'application/msword';
    case 'docx':
      return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    case 'xls':
      return 'application/vnd.ms-excel';
    case 'xlsx':
      return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    case 'ppt':
      return 'application/vnd.ms-powerpoint';
    case 'pptx':
      return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
    default:
      return null;
  }
}

IconData iconFor(FileSystemEntity e) {
  if (e is Directory) return Icons.folder_outlined;
  switch (fileExt(e.path)) {
    case 'pdf':
      return Icons.picture_as_pdf_outlined;
    case 'jpg':
    case 'jpeg':
    case 'png':
    case 'gif':
    case 'webp':
      return Icons.image_outlined;
    case 'mp4':
    case 'avi':
    case 'mov':
      return Icons.videocam_outlined;
    case 'mp3':
    case 'wav':
    case 'flac':
      return Icons.audiotrack_outlined;
    case 'zip':
    case 'rar':
    case '7z':
      return Icons.folder_zip_outlined;
    case 'docx':
    case 'doc':
    case 'odt':
      return Icons.article_outlined;
    case 'xlsx':
    case 'xls':
    case 'csv':
      return Icons.table_chart_outlined;
    case 'html':
    case 'htm':
      return Icons.html_outlined;
    case 'js':
    case 'ts':
      return Icons.javascript_outlined;
    case 'css':
      return Icons.css_outlined;
    case 'json':
      return Icons.data_object;
    case 'md':
      return Icons.text_snippet_outlined;
    default:
      return Icons.insert_drive_file_outlined;
  }
}

Color colorFor(FileSystemEntity e) {
  if (e is Directory) return Colors.amber;
  switch (fileExt(e.path)) {
    case 'pdf':
      return Colors.red;
    case 'jpg':
    case 'jpeg':
    case 'png':
    case 'gif':
      return Colors.purple;
    case 'js':
    case 'ts':
      return Colors.yellow.shade700;
    case 'html':
    case 'htm':
      return Colors.orange;
    case 'css':
      return Colors.blue;
    case 'json':
      return Colors.deepPurple;
    case 'docx':
    case 'doc':
      return Colors.blue.shade700;
    case 'xlsx':
    case 'csv':
      return Colors.green;
    default:
      return Colors.grey;
  }
}

/// Alias rétrocompat — délègue à [FormatUtils.bytesStorage] (style EN B/KB/MB/GB).
String formatSize(int bytes) => FormatUtils.bytesStorage(bytes);

bool isValidFileName(String name) {
  if (name.contains('/') || name.contains('\\')) return false;
  if (name == '.' || name == '..') return false;
  for (final c in name.codeUnits) {
    if (c < 0x20) return false;
  }
  return true;
}
