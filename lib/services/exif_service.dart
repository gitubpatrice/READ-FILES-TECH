import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:image/image.dart' as img;
import 'package:path_provider/path_provider.dart';

/// Suppression des métadonnées EXIF (GPS, date, appareil, orientation rotative…).
///
/// Approche : on décode l'image, on **réinitialise les ExifData** (toutes les
/// IFDs vidées) puis on ré-encode. `image` 4.x n'écrit l'EXIF en sortie que
/// si `decoded.exif.isNotEmpty` — vidage = aucun EXIF dans le fichier final.
///
/// Le décodage + ré-encodage est fait dans un Isolate (offload coûteux pour
/// images > 5 Mpx — sur S9 / Redmi 9C, freezait l'UI 5-15 s avant v2.5.5).
class ExifService {
  /// Retourne un nouveau fichier dans le cache avec EXIF effacé. JPEG ou PNG.
  Future<File> stripExif(File source, {int jpegQuality = 95}) async {
    final bytes = await source.readAsBytes();
    final ext = PathUtils.fileExt(source.path);
    // Décode + ré-encode dans un Isolate pour ne pas geler l'UI.
    final result = await Isolate.run(
      () => _stripBytes(bytes, ext, jpegQuality),
    );
    if (result == null) {
      throw const FormatException(
        'Image illisible — impossible d\'effacer EXIF',
      );
    }

    final tmp = await getTemporaryDirectory();
    var base = source.path
        .split(RegExp(r'[/\\]'))
        .last
        .replaceAll(RegExp(r'\.[^.]+$'), '')
        .replaceAll(RegExp(r'[\x00-\x1f/\\:*?"<>|]'), '_');
    if (base.isEmpty || base == '.' || base == '..') base = 'image';
    final dest = File('${tmp.path}/${base}_no_exif.${result.$2}');
    await dest.writeAsBytes(result.$1);
    return dest;
  }

  /// Worker Isolate : retourne (bytes, extension de sortie) ou null si KO.
  static (Uint8List, String)? _stripBytes(
    Uint8List bytes,
    String ext,
    int jpegQuality,
  ) {
    final decoded = img.decodeImage(bytes);
    if (decoded == null) return null;
    // Vide l'EXIF in-place — tous les IFDs (image, exif, gps, interop, thumbnail).
    decoded.exif.clear();

    Uint8List out;
    String outExt;
    switch (ext) {
      case 'jpg':
      case 'jpeg':
        out = Uint8List.fromList(img.encodeJpg(decoded, quality: jpegQuality));
        outExt = 'jpg';
        break;
      case 'png':
        out = Uint8List.fromList(img.encodePng(decoded));
        outExt = 'png';
        break;
      default:
        out = Uint8List.fromList(img.encodeJpg(decoded, quality: jpegQuality));
        outExt = 'jpg';
    }
    return (out, outExt);
  }

  /// Inspecte sommairement les métadonnées présentes dans un fichier image.
  /// Utilisé pour l'affichage "avant" (informatif).
  Future<Map<String, String>> inspect(File source) async {
    try {
      final bytes = await source.readAsBytes();
      final decoded = img.decodeImage(bytes);
      if (decoded == null) return {};
      final exif = decoded.exif;
      final out = <String, String>{};
      // GPS
      if (exif.gpsIfd.data.isNotEmpty) out['GPS'] = 'présent';
      // EXIF principal — accès via map data (les getters typés varient selon
      // la version du package). Tag 0x9003 = DateTimeOriginal.
      final dto = exif.exifIfd.data[0x9003]?.toString();
      if (dto != null && dto.isNotEmpty) out['Prise de vue'] = dto;
      // IFD0 — Tag 0x010F=Make, 0x0110=Model, 0x0131=Software.
      final make = exif.imageIfd.data[0x010F]?.toString();
      if (make != null && make.isNotEmpty) out['Marque'] = make;
      final model = exif.imageIfd.data[0x0110]?.toString();
      if (model != null && model.isNotEmpty) out['Modèle'] = model;
      final software = exif.imageIfd.data[0x0131]?.toString();
      if (software != null && software.isNotEmpty) out['Logiciel'] = software;
      out['Dimensions'] = '${decoded.width} × ${decoded.height}';
      return out;
    } catch (_) {
      return {};
    }
  }
}
