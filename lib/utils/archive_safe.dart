import 'dart:typed_data';

import 'package:archive/archive.dart';

/// Lecture bornée d'une entrée d'archive ZIP.
///
/// **Pourquoi ce fichier existe.** Depuis F2/F4 v2.12.0, l'application se
/// protège des zip-bombs en testant `entry.size` avant d'accéder à
/// `entry.content`. Une relecture externe (Gemini 3 Pro, 2026-08-09) a montré
/// que cette garde ne garde rien : dans `package:archive`, `ArchiveFile.size`
/// vaut `zf.uncompressedSize` — une valeur lue telle quelle dans l'en-tête du
/// ZIP (`zip_file_header.dart:34`, `input.readUint32()`), donc **entièrement
/// choisie par celui qui a fabriqué le fichier**.
///
/// Un attaquant déclare `uncompressedSize = 0` et livre 2 Go de zéros
/// compressés : `0 > cap` est faux, la garde laisse passer, puis `.content`
/// lance l'inflate qui alloue les 2 Go réels. L'application meurt par OOM à
/// l'ouverture du document.
///
/// **Ce que fait la garde ici.** Deux niveaux, dans cet ordre :
///
/// 1. le contrôle bon marché sur la taille déclarée — il attrape le cas
///    honnête (un vrai gros fichier) sans rien décompresser ;
/// 2. la décompression elle-même, dirigée vers un puits qui **compte les
///    octets réellement produits** et interrompt l'inflate dès le
///    dépassement. C'est le seul contrôle qu'un en-tête mensonger ne peut pas
///    contourner, parce qu'il ne porte plus sur ce que le fichier *déclare*
///    mais sur ce qu'il *produit*.
///
/// Une seule implémentation, partagée par les quatre sites qui décompressent
/// (EPUB, DOCX/ODT, extraction de texte, visionneuse ZIP) : une garde recopiée
/// à la main se re-oublie, et c'est précisément ce qui s'est produit.
class ArchiveTooLargeException implements Exception {
  final String label;
  final int maxBytes;

  const ArchiveTooLargeException(this.label, this.maxBytes);

  @override
  String toString() =>
      'Entrée « $label » refusée : contenu décompressé supérieur à '
      '${maxBytes ~/ (1024 * 1024)} Mo. Fichier probablement piégé.';
}

/// Puits de décompression qui refuse d'écrire au-delà de [maxBytes].
///
/// `Inflate.stream` écrit au fil de l'eau ; lever depuis `writeBytes`
/// interrompt la décompression sur-le-champ, donc la mémoire consommée reste
/// bornée par [maxBytes] au lieu de suivre la bombe.
/// Puits de décompression qui refuse de dépasser [maxBytes].
///
/// Enveloppe un `OutputStream` réel plutôt que de réimplémenter un tampon :
/// `Inflate` ne se contente pas de l'interface `OutputStreamBase`, il appelle
/// aussi `subset()` et `getBytes()` sur un `output` typé `dynamic`
/// (`inflate.dart:312-319`, la copie arrière LZ77 relit ce qui vient d'être
/// écrit). Un puits qui n'exposerait que l'interface abstraite ferait échouer
/// la décompression de tout fichier légitime — c'est ce qu'a montré le test
/// « une entrée pile à la limite passe ».
///
/// Le contrôle a lieu APRÈS chaque écriture : on peut donc dépasser le
/// plafond d'un bloc d'inflate au plus (quelques dizaines de kilo-octets)
/// avant de lever. C'est sans effet sur l'objectif, qui est d'empêcher une
/// allocation de plusieurs gigaoctets.
class _CappedOutput extends OutputStreamBase {
  final int maxBytes;
  final String label;
  final OutputStream _inner = OutputStream();

  _CappedOutput(this.maxBytes, this.label);

  @override
  int get length => _inner.length;

  void _check() {
    if (_inner.length > maxBytes) {
      throw ArchiveTooLargeException(label, maxBytes);
    }
  }

  @override
  void writeByte(int value) {
    _inner.writeByte(value);
    _check();
  }

  @override
  void writeBytes(List<int> bytes, [int? len]) {
    _inner.writeBytes(bytes, len);
    _check();
  }

  @override
  void writeInputStream(InputStreamBase stream) {
    _inner.writeInputStream(stream);
    _check();
  }

  @override
  void writeUint16(int value) {
    _inner.writeUint16(value);
    _check();
  }

  @override
  void writeUint32(int value) {
    _inner.writeUint32(value);
    _check();
  }

  @override
  void writeUint64(int value) {
    _inner.writeUint64(value);
    _check();
  }

  @override
  void flush() => _inner.flush();

  // Utilisés par Inflate via `dynamic` — absents de OutputStreamBase, donc
  // pas de @override.
  List<int> subset(int start, [int? end]) => _inner.subset(start, end);
  List<int> getBytes() => _inner.getBytes();
  void clear() => _inner.clear();
}

/// Renvoie le contenu décompressé de [entry], en refusant de dépasser
/// [maxBytes] **octets réellement produits**.
///
/// Lève [ArchiveTooLargeException] si l'entrée dépasse le plafond, que ce soit
/// d'après son en-tête ou d'après ce qu'elle produit vraiment.
Uint8List safeEntryBytes(ArchiveFile entry, String label, int maxBytes) {
  // 1. Contrôle bon marché : une taille déclarée déjà hors bornes n'a pas
  //    besoin d'être décompressée pour être refusée. Ne protège que du cas
  //    honnête — un en-tête peut mentir, d'où l'étape 2.
  if (entry.size > maxBytes) {
    throw ArchiveTooLargeException(label, maxBytes);
  }

  // 2. Contrôle réel : on inflate NOUS-MÊMES vers un puits borné.
  //
  //    `ArchiveFile.decompress(output)` ne convient pas : pour une entrée
  //    issue d'un `ZipDecoder`, le décodeur passe un `ZipFile` — qui étend
  //    `FileContent` — si bien que `_content` n'est pas nul et que
  //    `decompress()` sort immédiatement sans rien écrire dans notre puits
  //    (`archive_file.dart:176`). C'est `ZipFile.content` qui décompresse, et
  //    il le fait par `inflateBuffer(_rawContent.toUint8List())`, sans aucune
  //    borne (`zip_file.dart:164`). Passer par lui, c'est déjà avoir alloué la
  //    bombe.
  //
  //    On part donc de `rawContent` — les octets encore compressés — et on
  //    dirige l'inflate vers le puits. La première version de cette fonction
  //    appelait `decompress(out)` et rendait un buffer VIDE ; les tests l'ont
  //    montré tout de suite. Sans eux, elle aurait été livrée en annonçant une
  //    protection qu'elle n'exerçait pas — pire que pas de garde du tout.
  final raw = entry.rawContent;
  if (raw == null) {
    // Entrée construite en mémoire (pas issue d'un décodage) : le contenu est
    // déjà là, il n'y a rien à inflater.
    final direct = entry.content as List<int>;
    if (direct.length > maxBytes) {
      throw ArchiveTooLargeException(label, maxBytes);
    }
    return Uint8List.fromList(direct);
  }

  final out = _CappedOutput(maxBytes, label);
  if (entry.compressionType == ArchiveFile.DEFLATE) {
    Inflate.stream(raw, out);
  } else {
    // STORE : rien à décompresser, mais la taille reste à contrôler.
    //
    // La copie se fait par tranches et non d'un seul `writeInputStream`.
    // `_CappedOutput` ne vérifie le plafond qu'APRÈS chaque écriture : un
    // unique appel aurait donc tout copié avant que la borne ne s'exprime, ce
    // qui la rendait inopérante sur ce chemin. Signalé par la relecture GPT
    // du 2026-08-09. Le chemin DEFLATE n'avait pas ce défaut : `Inflate`
    // écrit par blocs, donc le contrôle s'intercale naturellement.
    const chunk = 64 * 1024;
    while (!raw.isEOS) {
      final remaining = raw.length;
      out.writeInputStream(
        raw.readBytes(remaining < chunk ? remaining : chunk),
      );
    }
  }
  return Uint8List.fromList(out.getBytes());
}
