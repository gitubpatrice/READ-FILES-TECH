/// Écriture atomique : `tmp + flush + rename`.
///
/// Garantit qu'un kill OS / OOM en milieu d'écriture ne laisse pas un fichier
/// final tronqué. Le `rename()` POSIX est atomique sur un même système de
/// fichiers — d'où le temporaire placé **à côté** de la cible, et non dans le
/// dossier temporaire du système, qui peut être sur un autre volume.
///
/// En cas d'erreur, le `.tmp` résiduel est supprimé best-effort.
library;

import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

/// Compteur de processus, pour que deux appels rapprochés ne puissent pas
/// tomber sur le même nom même si l'aléa se répétait.
int _seq = 0;

final _rand = Random.secure();

/// Nom du fichier temporaire, **unique à chaque appel**.
///
/// **Pourquoi ce n'est pas `'$path.tmp'`.** Ce nom fixe, dérivé de la seule
/// cible, était partagé par tous les appels visant le même fichier. Deux
/// écritures concurrentes écrivaient donc dans le MÊME temporaire, puis le
/// renommaient chacune à leur tour : le fichier final pouvait contenir un
/// mélange des deux, ou une version tronquée. Signalé par la relecture GPT du
/// 2026-08-10.
///
/// Ce n'est pas théorique ici : `atomicWriteBytes` est le point d'écriture du
/// coffre, de la corbeille, des conversions et des sauvegardes. Deux
/// opérations de corbeille qui se chevauchent suffisent.
///
/// L'unicité ferme au passage un second risque : un `.tmp` pré-existant posé
/// en lien symbolique par un tiers faisait écrire hors de la cible. Un nom
/// imprévisible ne peut pas être devancé.
String _tmpPathFor(String path) {
  final n = _seq = (_seq + 1) & 0xFFFF;
  final r = _rand.nextInt(1 << 32).toRadixString(16);
  return '$path.$n-$r.tmp';
}

/// Écrit [path] de façon atomique, via [write] qui reçoit le fichier
/// temporaire. Corps commun aux variantes ci-dessous, qui n'en différaient
/// que par une ligne.
Future<void> _atomically(
  String path,
  Future<void> Function(File tmp) write,
) async {
  final tmp = File(_tmpPathFor(path));
  try {
    await write(tmp);
    await tmp.rename(path);
  } catch (_) {
    try {
      if (await tmp.exists()) await tmp.delete();
    } catch (_) {
      // Le nettoyage est best-effort : masquer l'erreur d'origine par une
      // erreur de nettoyage rendrait la panne réelle indéchiffrable.
    }
    rethrow;
  }
}

/// Écrit [bytes] dans [path], atomiquement.
/// Reserve un fichier au nom UNIQUE dans [dir], et le rend deja cree.
///
/// **Pourquoi reserver plutot que composer un nom.** Les sorties temporaires
/// etaient nommees a partir du seul nom du fichier source :
/// `contrat_signe.pdf`, `IMG_0001_no_exif.jpg`. Signer deux `contrat.pdf`
/// venant de dossiers differents, ou nettoyer deux `IMG_0001.jpg`, ecrivait
/// donc au MEME chemin : le second ecrasait le premier, et l'appelant qui
/// partageait ensuite « son » resultat partageait celui de l'autre.
///
/// La creation est EXCLUSIVE : entre le moment ou l'on choisit un nom libre et
/// celui ou l'on ecrit, rien ne peut s'intercaler. Tester puis agir laisserait
/// precisement cette fenetre — le motif corrige partout ailleurs le
/// 2026-08-11.
Future<File> reserveTempFile({
  required String dir,
  required String base,
  required String extension,
}) async {
  String assainir(String s) => s
      .replaceAll(RegExp(r'[\x00-\x1f/\\:*?"<>|]'), '_')
      .replaceAll(RegExp(r'^\.+'), '');

  final propre = assainir(base);
  final racine = propre.isEmpty ? 'fichier' : propre;
  // L'EXTENSION est assainie elle aussi. Elle ne vient aujourd'hui que de
  // constantes internes (`pdf`, `jpg`, `png`), mais une fonction qui compose un
  // chemin ne doit pas dependre de la prudence de ses appelants : un
  // `extension` porteur de separateurs ou de `..` fabriquerait un chemin hors
  // du dossier vise. Signale par la relecture GPT du 2026-08-11.
  final ext = assainir(extension);
  final suffixeExt = ext.isEmpty ? 'bin' : ext;

  for (var i = 0; i < 10000; i++) {
    final suffixe = i == 0 ? '' : '_$i';
    final f = File('$dir/$racine$suffixe.$suffixeExt');
    try {
      await f.create(exclusive: true);
      return f;
    } on FileSystemException catch (e) {
      // Seule une COLLISION justifie d'essayer le nom suivant. Traiter toute
      // erreur comme une collision faisait enchainer dix mille tentatives sur
      // un dossier absent ou en lecture seule, avant de lever un message qui
      // ne parlait pas de la vraie cause.
      if (await f.exists()) continue;
      throw FileSystemException(
        'Impossible de creer un fichier temporaire : ${e.message}',
        f.path,
        e.osError,
      );
    }
  }
  throw FileSystemException('Impossible de reserver un nom libre', dir);
}

/// Ecrit dans un fichier temporaire RESERVE, en le supprimant si l'ecriture
/// echoue.
///
/// [reserveTempFile] cree le fichier immediatement — c'est ce qui rend la
/// reservation exclusive. Mais si l'ecriture qui suit echoue (disque plein,
/// encodage impossible, annulation), le fichier VIDE restait sur le disque et
/// s'accumulait a chaque tentative ratee. Signale par la relecture GPT du
/// 2026-08-11.
Future<File> writeReservedTempFile({
  required String dir,
  required String base,
  required String extension,
  required Future<void> Function(File cible) ecrire,
}) async {
  final f = await reserveTempFile(dir: dir, base: base, extension: extension);
  try {
    await ecrire(f);
    return f;
  } catch (_) {
    // Nettoyage au mieux : si la suppression echoue elle aussi, on ne masque
    // surtout pas l'erreur d'origine, qui est celle qui interesse l'appelant.
    try {
      if (await f.exists()) await f.delete();
    } catch (_) {}
    rethrow;
  }
}

Future<void> atomicWriteBytes(String path, List<int> bytes) =>
    _atomically(path, (tmp) => tmp.writeAsBytes(bytes, flush: true));

/// Variante string. UTF-8 par défaut.
Future<void> atomicWriteString(String path, String content) =>
    _atomically(path, (tmp) => tmp.writeAsString(content, flush: true));

/// Variante prenant un `Uint8List` typé (évite cast inutile).
Future<void> atomicWriteUint8(String path, Uint8List bytes) =>
    atomicWriteBytes(path, bytes);
