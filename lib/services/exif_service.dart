import 'dart:io';
import 'dart:typed_data';
import 'package:image/image.dart' as img;
import 'package:path_provider/path_provider.dart';

/// Suppression des métadonnées EXIF (GPS, date, appareil, orientation rotative…).
///
/// Approche : on décode l'image, on conserve l'orientation visuelle (le pixel
/// data est déjà rotaté correctement par décodage), puis on ré-encode SANS
/// transmettre les ExifData. Le package `image` 4.x n'inclut pas l'EXIF lors
/// d'un `encodeJpg`/`encodePng` à partir d'une `Image` créée sans `exif`.
class ExifService {
  /// Retourne un nouveau fichier dans le cache avec EXIF effacé. JPEG ou PNG.
  /// Conserve la qualité au maximum (JPEG quality 95) car le but est privacy,
  /// pas compression.
  Future<File> stripExif(File source, {int jpegQuality = 95}) async {
    final bytes = await source.readAsBytes();
    final decoded = img.decodeImage(bytes);
    if (decoded == null) {
      throw const FormatException('Image illisible — impossible d\'effacer EXIF');
    }
    // Crée une nouvelle image SANS clone des metadata. `Image.from(decoded)` recopie
    // l'EXIF ; on construit donc à partir des pixels uniquement.
    final clean = img.Image(
      width: decoded.width,
      height: decoded.height,
      numChannels: decoded.numChannels,
    );
    // Copie pixel-à-pixel — coûteux mais garantit aucune metadata.
    for (var y = 0; y < decoded.height; y++) {
      for (var x = 0; x < decoded.width; x++) {
        clean.setPixel(x, y, decoded.getPixel(x, y));
      }
    }

    final ext = source.path.toLowerCase().split('.').last;
    Uint8List out;
    String outExt;
    switch (ext) {
      case 'jpg':
      case 'jpeg':
        out = Uint8List.fromList(img.encodeJpg(clean, quality: jpegQuality));
        outExt = 'jpg';
        break;
      case 'png':
        out = Uint8List.fromList(img.encodePng(clean));
        outExt = 'png';
        break;
      default:
        // Format non géré (HEIC, WebP encode non disponible) → JPEG par défaut
        out = Uint8List.fromList(img.encodeJpg(clean, quality: jpegQuality));
        outExt = 'jpg';
    }

    final tmp = await getTemporaryDirectory();
    final base = source.path.split(RegExp(r'[/\\]')).last
        .replaceAll(RegExp(r'\.[^.]+$'), '');
    final dest = File('${tmp.path}/${base}_no_exif.$outExt');
    await dest.writeAsBytes(out);
    return dest;
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
