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
///
/// Enveloppe un `OutputMemoryStream` réel plutôt que de réimplémenter un
/// tampon : `Inflate` appelle aussi `subset()` et `getBytes()` pour la copie
/// arrière LZ77, qui relit ce qui vient d'être écrit (`inflate.dart:335`). Un
/// puits qui ne tiendrait pas son propre contenu à disposition ferait échouer
/// la décompression de tout fichier légitime — c'est ce qu'a montré le test
/// « une entrée pile à la limite passe ».
///
/// Le contrôle a lieu APRÈS chaque écriture : on peut donc dépasser le
/// plafond d'un bloc d'inflate au plus (quelques dizaines de kilo-octets)
/// avant de lever. C'est sans effet sur l'objectif, qui est d'empêcher une
/// allocation de plusieurs gigaoctets.
class _CappedOutput extends OutputStream {
  final int maxBytes;
  final String label;
  final OutputMemoryStream _inner = OutputMemoryStream();

  _CappedOutput(this.maxBytes, this.label)
    : super(byteOrder: ByteOrder.littleEndian);

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
  void writeBytes(List<int> bytes, {int? length}) {
    _inner.writeBytes(bytes, length: length);
    _check();
  }

  /// Copie **par tranches**, et c'est la seule façon correcte de l'écrire.
  ///
  /// Le plafond n'est vérifié qu'APRÈS chaque écriture. Recopier un flux d'un
  /// seul appel aurait donc tout matérialisé avant que la borne ne puisse
  /// s'exprimer — la garde serait présente et inopérante. Le défaut existait
  /// dans la version archive 3, où l'appelant devait découper lui-même ;
  /// signalé par la relecture GPT du 2026-08-09. Le découpage vit désormais
  /// **ici**, si bien que la borne tient quel que soit l'appelant : c'est ce
  /// que le chemin STORE d'archive 4 exige, `ZipFile.decompress` s'y réduisant
  /// à un unique `output.writeStream(...)`.
  @override
  void writeStream(InputStream stream) {
    const chunk = 64 * 1024;
    while (!stream.isEOS) {
      // `InputStream.length` vaut le nombre d'octets RESTANTS, pas la taille
      // totale du flux — la 4.x le documente ainsi (« How many bytes are left
      // in the stream »). Une relecture externe a soutenu le contraire le
      // 2026-08-09 ; c'est la source qui tranche.
      final remaining = stream.length;
      if (remaining <= 0) break;
      _inner.writeStream(
        stream.readBytes(remaining < chunk ? remaining : chunk),
      );
      _check();
    }
  }

  @override
  void flush() => _inner.flush();

  @override
  void clear() => _inner.clear();

  // Utilisés par Inflate pour la copie arrière LZ77 (`inflate.dart:335`), qui
  // relit ce qu'il vient d'écrire. En archive 3 ils étaient appelés via
  // `dynamic` et absents de l'interface ; la 4.x les y a remontés. D'où le
  // besoin de déléguer au tampon réel plutôt que de tenir notre propre buffer.
  @override
  Uint8List subset(int start, [int? end]) => _inner.subset(start, end);

  @override
  Uint8List getBytes() => _inner.getBytes();
}

/// `true` si [bytes] commence par une signature d'archive ZIP.
///
/// **Pourquoi ce contrôle est devenu nécessaire.** Jusqu'à `archive` 3,
/// `ZipDecoder().decodeBytes` levait sur des octets qui n'étaient pas une
/// archive, et les quatre sites qui décodent s'appuyaient tous sur ce `throw`
/// pour dire « fichier illisible ». La 4.x ne lève plus : elle rend une
/// **archive vide**. Vérifié le 2026-08-10 sur du texte brut, un tampon vide et
/// un en-tête `PK` tronqué — aucune exception, zéro entrée à chaque fois.
///
/// Sans ce contrôle, un `.zip` corrompu s'afficherait comme une archive
/// parfaitement valide et vide, un `.docx` renvoyé par le réseau dirait
/// « `document.xml` introuvable » au lieu de « ce n'est pas un .docx », et la
/// panne réelle serait masquée par un message qui accuse autre chose.
///
/// Reconnaît les trois signatures du format : entrée locale (`PK\x03\x04`),
/// fin de répertoire central pour une archive légitimement vide
/// (`PK\x05\x06`), et marqueur d'archive segmentée (`PK\x07\x08`).
bool looksLikeZip(List<int> bytes) {
  if (bytes.length < 4) return false;
  if (bytes[0] != 0x50 || bytes[1] != 0x4B) return false;
  final c = bytes[2];
  final d = bytes[3];
  return (c == 0x03 && d == 0x04) ||
      (c == 0x05 && d == 0x06) ||
      (c == 0x07 && d == 0x08);
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
  //    On part de `rawContent` — les octets encore compressés — et on dirige
  //    l'inflate vers le puits. En archive 3, la première version de cette
  //    fonction appelait `decompress(out)` et rendait un buffer VIDE ; les
  //    tests l'ont montré tout de suite. Sans eux, elle aurait été livrée en
  //    annonçant une protection qu'elle n'exerçait pas — pire que pas de garde
  //    du tout.
  //
  //    **En archive 4, `decompress(out)` remplit bien le puits — et reste
  //    pourtant à proscrire, pour une raison entièrement différente.** Il
  //    délègue à `ZLibDecoder.decodeStream`, qui sur `dart:io` alimente un
  //    `ChunkedConversionSink.withCallback` (`_zlib_decoder_io.dart:24`) :
  //    cette variante accumule tous les fragments et ne rappelle notre puits
  //    qu'à la fermeture. La bombe est donc intégralement décompressée avant
  //    que la garde ne puisse parler. Le refus reste correct, l'allocation ne
  //    l'est plus — c'est exactement l'ANR du Galaxy S9 du 2026-08-09.
  //
  //    Mesuré le 2026-08-10 sur une bombe de 256 Mo plafonnée à 1 Mo : 0 ms
  //    par `Inflate.stream`, 110 ms par `decompress`. Le test « la borne tient,
  //    pas seulement le refus » garde ce piège fermé ; il a été écrit APRÈS
  //    avoir constaté que les neuf tests existants restaient verts malgré la
  //    substitution.
  final rawContent = entry.rawContent;
  if (rawContent == null) {
    // Entrée construite en mémoire (pas issue d'un décodage) : le contenu est
    // déjà là, il n'y a rien à inflater.
    final direct = entry.content;
    if (direct.length > maxBytes) {
      throw ArchiveTooLargeException(label, maxBytes);
    }
    return Uint8List.fromList(direct);
  }

  // `getStream(decompress: false)` rend les octets ENCORE COMPRESSÉS, mais
  // après déchiffrement éventuel (`zip_file.dart:201-219`). En archive 3 il
  // fallait lire `rawContent` directement, ce qui court-circuitait ZipCrypto
  // et AES ; la 4.x rend donc ce chemin strictement meilleur.
  final raw = rawContent.getStream(decompress: false);
  final out = _CappedOutput(maxBytes, label);

  if (entry.compression == CompressionType.deflate) {
    // **Inflate en pur Dart, délibérément — ne pas remplacer par
    // `rawContent.decompress(out)`.**
    //
    // Ce raccourci semble naturel en archive 4 : `ZipFile.decompress` sait
    // traiter DEFLATE, BZIP2 et STORE d'un seul appel. Mais sur `dart:io` —
    // c'est-à-dire sur Android, la seule cible qui compte ici — il délègue à
    // `ZLibDecoder.decodeStream`, qui alimente un
    // `ChunkedConversionSink.withCallback` (`_zlib_decoder_io.dart:24`). Cette
    // variante **accumule tous les fragments et ne rappelle qu'à la
    // fermeture** : les 300 Mo d'une bombe seraient intégralement matérialisés
    // avant que `writeBytes` n'atteigne notre puits. La garde serait présente,
    // et sans effet.
    //
    // `Inflate` écrit bloc par bloc, donc le contrôle s'intercale réellement.
    Inflate.stream(raw, output: out);
  } else {
    // STORE (ou toute autre méthode) : rien à décompresser, mais la taille
    // reste à contrôler. Le découpage nécessaire vit dans
    // `_CappedOutput.writeStream`, pas ici.
    out.writeStream(raw);
  }
  return Uint8List.fromList(out.getBytes());
}
