import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:image/image.dart' as img;
import 'package:path_provider/path_provider.dart';
import '../utils/atomic_write.dart';
import '../utils/file_caps.dart';
import '../utils/image_bounds.dart';

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
    // F5 : cap fichier + dimensions IHDR avant decode (anti image-bomb).
    final size = await source.length();
    if (size > FileCaps.imageFile) {
      throw const FormatException(
        'Image trop volumineuse (max ${FileCaps.imageFile ~/ (1024 * 1024)} Mo).',
      );
    }
    final bytes = await source.readAsBytes();
    final dimErr = ImageBounds.assertSafeBounds(bytes);
    if (dimErr != null) throw FormatException(dimErr);
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
    final base = source.path
        .split(RegExp(r'[/\\]'))
        .last
        .replaceAll(RegExp(r'\.[^.]+$'), '');
    // Nom RESERVE, et non compose : nettoyer deux `IMG_0001.jpg` venant de
    // dossiers differents ecrivait au meme chemin, et le second ecrasait le
    // premier. L'utilisateur partageait alors la mauvaise image.
    return writeReservedTempFile(
      dir: tmp.path,
      base: '${base}_no_exif',
      extension: result.$2,
      ecrire: (cible) => atomicWriteUint8(cible.path, result.$1),
    );
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
  ///
  /// F8 v2.13.0 — Mêmes gardes anti-image-bomb que `stripExif` : cap fichier
  /// + probe IHDR/SOF avant `decodeImage`. Auparavant `inspect` (souvent
  /// appelé en preview) chargeait sans cap → vecteur OOM identique à F5
  /// v2.12.0 sur le chemin écriture.
  /// Une map VIDE signifie « aucune metadonnee », et rien d'autre.
  ///
  /// Cette methode enveloppait tout dans un `catch (_) { return {}; }` : une
  /// permission refusee, un fichier efface en cours de lecture ou une image
  /// piegee rendaient la meme reponse qu'une photo parfaitement propre.
  /// L'utilisateur en concluait que son image ne portait aucune metadonnee,
  /// alors que rien n'avait pu etre lu.
  ///
  /// L'ecran appelant possede DEJA un chemin d'erreur (`exif_screen.dart:56`),
  /// que ce `catch` rendait inatteignable. Les echecs remontent desormais.
  Future<Map<String, String>> inspect(File source) async {
    final size = await source.length();
    if (size > FileCaps.imageFile) {
      throw const FormatException(
        'Image trop volumineuse (max ${FileCaps.imageFile ~/ (1024 * 1024)} Mo).',
      );
    }
    final bytes = await source.readAsBytes();
    final dimErr = ImageBounds.assertSafeBounds(bytes);
    if (dimErr != null) throw FormatException(dimErr);
    final decoded = img.decodeImage(bytes);
    if (decoded == null) {
      throw const FormatException('Image illisible — format non reconnu.');
    }
    {
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
    }
  }
}
